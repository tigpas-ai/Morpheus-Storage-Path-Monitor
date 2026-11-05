package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/** Inventory used by the background collection (system scope, no user) */
interface ClusterInventory {
	/**
	 * Cluster id → hosts of all HVM clusters. Also clusters without a shared-LUN datastore: when the last datastore is
	 * removed, its mount may stay behind on a host.
	 */
	Map<Long, List<HostRef>> monitoredClusters()

	/** Cluster id → all its datastores, regardless of any user */
	Map<Long, KnownStorage> knownStorage(Collection<Long> clusterIds)

	/** Cluster id → multipath devices of its datastores that Morpheus is not creating or removing right now */
	Map<Long, Set<String>> settledDevices(Collection<Long> clusterIds)
}

/** Objects as the signed-in user may see them */
interface UserDirectory {
	/** {@code true} if the HVM cluster exists and the user may see it */
	boolean clusterVisible(User user, Long clusterId)

	/** Same, with the user's tenant given separately (tab providers get the user without its tenant) */
	boolean clusterVisible(User user, Long clusterId, Account tenant)

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
	static final List<String> DATASTORE_PROPS = ['id', 'name', 'refType', 'refId', 'config', 'owner.id', 'status', 'externalPath', 'externalId']
	/**
	 * Status of a datastore while Morpheus works on it. Not {@code warning}: Morpheus sets that when a host has a problem
	 * with the datastore ("Check host(s) … status"), which is what the plugin is there to show.
	 */
	static final List<String> STATUS_IN_PROGRESS = ['provisioning', 'deprovisioning', 'removing']

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
		Map<Long, List<HostRef>> result = clusterIds.collectEntries { [(it): []] }
		rows(morpheus.services.computeServer, new DataQuery().withFilter('serverGroup.id', 'in', clusterIds), HOST_PROPS)
			.findAll { it['parentServer.id'] == null && (it['serverGroup.id'] as Long) in clusterIds }
			.each { result[it['serverGroup.id'] as Long] << new HostRef(it.id as Long, it.name as String) }
		return result.collectEntries { k, v -> [(k): v.sort { it.id }.asImmutable()] }
	}

	@Override
	Map<Long, KnownStorage> knownStorage(Collection<Long> clusterIds) {
		if (!clusterIds) {
			return [:]
		}
		Map<Long, Set<String>> devices = clusterIds.collectEntries { [(it): new HashSet<String>()] }
		Map<Long, Set<String>> mountPoints = clusterIds.collectEntries { [(it): new HashSet<String>()] }
		DataQuery query = new DataQuery()
			.withFilter('refType', DATASTORE_REF_TYPE)
			.withFilter('refId', 'in', clusterIds as List)
		rows(morpheus.services.cloud.datastore, query, DATASTORE_PROPS)
			.findAll { it.refType == DATASTORE_REF_TYPE && devices.containsKey(it.refId as Long) }
			.each { Map row ->
				Long clusterId = row.refId as Long
				String blockDevice = blockDeviceOf(row)
				if (blockDevice) {
					devices[clusterId] << Datastores.deviceKey(blockDevice)
				}
				if (row.externalPath) {
					mountPoints[clusterId] << row.externalPath.toString()
				}
			}
		return clusterIds.collectEntries { Long id ->
			[(id): new KnownStorage(devices[id].asImmutable(), mountPoints[id].asImmutable())]
		}
	}

	@Override
	Map<Long, Set<String>> settledDevices(Collection<Long> clusterIds) {
		if (!clusterIds) {
			return [:]
		}
		Map<Long, Set<String>> settled = clusterIds.collectEntries { [(it): new HashSet<String>()] }
		DataQuery query = new DataQuery()
			.withFilter('refType', DATASTORE_REF_TYPE)
			.withFilter('refId', 'in', clusterIds as List)
		rows(morpheus.services.cloud.datastore, query, DATASTORE_PROPS)
			.findAll { it.refType == DATASTORE_REF_TYPE && settled.containsKey(it.refId as Long) && !(it.status in STATUS_IN_PROGRESS) }
			.each { Map row ->
				String device = deviceOf(row)
				if (device != null) {
					settled[row.refId as Long] << device
				}
			}
		return settled
	}

	@Override
	boolean clusterVisible(User user, Long clusterId) {
		return clusterVisible(user, clusterId, user?.account)
	}

	@Override
	boolean clusterVisible(User user, Long clusterId, Account tenant) {
		if (user == null || clusterId == null || tenant?.id == null) {
			return false
		}
		DataQuery query = new DataQuery(user).withFilter('id', clusterId).withFilter('type.code', CLUSTER_TYPE_CODE)
		if (query.account == null) {
			// user without tenant (tab providers): scope the query to the tenant handed over separately
			query.account = tenant
		}
		Map cluster = rows(morpheus.services.cluster, query, CLUSTER_PROPS).find { (it.id as Long) == clusterId }
		return cluster != null && accessPolicy.canSeeTenant(tenant, cluster['owner.id'] as Long)
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
				device == null ? null : new SharedDatastore(it.id as Long, it.name as String, device,
					!(it.status in STATUS_IN_PROGRESS), it.externalId as String)
			}
			.findAll { it != null }
			.sort { a, b -> (a.name ?: '') <=> (b.name ?: '') ?: a.id <=> b.id }
	}

	/** Multipath device of a datastore row, from {@code config.blockDevice} */
	static String deviceOf(Map row) {
		return Datastores.deviceName(blockDeviceOf(row))
	}

	/** Setting {@code blockDevice} of a datastore row, in whatever form it has */
	private static String blockDeviceOf(Map row) {
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
		return map?.blockDevice?.toString()
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
