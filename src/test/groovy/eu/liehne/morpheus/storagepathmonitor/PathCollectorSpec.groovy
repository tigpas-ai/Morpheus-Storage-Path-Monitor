package eu.liehne.morpheus.storagepathmonitor

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

	def 'one command per host, results per host'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a'), new HostRef(12L, 'b')], (8L): [new HostRef(13L, 'c')]]

		when:
		collector.runOnce(MonitorSettings.DEFAULTS)
		Snapshot s = collector.snapshot

		then:
		1 * runner.run(11L, 30) >> OK_OUTPUT
		1 * runner.run(12L, 30) >> { throw new HostCommandException('command failed with exit code 127') }
		1 * runner.run(13L, 30) >> 'garbage'
		0 * runner._
		s.lastRunAt == CLOCK.instant()
		s.hostsByCluster.keySet() == [7L, 8L] as Set
		s.measurements[11L].success
		s.measurements[11L].devices == [lun1: new MultipathStatus.Device(2, 2)]
		s.measurements[12L].error == 'command failed with exit code 127'
		s.measurements[13L].error == 'command output is not valid JSON'
	}

	def 'unexpected exceptions become a generic error'() {
		given:
		inventory.monitoredClusters() >> [(7L): [new HostRef(11L, 'a')]]
		runner.run(11L, _) >> { throw new NullPointerException('internal detail') }

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
		runner.run(_, _) >> OK_OUTPUT

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
		HostCommandRunner slow = { Long id, int timeout ->
			int now = running.incrementAndGet()
			peak.accumulateAndGet(now) { a, b -> Math.max(a, b) }
			Thread.sleep(50)
			running.decrementAndGet()
			OK_OUTPUT
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
		HostCommandRunner blocking = { Long id, int timeout ->
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
		HostCommandRunner stubborn = { Long id, int timeout ->
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
		runner.run(11L, _) >>> [OK_OUTPUT] >> { throw new StackOverflowError() }

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
