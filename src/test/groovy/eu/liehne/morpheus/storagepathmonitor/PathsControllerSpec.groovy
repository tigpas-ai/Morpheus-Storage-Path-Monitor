package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant
import java.util.function.Supplier

class PathsControllerSpec extends Specification {

	static final Instant T1 = Instant.parse('2026-01-01T10:00:00Z')
	static final Instant T2 = Instant.parse('2026-01-01T10:00:05Z')

	@Shared Account master = new Account(id: 1L, masterAccount: true)
	@Shared User admin = new User(id: 1L, account: master, permissions: ['infrastructure-cluster': 'full',
		'infrastructure-storage': 'full', 'admin-servers': 'full'])
	@Shared User storageOnly = new User(id: 2L, account: master, permissions: ['infrastructure-cluster': 'read',
		'infrastructure-storage': 'read'])
	@Shared User noStorage = new User(id: 3L, account: master, permissions: ['infrastructure-cluster': 'full',
		'infrastructure-storage': 'none', 'admin-servers': 'full'])
	@Shared User noCluster = new User(id: 4L, account: master, permissions: ['infrastructure-storage': 'read'])

	UserDirectory directory = Mock()
	Snapshot snapshot = new Snapshot(
		[(7L): [new HostRef(11L, 'host-a'), new HostRef(12L, 'host-b'), new HostRef(13L, 'host-c')]],
		[(11L): HostMeasurement.success(11L, T1, ['lun1': new MultipathStatus.Device(2, 2), 'lun2': new MultipathStatus.Device(2, 1)],
			[new Mount('lun1', '/mnt/1', '1:1', false), new Mount('lun2', '/mnt/2', '1:2', false)], null, ['ds-1': true, 'ds-2': true]),
		 (12L): HostMeasurement.success(12L, T2, ['lun1': new MultipathStatus.Device(2, 2)], [new Mount('lun1', '/mnt/1', '1:1', false)], null, ['ds-1': true, 'ds-2': true]),
		 (13L): HostMeasurement.failure(13L, T2, 'no answer within 30 s')],
		T1)
	PathsController controller = new PathsController(null, Stub(MorpheusContext), directory,
		{ snapshot } as Supplier<Snapshot>, { MonitorSettings.DEFAULTS } as Supplier<MonitorSettings>)

	def 'registers one read route with the storage permission'() {
		expect:
		controller.routes*.url == ['/storage-path-monitor/paths']
		controller.routes*.method == ['paths']
		controller.routes.every { it.permissions*.code == ['infrastructure-storage'] }
	}

	def 'returns counters and states per datastore and host'() {
		given:
		directory.clusterVisible(admin, 7L) >> true
		directory.datastores(admin, 7L) >> [new SharedDatastore(21L, 'ds-1', 'lun1'), new SharedDatastore(22L, 'ds-2', 'lun2')]

		when:
		JsonResponse response = controller.paths(model(admin, [clusterId: '7']))

		then:
		response.status == 200
		response.data == [
			clusterId      : 7L,
			state          : 'down',
			lastRunAt      : '2026-01-01T10:00:00Z',
			intervalSeconds: 60,
			datastores     : [
				[id: 21L, name: 'ds-1', state: 'unknown', counts: [ok: 2, unknown: 1, degraded: 0, down: 0], hosts: [
					[hostId: 11L, name: 'host-a', state: 'ok', activePaths: 2, totalPaths: 2, expectedPaths: 2, mounted: true, withdrawn: false, poolActive: true, measuredAt: '2026-01-01T10:00:00Z', error: null],
					[hostId: 12L, name: 'host-b', state: 'ok', activePaths: 2, totalPaths: 2, expectedPaths: 2, mounted: true, withdrawn: false, poolActive: true, measuredAt: '2026-01-01T10:00:05Z', error: null],
					[hostId: 13L, name: 'host-c', state: 'unknown', activePaths: null, totalPaths: null, expectedPaths: null, mounted: null, withdrawn: null, poolActive: null, measuredAt: '2026-01-01T10:00:05Z', error: 'no answer within 30 s']]],
				[id: 22L, name: 'ds-2', state: 'down', counts: [ok: 0, unknown: 1, degraded: 1, down: 1], hosts: [
					[hostId: 11L, name: 'host-a', state: 'degraded', activePaths: 1, totalPaths: 2, expectedPaths: 2, mounted: true, withdrawn: false, poolActive: true, measuredAt: '2026-01-01T10:00:00Z', error: null],
					[hostId: 12L, name: 'host-b', state: 'down', activePaths: 0, totalPaths: 0, expectedPaths: 2, mounted: false, withdrawn: false, poolActive: true, measuredAt: '2026-01-01T10:00:05Z', error: null],
					[hostId: 13L, name: 'host-c', state: 'unknown', activePaths: null, totalPaths: null, expectedPaths: null, mounted: null, withdrawn: null, poolActive: null, measuredAt: '2026-01-01T10:00:05Z', error: 'no answer within 30 s']]]
			],
			orphanMounts   : []
		]
	}

