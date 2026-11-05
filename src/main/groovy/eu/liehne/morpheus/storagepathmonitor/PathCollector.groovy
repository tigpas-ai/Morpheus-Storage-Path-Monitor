package eu.liehne.morpheus.storagepathmonitor

import groovy.util.logging.Slf4j

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/**
 * Background collection on the manager, independent of any request.
 *
 * Each run reads the settings, determines the monitored clusters and their hosts, and runs the fixed commands once per
 * host (one call covers all LUNs of the host), with at most {@code parallelHosts} hosts at the same time.
 * Each host result is published as soon as it is available. The next run starts {@code intervalSeconds} after the
 * previous one ended, so runs never overlap. Requests only read the published {@link Snapshot}.
 *
 * At the end of a run, the mounts are compared with the datastores Morpheus knows at that moment. A mount only counts as
 * orphaned once two runs in a row found it without a datastore, so a datastore being created never shows up as one.
 */
@Slf4j
class PathCollector {

	static final long INITIAL_DELAY_SECONDS = 30
	/** Extra time for loading the host before the command timeout applies */
	static final long HOST_GRACE_SECONDS = 30
	/** Wait for interrupted workers at the end of a run */
	static final long WORKER_GRACE_SECONDS = 5
	/** Commands a host may need in one run: paths, mounts, withdraw state */
	static final int COMMANDS_PER_HOST = 3

	/** Mounts without a datastore in the last run → first run that saw them; only used by the (single) run thread */
	private Map<OrphanKey, Instant> orphanCandidates = [:]

