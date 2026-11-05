package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import spock.lang.Specification

class MorpheusQueriesSpec extends Specification {

	static final String LUN = '{"blockDevice":"/dev/mapper/3abc"}'

	MorpheusSynchronousComputeServerGroupService clusters = Mock()
	MorpheusSynchronousDatastoreService datastoreService = Mock()
	MorpheusSynchronousComputeServerService servers = Mock()
	MorpheusContext morpheus = Stub {
		getServices() >> Stub(MorpheusServices) {
			getCluster() >> clusters
			getComputeServer() >> servers
			getCloud() >> Stub(MorpheusSynchronousCloudService) {
				getDatastore() >> datastoreService
			}
		}
	}
	MorpheusQueries queries = new MorpheusQueries(morpheus, new AccessPolicy())

	User tenantUser = new User(id: 5L, account: new Account(id: 5L, masterAccount: false))
	User masterUser = new User(id: 1L, account: new Account(id: 1L, masterAccount: true))

	def 'cluster visible only with matching tenant and the user scope'() {
		given:
		List<DataQuery> seen = []
		clusters.listOptions(_) >> { DataQuery q -> seen << q; [[id: 7L, 'owner.id': 6L]] }

		expect:
		!queries.clusterVisible(tenantUser, 7L)
		queries.clusterVisible(masterUser, 7L)
		!queries.clusterVisible(masterUser, 8L)
		!queries.clusterVisible(null, 7L)
		queries.clusterVisible(new User(id: 2L), 7L, new Account(id: 1L, masterAccount: true))
		!queries.clusterVisible(new User(id: 2L), 7L, new Account(id: 5L, masterAccount: false))
		queries.clusterVisible(new User(id: 2L), 7L, new Account(id: 6L, masterAccount: false))
		!queries.clusterVisible(new User(id: 2L), 7L)
		!queries.clusterVisible(new User(id: 2L), 7L, null)
		seen.every { it.propertyList == MorpheusQueries.CLUSTER_PROPS && it.max == MorpheusQueries.MAX_ROWS }
	}

	def 'a user without tenant gets the tenant handed over as query scope'() {
		given:
		Account tenant = new Account(id: 6L, masterAccount: false)
		DataQuery seen = null
		clusters.listOptions(_) >> { DataQuery q -> seen = q; [[id: 7L, 'owner.id': 6L]] }

		when:
		boolean visible = queries.clusterVisible(new User(id: 2L), 7L, tenant)

		then:
		visible
		seen.account.is(tenant)
	}

	def 'datastores: own tenant, own cluster, multipath device only, sorted by name'() {
		given:
		datastoreService.listOptions(_) >> [
			[id: 1L, name: 'b-shared', refType: 'ComputeServerGroup', refId: 7L, config: LUN, 'owner.id': 5L],
			[id: 2L, name: 'a-shared', refType: 'ComputeServerGroup', refId: 7L, config: '{"blockDevice":"/dev/mapper/3def"}', 'owner.id': 5L],
			[id: 3L, name: 'other-tenant', refType: 'ComputeServerGroup', refId: 7L, config: LUN, 'owner.id': 6L],
			[id: 4L, name: 'other-cluster', refType: 'ComputeServerGroup', refId: 8L, config: LUN, 'owner.id': 5L],
			[id: 5L, name: 'local', refType: 'ComputeServerGroup', refId: 7L, config: '{"path":"/var/lib"}', 'owner.id': 5L],
			[id: 6L, name: 'cloud', refType: 'ComputeZone', refId: 7L, config: LUN, 'owner.id': 5L]]

		expect:
		queries.datastores(tenantUser, 7L) == [new SharedDatastore(2L, 'a-shared', '3def'), new SharedDatastore(1L, 'b-shared', '3abc')]
	}