	@Unroll
	def 'not mounted only counts for a datastore that was finished before the measurements started'() {
		given:
		Map<String, MultipathStatus.Device> lun = [lun1: new MultipathStatus.Device(2, 2)]
		directory.clusterVisible(admin, 7L) >> true
		directory.datastores(admin, 7L) >> [new SharedDatastore(21L, 'ds-1', 'lun1', provisioned)]
		snapshot = new Snapshot([(7L): [new HostRef(11L, 'a')]], [(11L): HostMeasurement.success(11L, T1, lun, [], null, ['ds-1': false])], T1, [:], settled)

		when:
		Map host = (controller.paths(model(admin, [clusterId: '7'])).data as Map).datastores[0].hosts[0] as Map

		then:
		host.mounted == false
		host.state == expected

		where:
		provisioned | settled               || expected
		true        | [(7L): ['lun1'] as Set] || 'down'
		true        | [(7L): [] as Set]     || 'ok'
		true        | [:]                   || 'ok'
		false       | [(7L): ['lun1'] as Set] || 'ok'
		true        | null                  || 'down'
	}

	def 'mount state: mounted #mounted, withdrawn #withdrawn, expected to be mounted #expectMounted is #expected'() {
		expect:
		PathReport.mountState(mounted, withdrawn, expectMounted) == expected

		where:
		mounted | withdrawn | expectMounted || expected
		true    | false     | true        || PathState.OK
		true    | true      | true        || PathState.DOWN
		true    | null      | true        || PathState.UNKNOWN
		false   | false     | true        || PathState.DOWN
		false   | false     | false       || PathState.OK
		null    | null      | true        || PathState.UNKNOWN
	}

	def 'paths fine: not mounted and withdrawn are down, a failed check is unknown with its error'() {
		given:
		Map<String, MultipathStatus.Device> lun = [lun1: new MultipathStatus.Device(2, 2)]
		snapshot = new Snapshot([(7L): [new HostRef(11L, 'a'), new HostRef(12L, 'b'), new HostRef(13L, 'c'), new HostRef(14L, 'd')]],
			[(11L): HostMeasurement.success(11L, T1, lun, []),
			 (12L): HostMeasurement.success(12L, T1, lun, [new Mount('lun1', '/mnt/1', '1:1', true)]),
			 (13L): HostMeasurement.success(13L, T1, lun, null, 'mount check failed: no answer within 30 s'),
			 (14L): HostMeasurement.success(14L, T1, lun, [new Mount('lun1', '/mnt/1', '1:1', null)], 'withdraw check failed: command failed on the host')], T1)
		directory.clusterVisible(admin, 7L) >> true
		directory.datastores(admin, 7L) >> [new SharedDatastore(21L, 'ds-1', 'lun1')]

		when:
		Map ds = (controller.paths(model(admin, [clusterId: '7'])).data as Map).datastores[0] as Map

		then:
		ds.state == 'down'
		ds.hosts*.state == ['down', 'down', 'unknown', 'unknown']
		ds.hosts*.mounted == [false, true, null, true]
		ds.hosts*.withdrawn == [false, true, null, null]
		ds.hosts*.activePaths == [2, 2, 2, 2]
		ds.hosts*.error == [null, null, 'mount check failed: no answer within 30 s', 'withdraw check failed: command failed on the host']
	}