	private final ClusterInventory inventory
	private final HostCommandRunner runner
	private final Supplier<MonitorSettings> settings
	private final Clock clock
	private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY)
	/** Number of the current run; a result is only published while its run is current */
	private final AtomicLong runs = new AtomicLong()

	/** Extra time per host before a run gives up on it; shorter only in tests */
	long hostGraceSeconds = HOST_GRACE_SECONDS

	private final Object lifecycle = new Object()
	private ScheduledExecutorService scheduler
	private volatile ExecutorService workers
	private volatile boolean stopped

	PathCollector(ClusterInventory inventory, HostCommandRunner runner, Supplier<MonitorSettings> settings,
				  Clock clock = Clock.systemUTC()) {
		this.inventory = inventory
		this.runner = runner
		this.settings = settings
		this.clock = clock
	}

	Snapshot getSnapshot() {
		return snapshot.get()
	}

	void start() {
		synchronized (lifecycle) {
			if (scheduler != null) {
				return
			}
			stopped = false
			ScheduledExecutorService own = Executors.newSingleThreadScheduledExecutor(threads('storage-path-monitor-scheduler'))
			scheduler = own
			own.schedule({ loop(own) } as Runnable, INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
			log.info('Storage Path Monitor: collection started')
		}
	}

	/** Stops the schedule and interrupts a running collection; safe to call more than once */
	void stop() {
		ScheduledExecutorService s
		ExecutorService w
		synchronized (lifecycle) {
			stopped = true
			s = scheduler
			scheduler = null
			w = workers
			s?.shutdownNow()
			w?.shutdownNow()
		}
		// Wait outside the lock: the scheduler thread needs it to finish its run
		try {
			s?.awaitTermination(5, TimeUnit.SECONDS)
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt()
		}
		log.info('Storage Path Monitor: collection stopped')
	}

	/** One scheduled run; reschedules itself only on the scheduler it belongs to and while that one is current */
	private void loop(ScheduledExecutorService own) {
		MonitorSettings current = MonitorSettings.DEFAULTS
		try {
			current = settings.get() ?: MonitorSettings.DEFAULTS
			runOnce(current)
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt()
		} catch (Throwable e) {
			log.error('Storage Path Monitor: collection run failed', e)
		} finally {
			synchronized (lifecycle) {
				if (!stopped && scheduler.is(own) && !own.isShutdown() && !Thread.currentThread().isInterrupted()) {
					own.schedule({ loop(own) } as Runnable, current.intervalSeconds, TimeUnit.SECONDS)
				}
			}
		}
	}

	/** One collection run; public for tests */
	void runOnce(MonitorSettings s) throws InterruptedException {
		long run = runs.incrementAndGet()
		Instant start = clock.instant()
		Map<Long, List<HostRef>> clusters = inventory.monitoredClusters() ?: [:]
		List<Long> hostIds = clusters.values().collectMany { it*.id }.unique()
		// Publish the current host list first: hosts that left are dropped, new hosts show up as not measured yet
		snapshot.updateAndGet { Snapshot old ->
			new Snapshot(clusters, old.measurements.findAll { it.key in hostIds }, old.lastRunAt, old.orphansByCluster, old.settledByCluster)
		}
		Map<Long, Set<String>> settled = readSettled(clusters.keySet())
		if (hostIds) {
			ExecutorService pool = Executors.newFixedThreadPool(Math.min(s.parallelHosts, hostIds.size()),
				threads('storage-path-monitor-worker'))
			workers = pool
			try {
				if (stopped) {
					return
				}
				Map<Long, Future<?>> futures = hostIds.collectEntries { Long id ->
					[(id): pool.submit({ record(measure(id, s), run) } as Runnable)]
				}
				long deadline = System.nanoTime() +
					TimeUnit.SECONDS.toNanos(s.commandTimeoutSeconds * COMMANDS_PER_HOST + hostGraceSeconds) *
					((hostIds.size() + s.parallelHosts - 1).intdiv(s.parallelHosts) as long)
				futures.each { Long id, Future<?> future ->
					try {
						future.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
					} catch (TimeoutException ignored) {
						future.cancel(true)
						record(HostMeasurement.failure(id, clock.instant(), "no answer within ${s.commandTimeoutSeconds} s".toString()), run)
					} catch (java.util.concurrent.ExecutionException e) {
						log.warn("Storage Path Monitor: measuring host ${id} failed", e.cause)
						record(HostMeasurement.failure(id, clock.instant(), 'measurement failed'), run)
					}
				}
			} finally {
				// Results of this run are final now: late workers are ignored (run counter)
				runs.compareAndSet(run, run + 1)
				pool.shutdownNow()
				workers = null
				// Give interrupted workers a moment to end, so they do not overlap with the next run
				if (!pool.awaitTermination(WORKER_GRACE_SECONDS, TimeUnit.SECONDS)) {
					log.warn('Storage Path Monitor: host queries still running after the run ended')
				}
			}
		}
		Map<Long, List<OrphanMount>> orphans = findOrphans(clusters, start)
		snapshot.updateAndGet { Snapshot old ->
			new Snapshot(old.hostsByCluster, old.measurements, start, orphans, settled ?: old.settledByCluster)
		}
	}

	/** Finished datastores before measuring; {@code null} if Morpheus cannot be asked (the last answer stays valid) */
	private Map<Long, Set<String>> readSettled(Collection<Long> clusterIds) {
		try {
			return inventory.settledDevices(clusterIds)
		} catch (Exception e) {
			log.warn("Storage Path Monitor: could not read the datastores before measuring: ${e.message}")
			return null
		}
	}

	/**
	 * Compares the mounts with the datastores Morpheus knows now. Hosts without a current mount list keep what was known
	 * about them. If Morpheus cannot be asked, nothing changes: an empty answer must not turn every mount into an orphan.
	 */
	private Map<Long, List<OrphanMount>> findOrphans(Map<Long, List<HostRef>> clusters, Instant start) {
		Snapshot current = snapshot.get()
		Map<Long, KnownStorage> known
		try {
			known = inventory.knownStorage(clusters.keySet())
		} catch (Exception e) {
			log.warn("Storage Path Monitor: could not read the datastores to compare mounts: ${e.message}")
			return current.orphansByCluster
		}
		Map<OrphanKey, Instant> candidates = [:]
		Map<Long, List<OrphanMount>> orphans = [:]
		clusters.each { Long clusterId, List<HostRef> hosts ->
			KnownStorage storage = known?.get(clusterId)
			List<OrphanMount> found = []
			hosts.each { HostRef host ->
				List<Mount> mounts = current.measurements.get(host.id)?.mounts
				if (mounts == null || storage == null) {
					// nothing to compare in this run: keep its candidates and confirmed orphans
					orphanCandidates.findAll { it.key.hostId == host.id }.each { candidates.put(it.key, it.value) }
					found.addAll(current.orphansByCluster.get(clusterId)?.findAll { it.hostId == host.id } ?: [])
					return
				}
				mounts.findAll { !storage.knows(it) }.each { Mount mount ->
					OrphanKey key = new OrphanKey(host.id, mount.device, mount.mountPoint)
					Instant since = orphanCandidates.get(key)
					candidates.put(key, since ?: start)
					if (since != null) {
						found << new OrphanMount(host.id, mount.device, mount.mountPoint, mount.withdrawn, since)
					}
				}
			}
			if (found) {
				orphans.put(clusterId, found.sort { it.hostId }.asImmutable())
			}
		}
		logOrphanChanges(current.orphansByCluster, orphans)
		orphanCandidates = candidates
		return orphans
	}

	private static void logOrphanChanges(Map<Long, List<OrphanMount>> before, Map<Long, List<OrphanMount>> now) {
		Closure<Set<OrphanKey>> keys = { Map<Long, List<OrphanMount>> byCluster ->
			byCluster.values().collectMany { list -> list.collect { new OrphanKey(it.hostId, it.device, it.mountPoint) } } as Set
		}
		Set<OrphanKey> old = keys(before)
		Set<OrphanKey> current = keys(now)
		(current - old).each {
			log.warn("Storage Path Monitor: host ${it.hostId}: mount ${it.mountPoint} of ${it.device} has no datastore in Morpheus")
		}
		(old - current).each {
			log.warn("Storage Path Monitor: host ${it.hostId}: mount ${it.mountPoint} of ${it.device} is no longer orphaned")
		}
	}

	private HostMeasurement measure(Long hostId, MonitorSettings s) {
		try {
			Map<String, MultipathStatus.Device> devices =
				MultipathStatus.parse(runner.run(hostId, MultipathStatus.COMMAND, s.commandTimeoutSeconds)).asImmutable()
			List<Mount> mounts
			try {
				mounts = HostMounts.parseMounts(runner.run(hostId, HostMounts.MOUNT_COMMAND, s.commandTimeoutSeconds))
			} catch (HostCommandException | MultipathStatus.ParseException e) {
				return HostMeasurement.success(hostId, clock.instant(), devices, null, "mount check failed: ${e.message}".toString())
			}
			if (!mounts) {
				return HostMeasurement.success(hostId, clock.instant(), devices, [])
			}
			String withdrawError = null
			Map<String, Boolean> withdrawn = [:]
			try {
				withdrawn = HostMounts.parseWithdrawn(runner.run(hostId, HostMounts.WITHDRAW_COMMAND, s.commandTimeoutSeconds))
			} catch (HostCommandException | MultipathStatus.ParseException e) {
				withdrawError = "withdraw check failed: ${e.message}".toString()
			}
			List<Mount> checked = HostMounts.withWithdrawn(mounts, withdrawn).asImmutable()
			if (withdrawError == null && checked.any { it.withdrawn == null }) {
				withdrawError = 'withdraw check failed: mount not covered by the command output'
			}
			return HostMeasurement.success(hostId, clock.instant(), devices, checked, withdrawError)
		} catch (HostCommandException | MultipathStatus.ParseException e) {
			return HostMeasurement.failure(hostId, clock.instant(), e.message)
		} catch (Exception e) {
			log.warn("Storage Path Monitor: measuring host ${hostId} failed", e)
			return HostMeasurement.failure(hostId, clock.instant(), 'measurement failed')
		}
	}

	/** Publishes a host result, unless the collector stopped or the run it belongs to has ended */
	private void record(HostMeasurement m, long run) {
		if (stopped || runs.get() != run) {
			return
		}
		HostMeasurement previous = null
		boolean stored = false
		snapshot.updateAndGet { Snapshot old ->
			// Only hosts that are still monitored
			stored = old.hostsByCluster.values().any { hosts -> hosts.any { it.id == m.hostId } }
			if (!stored) {
				return old
			}
			previous = old.measurements.get(m.hostId)
			Map<Long, HostMeasurement> measurements = new HashMap<>(old.measurements)
			measurements.put(m.hostId, m)
			new Snapshot(old.hostsByCluster, measurements, old.lastRunAt, old.orphansByCluster, old.settledByCluster)
		}
		if (stored) {
			changes(previous, m).each { log.warn("Storage Path Monitor: ${it}") }
		}
	}

	/**
	 * Log lines for state changes between two measurements of a host: measurement failing or working again, multipath
	 * devices whose state changed, and mounts that came, went or were withdrawn. On the first measurement only devices
	 * that are not ok and withdrawn mounts are reported.
	 */
	static List<String> changes(HostMeasurement previous, HostMeasurement current) {
		String host = "host ${current.hostId}"
		if (!current.success) {
			return previous == null || previous.success || previous.error != current.error
				? ["${host}: measurement failed: ${current.error}".toString()] : []
		}
		List<String> lines = []
		Map<String, MultipathStatus.Device> before = previous?.success ? previous.devices : null
		if (previous != null && !previous.success) {
			lines << "${host}: measurement works again".toString()
		}
		Set<String> names = new TreeSet<>(current.devices.keySet())
		if (before != null) {
			names.addAll(before.keySet())
		}
		names.each { String name ->
			MultipathStatus.Device now = current.devices.get(name)
			PathState state = PathState.of(now)
			String paths = now == null ? 'device missing' : "${now.active}/${now.paths} paths active"
			if (before == null) {
				if (state != PathState.OK) {
					lines << "${host} device ${name}: ${state.code} (${paths})".toString()
				}
			} else {
				PathState was = PathState.of(before.get(name))
				if (was != state) {
					lines << "${host} device ${name}: ${was.code} -> ${state.code} (${paths})".toString()
				}
			}
		}
		lines.addAll(mountChanges(host, previous?.success ? previous.mounts : null, current.mounts))
		return lines
	}

	private static List<String> mountChanges(String host, List<Mount> before, List<Mount> now) {
		if (now == null) {
			return []
		}
		List<String> lines = []
		Map<String, Mount> old = (before ?: []).collectEntries { [(it.mountPoint): it] }
		now.each { Mount mount ->
			Mount was = old.remove(mount.mountPoint)
			if (before != null && was == null) {
				lines << "${host}: ${mount.device} mounted on ${mount.mountPoint}".toString()
			}
			if (mount.withdrawn == Boolean.TRUE && was?.withdrawn != Boolean.TRUE) {
				lines << "${host}: file system on ${mount.mountPoint} (${mount.device}) withdrawn".toString()
			}
		}
		old.values().each { Mount mount ->
			lines << "${host}: ${mount.device} no longer mounted on ${mount.mountPoint}".toString()
		}
		return lines
	}

	private static ThreadFactory threads(String prefix) {
		AtomicInteger counter = new AtomicInteger()
		return { Runnable r ->
			Thread t = new Thread(r, "${prefix}-${counter.incrementAndGet()}".toString())
			t.daemon = true
			t
		} as ThreadFactory
	}
}
