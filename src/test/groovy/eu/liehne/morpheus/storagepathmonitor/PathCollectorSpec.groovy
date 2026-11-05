package eu.liehne.morpheus.storagepathmonitor

import groovy.json.JsonOutput
import spock.lang.Specification
import spock.lang.Timeout

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class PathCollectorSpec extends Specification {

	static final String OK_OUTPUT = '{"errorCode":0,"data":[{"device":"lun1","paths":2,"active":2}]}'
	static final Clock CLOCK = Clock.fixed(Instant.parse('2026-01-01T10:00:00Z'), ZoneOffset.UTC)

	ClusterInventory inventory = Stub()
	HostCommandRunner runner = Mock()
	PathCollector collector = new PathCollector(inventory, runner, { MonitorSettings.DEFAULTS } as Supplier, CLOCK)

	/** Answers like a host with the given GFS2 mounts ({@code device: mountPoint}) that are not withdrawn */
	static String answer(String command, Map<String, String> mounts = [:]) {
		if (command == MultipathStatus.COMMAND) {
			return OK_OUTPUT
		}
		int number = 0
		if (command == HostMounts.MOUNT_COMMAND) {
			return JsonOutput.toJson([filesystems: mounts.collect { device, target ->
				[source: "/dev/mapper/${device}".toString(), target: target, fstype: 'gfs2', 'maj:min': "252:${number++}".toString()]
			}])
		}
		return mounts.collect { device, target ->
			int id = number++
			"/sys/fs/gfs2/c:f${id}/id:252:${id}\n/sys/fs/gfs2/c:f${id}/withdraw:0"
		}.join('\n')
	}

	def 'multipath failure skips the other commands, results per host'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a'), new HostRef(12L, 'b')], (8L): [new HostRef(13L, 'c')]]

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)
		Snapshot s = collector.snapshot

		then:
		1 * runner.run(11L, MultipathStatus.COMMAND, 30) >> OK_OUTPUT
		1 * runner.run(11L, HostMounts.MOUNT_COMMAND, 30) >> '{"filesystems":[]}'
		1 * runner.run(12L, MultipathStatus.COMMAND, 30) >> { throw new HostCommandException('command failed with exit code 127') }
		1 * runner.run(13L, MultipathStatus.COMMAND, 30) >> 'garbage'
		0 * runner._
		s.lastRunAt == CLOCK.instant()
		s.hostsByCluster.keySet() == [7L, 8L] as Set
		s.measurements[11L].success
		s.measurements[11L].devices == [lun1: new MultipathStatus.Device(2, 2)]
		s.measurements[11L].mounts == []
		s.measurements[12L].error == 'command failed with exit code 127'
		s.measurements[13L].error == 'command output is not valid JSON'
	}

	def 'mounts and withdraw state are measured; a failing check keeps the paths'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a'), new HostRef(12L, 'b'), new HostRef(13L, 'c')]]
		runner.run(_, MultipathStatus.COMMAND, _) >> OK_OUTPUT
		runner.run(11L, HostMounts.MOUNT_COMMAND, _) >> answer(HostMounts.MOUNT_COMMAND, [lun1: '/mnt/a'])
		runner.run(11L, HostMounts.WITHDRAW_COMMAND, _) >> '/sys/fs/gfs2/c:f/id:252:0\n/sys/fs/gfs2/c:f/withdraw:1'
		runner.run(12L, HostMounts.MOUNT_COMMAND, _) >> { throw new HostCommandException('no answer within 30 s') }
		runner.run(13L, HostMounts.MOUNT_COMMAND, _) >> answer(HostMounts.MOUNT_COMMAND, [lun1: '/mnt/a'])
		runner.run(13L, HostMounts.WITHDRAW_COMMAND, _) >> { throw new HostCommandException('command failed on the host') }

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)
		Map<Long, HostMeasurement> m = collector.snapshot.measurements

		then:
		m[11L].mounts == [new Mount('lun1', '/mnt/a', '252:0', true)]
		m[11L].mountError == null
		m[12L].success
		m[12L].mounts == null
		m[12L].mountError == 'mount check failed: no answer within 30 s'
		m[13L].mounts == [new Mount('lun1', '/mnt/a', '252:0', null)]
		m[13L].mountError == 'withdraw check failed: command failed on the host'
	}

	def 'a mount the withdraw output does not cover has a reason for being unknown'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a')]]
		runner.run(11L, MultipathStatus.COMMAND, _) >> OK_OUTPUT
		runner.run(11L, HostMounts.MOUNT_COMMAND, _) >> answer(HostMounts.MOUNT_COMMAND, [lun1: '/mnt/a'])
		runner.run(11L, HostMounts.WITHDRAW_COMMAND, _) >> '/sys/fs/gfs2/c:f/id:9:9\n/sys/fs/gfs2/c:f/withdraw:0'

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)
		HostMeasurement m = collector.snapshot.measurements[11L]

		then:
		m.mounts == [new Mount('lun1', '/mnt/a', '252:0', null)]
		m.mountError == 'withdraw check failed: mount not covered by the command output'
	}

	def 'without an answer for the cluster no mount becomes an orphan'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a')]]
		inventory.knownStorage(_) >> [:]
		runner.run(11L, _, _) >> { Long id, String command, int t -> answer(command, [lost: '/mnt/lost']) }

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.orphansByCluster == [:]
	}

	def 'a mount without a datastore counts as orphaned from the second run on, until it is gone'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a')]]
		inventory.knownStorage(_) >> [(7L): new KnownStorage(['lun1'] as Set, ['/mnt/known'] as Set)]
		Map<String, String> mounts = [lun1: '/mnt/a', lun2: '/mnt/known', lost: '/mnt/lost']
		runner.run(11L, _, _) >> { Long id, String command, int t -> answer(command, mounts) }

		when: 'first run: only a candidate'
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.orphansByCluster == [:]

		when: 'second run: confirmed, datastores known by device or mount point are left out'
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.orphansByCluster == [(7L): [new OrphanMount(11L, 'lost', '/mnt/lost', false, CLOCK.instant())]]

		when: 'unmounted'
		mounts.remove('lost')
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.orphansByCluster == [:]
	}

	def 'finished datastores are read before measuring and published when the run ends; a failed read keeps the last answer'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a')]]
		List<Set<String>> seenWhileMeasuring = []
		int reads = 0
		inventory.settledDevices(_) >> {
			if (++reads == 3) {
				throw new IllegalStateException('database gone')
			}
			[(7L): ["lun${reads}".toString()] as Set]
		}
		runner.run(11L, _, _) >> { Long id, String command, int t ->
			seenWhileMeasuring << collector.snapshot.settledByCluster[7L]
			answer(command)
		}

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		seenWhileMeasuring.first() == null
		collector.snapshot.settledByCluster == [(7L): ['lun1'] as Set]

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.settledByCluster == [(7L): ['lun2'] as Set]

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.settledByCluster == [(7L): ['lun2'] as Set]
	}

	def 'a datastore that appears after the first sighting is no orphan'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a')]]
		inventory.knownStorage(_) >>> [[(7L): new KnownStorage([] as Set, [] as Set)], [(7L): new KnownStorage(['new'] as Set, [] as Set)]]
		runner.run(11L, _, _) >> { Long id, String command, int t -> answer(command, [new: '/mnt/new']) }

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.orphansByCluster == [:]
	}

	def 'orphans stay while the host cannot be measured or Morpheus cannot be asked'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a')]]
		int calls = 0
		inventory.knownStorage(_) >> {
			if (++calls == 4) {
				throw new IllegalStateException('database gone')
			}
			[(7L): new KnownStorage([] as Set, [] as Set)]
		}
		boolean reachable = true
		runner.run(11L, _, _) >> { Long id, String command, int t ->
			if (!reachable) {
				throw new HostCommandException('no answer within 30 s')
			}
			answer(command, [lost: '/mnt/lost'])
		}
		List<OrphanMount> expected = [new OrphanMount(11L, 'lost', '/mnt/lost', false, CLOCK.instant())]

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)
		collector.runOnce(MonitorSettings.DEFAULTS)
		reachable = false
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.orphansByCluster == [(7L): expected]

		when:
		reachable = true
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.orphansByCluster == [(7L): expected]
	}

	def 'unexpected exceptions become a generic error'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a')]]
		runner.run(11L, _, _) >> { throw new NullPointerException('internal detail') }

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.measurements[11L].error == 'measurement failed'
	}

	def 'hosts that are no longer monitored are dropped'() {
		given:
		List<Map<Long, List<HostRef>>> inventories = [[(7L): [new HostRef(11L, 'a'), new HostRef(12L, 'b')]],
													  [(7L): [new HostRef(11L, 'a')]]]
		inventory.monitoredClusters() >>> inventories
		runner.run(_, _, _) >> { Long id, String command, int t -> answer(command) }

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.measurements.keySet() == [11L] as Set
	}

	def 'no hosts, no commands'() {
		given:
		inventory.monitoredClusters() >> [:]

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		0 * runner._
		collector.snapshot.lastRunAt == CLOCK.instant()
	}

	@Timeout(20)
	def 'never more hosts in parallel than configured'() {
		given:
		List<HostRef> hosts = (1L..12L).collect { new HostRef(it, "h${it}") }
		inventory.monitoredClusters() >> [(7L): hosts]
		AtomicInteger running = new AtomicInteger()
		AtomicInteger peak = new AtomicInteger()
		HostCommandRunner slow = { Long id, String command, int timeout ->
			int now = running.incrementAndGet()
			peak.accumulateAndGet(now) { a, b -> Math.max(a, b) }
			Thread.sleep(50)
			running.decrementAndGet()
			answer(command)
		} as HostCommandRunner
		PathCollector c = new PathCollector(inventory, slow, { MonitorSettings.DEFAULTS } as Supplier, CLOCK)

		when:
		c.runOnce(new MonitorSettings(60, 3, 30))

		then:
		peak.get() <= 3
		peak.get() >= 2
		c.snapshot.measurements.size() == 12
	}

	@Timeout(20)
	def 'stop interrupts a running collection and no further results are published'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a')]]
		CountDownLatch started = new CountDownLatch(1)
		CountDownLatch interrupted = new CountDownLatch(1)
		HostCommandRunner blocking = { Long id, String command, int timeout ->
			started.countDown()
			try {
				Thread.sleep(60000)
			} catch (InterruptedException e) {
				interrupted.countDown()
				throw new HostCommandException('interrupted')
			}
			OK_OUTPUT
		} as HostCommandRunner
		PathCollector c = new PathCollector(inventory, blocking, { MonitorSettings.DEFAULTS } as Supplier, CLOCK)
		Thread run = Thread.start { c.runOnce(MonitorSettings.DEFAULTS) }

		when:
		started.await(5, TimeUnit.SECONDS)
		c.stop()
		run.join(5000)

		then:
		interrupted.await(5, TimeUnit.SECONDS)
		!run.alive
		c.snapshot.measurements.isEmpty()
	}

	@Timeout(20)
	def 'a host without answer gets a timeout error and its late result is ignored'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a')]]
		CountDownLatch release = new CountDownLatch(1)
		CountDownLatch finished = new CountDownLatch(1)
		HostCommandRunner stubborn = { Long id, String command, int timeout ->
			// ignores interrupts, like a call without timeout
			while (release.count > 0) {
				try {
					release.await()
				} catch (InterruptedException ignored) {
				}
			}
			finished.countDown()
			OK_OUTPUT
		} as HostCommandRunner
		PathCollector c = new PathCollector(inventory, stubborn, { MonitorSettings.DEFAULTS } as Supplier, CLOCK)
		c.hostGraceSeconds = 0

		when:
		c.runOnce(new MonitorSettings(60, 1, 1))
		HostMeasurement afterRun = c.snapshot.measurements[11L]
		release.countDown()
		finished.await(5, TimeUnit.SECONDS)
		Thread.sleep(100)

		then:
		afterRun.error == 'no answer within 1 s'
		c.snapshot.measurements[11L].error == 'no answer within 1 s'
	}

	def 'a host whose worker dies gets an error instead of keeping its old result'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a')]]
		runner.run(11L, MultipathStatus.COMMAND, _) >>> [OK_OUTPUT] >> { throw new StackOverflowError() }
		runner.run(11L, HostMounts.MOUNT_COMMAND, _) >> answer(HostMounts.MOUNT_COMMAND)

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)
		collector.runOnce(MonitorSettings.DEFAULTS)

		then:
		collector.snapshot.measurements[11L].error == 'measurement failed'
	}

	def 'state changes are reported once'() {
		given:
		Instant t = CLOCK.instant()
		def ok = HostMeasurement.success(5L, t, [a: new MultipathStatus.Device(2, 2), b: new MultipathStatus.Device(2, 2)])
		def degraded = HostMeasurement.success(5L, t, [a: new MultipathStatus.Device(2, 1), b: new MultipathStatus.Device(2, 2)])
		def lost = HostMeasurement.success(5L, t, [a: new MultipathStatus.Device(2, 1)])
		def failed = HostMeasurement.failure(5L, t, 'no answer within 30 s')

		expect:
		PathCollector.changes(null, ok) == []
		PathCollector.changes(null, degraded) == ['host 5 device a: degraded (1/2 paths active)']
		PathCollector.changes(ok, ok) == []
		PathCollector.changes(ok, degraded) == ['host 5 device a: ok -> degraded (1/2 paths active)']
		PathCollector.changes(degraded, lost) == ['host 5 device b: ok -> down (device missing)']
		PathCollector.changes(ok, failed) == ['host 5: measurement failed: no answer within 30 s']
		PathCollector.changes(failed, failed) == []
		PathCollector.changes(failed, ok) == ['host 5: measurement works again']
	}

	def 'mount changes are reported once'() {
		given:
		Instant t = CLOCK.instant()
		Mount fine = new Mount('a', '/mnt/a', '1:1', false)
		Mount withdrawn = new Mount('a', '/mnt/a', '1:1', true)
		def with = { Mount... mounts -> HostMeasurement.success(5L, t, [:], mounts as List) }

		expect:
		PathCollector.changes(null, with(fine)) == []
		PathCollector.changes(null, with(withdrawn)) == ['host 5: file system on /mnt/a (a) withdrawn']
		PathCollector.changes(with(fine), with(fine)) == []
		PathCollector.changes(with(), with(fine)) == ['host 5: a mounted on /mnt/a']
		PathCollector.changes(with(fine), with()) == ['host 5: a no longer mounted on /mnt/a']
		PathCollector.changes(with(fine), with(withdrawn)) == ['host 5: file system on /mnt/a (a) withdrawn']
		PathCollector.changes(with(withdrawn), with(withdrawn)) == []
		PathCollector.changes(with(fine), HostMeasurement.success(5L, t, [:], null, 'mount check failed: x')) == []
	}

	def 'start and stop are idempotent and threads are daemons'() {
		when:
		collector.start()
		collector.start()
		Set<Thread> threads = Thread.allStackTraces.keySet().findAll { it.name.startsWith('storage-path-monitor-') }
		collector.stop()
		collector.stop()

		then:
		threads.size() == 1
		threads.every { it.daemon }
	}
}