	def 'mounted, but the pool is inactive, missing or could not be read'() {
		given:
		Map<String, MultipathStatus.Device> lun = [lun1: new MultipathStatus.Device(2, 2)]
		List<Mount> mounted = [new Mount('lun1', '/mnt/1', '1:1', false)]
		snapshot = new Snapshot([(7L): [new HostRef(11L, 'a'), new HostRef(12L, 'b'), new HostRef(13L, 'c'), new HostRef(14L, 'd')]],
			[(11L): HostMeasurement.success(11L, T1, lun, mounted, null, [pool1: true]),
			 (12L): HostMeasurement.success(12L, T1, lun, mounted, null, [pool1: false]),
			 (13L): HostMeasurement.success(13L, T1, lun, mounted, null, [other: true]),
			 (14L): HostMeasurement.success(14L, T1, lun, mounted, null, null, 'pool check failed: no answer within 30 s')], T1)
		directory.clusterVisible(admin, 7L) >> true
		directory.datastores(admin, 7L) >> [new SharedDatastore(21L, 'ds-1', 'lun1', true, 'pool1')]

		when:
		Map ds = (controller.paths(model(admin, [clusterId: '7'])).data as Map).datastores[0] as Map

		then:
		ds.hosts*.state == ['ok', 'down', 'down', 'unknown']
		ds.hosts*.poolActive == [true, false, false, null]
		ds.hosts*.error == [null, null, null, 'pool check failed: no answer within 30 s']
	}

	def 'orphan mounts raise the cluster to degraded; details only for users who may see hosts'() {
		given:
		snapshot = new Snapshot([(7L): [new HostRef(11L, 'host-a')]], [:], T1,
			[(7L): [new OrphanMount(11L, 'lost', '/mnt/lost', false, T1)]])
		directory.clusterVisible(_, 7L) >> true
		directory.datastores(_, 7L) >> []

		expect:
		controller.paths(model(admin, [clusterId: '7'])).data == [clusterId: 7L, state: 'degraded',
			lastRunAt: '2026-01-01T10:00:00Z', intervalSeconds: 60, datastores: [],
			orphanMounts: [[hostId: 11L, name: 'host-a', device: 'lost', mountPoint: '/mnt/lost', withdrawn: false, since: '2026-01-01T10:00:00Z']]]
		controller.paths(model(storageOnly, [clusterId: '7'])).data.orphanMounts ==
			[[hostId: 11L, name: null, device: null, mountPoint: null, withdrawn: false, since: '2026-01-01T10:00:00Z']]
		controller.paths(model(admin, [clusterId: '7', hosts: 'false'])).data.orphanMounts.size() == 1
	}

	def 'a host with fewer paths than its peers is degraded even if all its paths are active'() {
		given:
		List<Mount> mounted = [new Mount('lun1', '/mnt/1', '1:1', false)]
		snapshot = new Snapshot([(7L): [new HostRef(11L, 'a'), new HostRef(12L, 'b'), new HostRef(13L, 'c')]],
			[(11L): HostMeasurement.success(11L, T1, [lun1: new MultipathStatus.Device(2, 2)], mounted, null, ['ds-1': true]),
			 (12L): HostMeasurement.success(12L, T1, [lun1: new MultipathStatus.Device(1, 1)], mounted, null, ['ds-1': true]),
			 (13L): HostMeasurement.success(13L, T1, [lun1: new MultipathStatus.Device(2, 2)], mounted, null, ['ds-1': true])], T1)
		directory.clusterVisible(admin, 7L) >> true
		directory.datastores(admin, 7L) >> [new SharedDatastore(21L, 'ds-1', 'lun1')]

		when:
		Map ds = (controller.paths(model(admin, [clusterId: '7'])).data as Map).datastores[0] as Map

		then:
		ds.state == 'degraded'
		ds.counts == [ok: 2, unknown: 0, degraded: 1, down: 0]
		ds.hosts*.state == ['ok', 'degraded', 'ok']
		ds.hosts*.expectedPaths == [2, 2, 2]
		ds.hosts[1].activePaths == 1
		ds.hosts[1].totalPaths == 1
	}

	def 'peer comparison: single host and unknown hosts'() {
		expect:
		PathReport.stateOf(new MultipathStatus.Device(1, 1), 1) == PathState.OK
		PathReport.stateOf(new MultipathStatus.Device(1, 1), 2) == PathState.DEGRADED
		PathReport.stateOf(new MultipathStatus.Device(2, 0), 2) == PathState.DOWN
		PathReport.stateOf(null, 2) == PathState.DOWN
		PathReport.expectedPaths(new SharedDatastore(1L, 'x', 'lun1'), [new HostRef(13L, 'c')],
			[(13L): HostMeasurement.failure(13L, T1, 'x')]) == 0
	}

