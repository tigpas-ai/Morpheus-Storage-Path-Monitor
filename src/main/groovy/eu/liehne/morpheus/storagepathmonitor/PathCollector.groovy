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
 * Each run reads the settings, determines the monitored clusters and their hosts, and runs the fixed status command
 * once per host (one call covers all LUNs of the host), with at most {@code parallelHosts} hosts at the same time.
 * Each host result is published as soon as it is available. The next run starts {@code intervalSeconds} after the
 * previous one ended, so runs never overlap. Requests only read the published {@link Snapshot}.
 */
@Slf4j
class PathCollector {

	static final long INITIAL_DELAY_SECONDS = 30
	/** Extra time for loading the host before the command timeout applies */
	static final long HOST_GRACE_SECONDS = 30
	/** Wait for interrupted workers at the end of a run */
	static final long WORKER_GRACE_SECONDS = 5

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
			new Snapshot(clusters, old.measurements.findAll { it.key in hostIds }, old.lastRunAt)
		}
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
					TimeUnit.SECONDS.toNanos(s.commandTimeoutSeconds + hostGraceSeconds) *
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
		snapshot.updateAndGet { Snapshot old -> new Snapshot(old.hostsByCluster, old.measurements, start) }
	}

	private HostMeasurement measure(Long hostId, MonitorSettings s) {
		try {
			String output = runner.run(hostId, s.commandTimeoutSeconds)
			return HostMeasurement.success(hostId, clock.instant(), MultipathStatus.parse(output).asImmutable())
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
			new Snapshot(old.hostsByCluster, measurements, old.lastRunAt)
		}
		if (stored) {
			changes(previous, m).each { log.warn("Storage Path Monitor: ${it}") }
		}
	}

	/**
	 * Log lines for state changes between two measurements of a host: measurement failing or working again, and
	 * multipath devices whose state changed. On the first measurement only devices that are not ok are reported.
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
