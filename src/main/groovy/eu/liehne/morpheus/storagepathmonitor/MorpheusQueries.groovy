package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.User
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/** Inventory used by the background collection (system scope, no user) */
interface ClusterInventory {
	/** Cluster id → hosts, only HVM clusters with at least one shared-LUN datastore */
	Map<Long, List<HostRef>> monitoredClusters()
}

/** Objects as the signed-in user may see them */
interface UserDirectory {
	/** {@code true} if the HVM cluster exists and the user may see it */
	boolean clusterVisible(User user, Long clusterId)

	/** Shared-LUN datastores of the cluster that the user may see */
	List<SharedDatastore> datastores(User user, Long clusterId)
}

/**
 * Queries against the Morpheus data services. Only {@code listOptions} with an explicit {@code propertyList} is used:
 * it returns plain maps in milliseconds, while complete model objects cost about half a second per object.
 *
 * Cluster = {@code ComputeServerGroup} of type {@code mvm-cluster}; host = server whose {@code serverGroup} is the
 * cluster and that has no parent; datastores reference their cluster with {@code refType ComputeServerGroup} and
 * {@code refId}, the LUN is the setting {@code blockDevice} in their {@code config}.
 *
 * User queries are scoped with {@code new DataQuery(user)} and, on top of that, checked against the tenant (the
 * services do not guarantee resource filtering).
 */
@Slf4j
class MorpheusQueries implements ClusterInventory, UserDirectory {

	static final String CLUSTER_TYPE_CODE = 'mvm-cluster'
	static final String DATASTORE_REF_TYPE = 'ComputeServerGroup'
	static final Long MAX_ROWS = 10000L

	static final List<String> CLUSTER_PROPS = ['id', 'owner.id']
	static final List<String> HOST_PROPS = ['id', 'name', 'serverGroup.id', 'parentServer.id']
	static final List<String> DATASTORE_PROPS = ['id', 'name', 'refType', 'refId', 'config', 'owner.id']

	private final MorpheusContext morpheus
	private final AccessPolicy accessPolicy

	MorpheusQueries(MorpheusContext morpheus, AccessPolicy accessPolicy) {
		this.morpheus = morpheus
		this.accessPolicy = accessPolicy
	}

	@Override
	Map<Long, List<HostRef>> monitoredClusters() {
		List<Long> clusterIds = rows(morpheus.services.cluster,
			new DataQuery().withFilter('type.code', CLUSTER_TYPE_CODE), CLUSTER_PROPS).collect { it.id as Long }
		if (!clusterIds) {
			return [:]
		}
		DataQuery datastoreQuery = new DataQuery()
			.withFilter('refType', DATASTORE_REF_TYPE)
			.withFilter('refId', 'in', clusterIds)
		Set<Long> withLun = rows(morpheus.services.cloud.datastore, datastoreQuery, DATASTORE_PROPS)
			.findAll { it.refType == DATASTORE_REF_TYPE && deviceOf(it) != null }
			.collect { it.refId as Long }
			.findAll { it in clusterIds } as Set
		if (!withLun) {
			return [:]
		}
		Map<Long, List<HostRef>> result = withLun.collectEntries { [(it): []] }
		rows(morpheus.services.computeServer, new DataQuery().withFilter('serverGroup.id', 'in', withLun as List), HOST_PROPS)
			.findAll { it['parentServer.id'] == null && (it['serverGroup.id'] as Long) in withLun }
			.each { result[it['serverGroup.id'] as Long] << new HostRef(it.id as Long, it.name as String) }
		return result.collectEntries { k, v -> [(k): v.sort { it.id }.asImmutable()] }
	}

	@Override
	boolean clusterVisible(User user, Long clusterId) {
		if (user == null || clusterId == null) {
			return false
		}
		DataQuery query = new DataQuery(user).withFilter('id', clusterId).withFilter('type.code', CLUSTER_TYPE_CODE)
		Map cluster = rows(morpheus.services.cluster, query, CLUSTER_PROPS).find { (it.id as Long) == clusterId }
		return cluster != null && accessPolicy.canSeeTenant(user, cluster['owner.id'] as Long)
	}

	@Override
	List<SharedDatastore> datastores(User user, Long clusterId) {
		DataQuery query = new DataQuery(user)
			.withFilter('refType', DATASTORE_REF_TYPE)
			.withFilter('refId', clusterId)
		return rows(morpheus.services.cloud.datastore, query, DATASTORE_PROPS)
			.findAll {
				it.refType == DATASTORE_REF_TYPE && (it.refId as Long) == clusterId &&
					accessPolicy.canSeeTenant(user, it['owner.id'] as Long)
			}
			.collect { Map it ->
				String device = deviceOf(it)
				device == null ? null : new SharedDatastore(it.id as Long, it.name as String, device)
			}
			.findAll { it != null }
			.sort { a, b -> (a.name ?: '') <=> (b.name ?: '') ?: a.id <=> b.id }
	}

	/** Multipath device of a datastore row, from {@code config.blockDevice} */
	static String deviceOf(Map row) {
		Object config = row.config
		Map map = null
		if (config instanceof Map) {
			map = (Map) config
		} else if (config instanceof String && config.trim().startsWith('{')) {
			try {
				Object json = new JsonSlurper().parseText((String) config)
				map = json instanceof Map ? (Map) json : null
			} catch (Exception ignored) {
				map = null
			}
		}
		return Datastores.deviceName(map?.blockDevice?.toString())
	}

	private static List<Map> rows(service, DataQuery query, List<String> properties) {
		query.propertyList = properties
		query.max = MAX_ROWS
		List<Map> result = (service.listOptions(query) ?: []) as List<Map>
		if (result.size() >= MAX_ROWS) {
			log.warn("Storage Path Monitor: query for ${properties} reached ${MAX_ROWS} rows, results may be incomplete")
		}
		return result
	}
}