	def 'hosts=false returns counters only'() {
		given:
		directory.clusterVisible(admin, 7L) >> true
		directory.datastores(admin, 7L) >> [new SharedDatastore(22L, 'ds-2', 'lun2')]

		expect:
		controller.paths(model(admin, [clusterId: '7', hosts: 'false'])).data.datastores ==
			[[id: 22L, name: 'ds-2', state: 'down', counts: [ok: 0, unknown: 1, degraded: 1, down: 1]]]
		controller.paths(model(admin, [clusterId: '7', hosts: 'yes'])).status == 400
	}

	def 'times are ISO-8601 in UTC with milliseconds'() {
		given:
		snapshot = new Snapshot([(7L): [new HostRef(11L, 'a')]],
			[(11L): HostMeasurement.success(11L, Instant.parse('2026-01-01T10:00:00.123456789Z'), [:])],
			Instant.parse('2026-01-01T09:59:59.5Z'))
		directory.clusterVisible(admin, 7L) >> true
		directory.datastores(admin, 7L) >> [new SharedDatastore(21L, 'ds-1', 'lun1')]

		when:
		Map data = controller.paths(model(admin, [clusterId: '7'])).data as Map

		then:
		data.lastRunAt == '2026-01-01T09:59:59.500Z'
		data.datastores[0].hosts[0].measuredAt == '2026-01-01T10:00:00.123Z'
	}

	def 'host names only for users who may see hosts'() {
		given:
		directory.clusterVisible(storageOnly, 7L) >> true
		directory.datastores(storageOnly, 7L) >> [new SharedDatastore(21L, 'ds-1', 'lun1')]

		when:
		Map data = controller.paths(model(storageOnly, [clusterId: '7'])).data as Map

		then:
		data.datastores[0].hosts*.name == [null, null, null]
		data.datastores[0].hosts*.hostId == [11L, 12L, 13L]
	}

	def 'a visible cluster without collected hosts reports its datastores as unknown'() {
		given:
		directory.clusterVisible(admin, 8L) >> true
		directory.datastores(admin, 8L) >> [new SharedDatastore(31L, 'ds-x', 'lunx')]

		when:
		Map data = controller.paths(model(admin, [clusterId: '8'])).data as Map

		then:
		data.state == 'unknown'
		data.datastores == [[id: 31L, name: 'ds-x', state: 'unknown', counts: [ok: 0, unknown: 0, degraded: 0, down: 0], hosts: []]]
	}

	def 'a cluster without shared datastores has no state'() {
		given:
		directory.clusterVisible(admin, 7L) >> true
		directory.datastores(admin, 7L) >> []

		expect:
		controller.paths(model(admin, [clusterId: '7'])).data == [clusterId: 7L, state: null,
			lastRunAt: '2026-01-01T10:00:00Z', intervalSeconds: 60, datastores: [], orphanMounts: []]
	}

	@Unroll
	def 'refuses user #user.id with 403 without asking the directory'() {
		when:
		JsonResponse response = controller.paths(model(user, [clusterId: '7']))

		then:
		response.status == 403
		0 * directory._

		where:
		user << [noStorage, noCluster, new User(id: 9L, account: master)]
	}

	def 'refuses requests without user'() {
		expect:
		controller.paths(model(null, [clusterId: '7'])).status == 403
	}

	def 'invisible or unknown cluster is 404 and its datastores are not read'() {
		when:
		JsonResponse response = controller.paths(model(admin, [clusterId: '7']))

		then:
		1 * directory.clusterVisible(admin, 7L) >> false
		0 * directory.datastores(_, _)
		response.status == 404
	}

	@Unroll
	def 'bad clusterId #value is 400'() {
		expect:
		controller.paths(model(admin, value == null ? [:] : [clusterId: value])).status == 400

		where:
		value << [null, '', 'abc', '7;1']
	}

	def 'only GET'() {
		expect:
		controller.paths(model(admin, [clusterId: '7'], 'POST')).status == 405
	}

	def 'errors are 500 without details'() {
		given:
		directory.clusterVisible(admin, 7L) >> { throw new IllegalStateException('database secret') }

		when:
		JsonResponse response = controller.paths(model(admin, [clusterId: '7']))

		then:
		response.status == 500
		response.data == [error: 'internal error']
	}

	private ViewModel model(User user, Map params = [:], String method = 'GET') {
		HttpServletRequest request = Stub {
			getParameter(_) >> { String name -> params[name] }
			getMethod() >> method
		}
		new ViewModel(user: user, request: request, response: Mock(HttpServletResponse))
	}
}