	def 'a datastore Morpheus has not finished is not provisioned'() {
		given:
		datastoreService.listOptions(_) >> [
			[id: 1L, name: 'a', refType: 'ComputeServerGroup', refId: 7L, config: LUN, 'owner.id': 5L, status: 'provisioned'],
			[id: 2L, name: 'b', refType: 'ComputeServerGroup', refId: 7L, config: LUN, 'owner.id': 5L, status: 'provisioning'],
			[id: 3L, name: 'c', refType: 'ComputeServerGroup', refId: 7L, config: LUN, 'owner.id': 5L, status: 'warning']]

		expect:
		queries.datastores(tenantUser, 7L)*.provisioned == [true, false, true]
	}

	def 'settled devices: multipath datastores of the cluster that Morpheus is not working on'() {
		given:
		datastoreService.listOptions(_) >> [
			[id: 1L, refType: 'ComputeServerGroup', refId: 7L, config: LUN, status: 'provisioned'],
			[id: 2L, refType: 'ComputeServerGroup', refId: 7L, config: '{"blockDevice":"/dev/mapper/3new"}', status: 'provisioning'],
			[id: 3L, refType: 'ComputeServerGroup', refId: 7L, config: '{"blockDevice":"/dev/mapper/3warn"}', status: 'warning'],
			[id: 4L, refType: 'ComputeServerGroup', refId: 7L, config: '{"path":"/var/lib"}', status: 'provisioned']]

		expect:
		queries.settledDevices([7L, 9L]) == [(7L): ['3abc', '3warn'] as Set, (9L): [] as Set]
		queries.settledDevices([]) == [:]
	}

	def 'monitored clusters: all HVM clusters, also without a shared LUN, and their hosts without parent'() {
		given:
		clusters.listOptions(_) >> [[id: 7L, 'owner.id': 1L], [id: 8L, 'owner.id': 1L]]
		servers.listOptions(_) >> [
			[id: 12L, name: 'h2', 'serverGroup.id': 7L, 'parentServer.id': null],
			[id: 11L, name: 'h1', 'serverGroup.id': 7L, 'parentServer.id': null],
			[id: 20L, name: 'vm', 'serverGroup.id': 7L, 'parentServer.id': 11L]]

		when:
		Map result = queries.monitoredClusters()

		then:
		0 * datastoreService.listOptions(_)
		result == [(7L): [new HostRef(11L, 'h1'), new HostRef(12L, 'h2')], (8L): []]
	}

	def 'known storage: device and mount point of every datastore of the cluster, without user scope'() {
		given:
		DataQuery seen = null
		datastoreService.listOptions(_) >> { DataQuery q ->
			seen = q
			[[id: 1L, refType: 'ComputeServerGroup', refId: 7L, config: LUN, externalPath: '/mnt/a', 'owner.id': 99L],
			 [id: 2L, refType: 'ComputeServerGroup', refId: 7L, config: '{"blockDevice":"/dev/sdx"}'],
			 [id: 3L, refType: 'ComputeServerGroup', refId: 7L, config: '{"path":"/var/lib"}', externalPath: '/mnt/c'],
			 [id: 4L, refType: 'ComputeServerGroup', refId: 8L, config: '{"blockDevice":"/dev/mapper/3other"}'],
			 [id: 5L, refType: 'ComputeZone', refId: 7L, config: '{"blockDevice":"/dev/mapper/3zone"}']]
		}

		when:
		Map<Long, KnownStorage> known = queries.knownStorage([7L, 9L])

		then:
		seen.user == null
		known[7L].devices == ['3abc', '/dev/sdx'] as Set
		known[7L].mountPoints == ['/mnt/a', '/mnt/c'] as Set
		known[9L].devices.isEmpty()
	}

	def 'no HVM cluster, no further queries'() {
		when:
		Map result = queries.monitoredClusters()

		then:
		1 * clusters.listOptions(_) >> []
		0 * datastoreService.listOptions(_)
		0 * servers.listOptions(_)
		result == [:]
	}
}
