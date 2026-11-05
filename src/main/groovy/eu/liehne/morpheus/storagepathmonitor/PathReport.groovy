package eu.liehne.morpheus.storagepathmonitor

import groovy.transform.CompileStatic

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Builds the response of {@code GET /plugin/storage-path-monitor/paths} from the collected snapshot and the
 * datastores the user may see. Only counters and states, never command output (format: CONTRACT.md).
 */
@CompileStatic
class PathReport {

	/**
	 * @param withHostNames host names only for users who may see hosts
	 * @param withHosts {@code false} leaves out the per-host list (counters only, for large clusters)
	 */
	static Map<String, Object> build(Long clusterId, List<SharedDatastore> datastores, Snapshot snapshot,
									 MonitorSettings settings, boolean withHostNames, boolean withHosts = true) {
		List<HostRef> hosts = snapshot.hostsByCluster.get(clusterId) ?: []
		List<Map<String, Object>> entries = datastores.collect { SharedDatastore ds ->
			Set<String> settled = snapshot.settledByCluster?.get(clusterId)
			boolean expectMounted = ds.provisioned && (snapshot.settledByCluster == null || settled?.contains(ds.device))
			Map<String, Object> entry = datastoreEntry(ds, hosts, snapshot.measurements, withHostNames, expectMounted)
			if (!withHosts) {
				entry.remove('hosts')
			}
			entry
		}
		List<PathState> states = entries.collect { PathState.valueOf(((String) it.state).toUpperCase()) }
		List<OrphanMount> orphans = snapshot.orphansByCluster?.get(clusterId) ?: []
		if (orphans) {
			states << PathState.DEGRADED
		}
		return [
			clusterId      : clusterId,
			state          : states ? PathState.worst(states).code : null,
			lastRunAt      : iso(snapshot.lastRunAt),
			intervalSeconds: settings.intervalSeconds,
			datastores     : entries,
			orphanMounts   : orphans.collect { orphanEntry(it, hosts, withHostNames) }
		] as Map<String, Object>
	}

	/** Device and mount point are details of the host: like its name, only for users who may see hosts */
	private static Map<String, Object> orphanEntry(OrphanMount orphan, List<HostRef> hosts, boolean withHostNames) {
		return [
			hostId    : orphan.hostId,
			name      : withHostNames ? hosts.find { it.id == orphan.hostId }?.name : null,
			device    : withHostNames ? orphan.device : null,
			mountPoint: withHostNames ? orphan.mountPoint : null,
			withdrawn : orphan.withdrawn,
			since     : iso(orphan.since)
		] as Map<String, Object>
	}

	private static Map<String, Object> datastoreEntry(SharedDatastore ds, List<HostRef> hosts,
													  Map<Long, HostMeasurement> measurements, boolean withHostNames,
													  boolean expectMounted) {
		Map<String, Integer> counts = PathState.values().collectEntries { [(it.code): 0] } as Map<String, Integer>
		List<PathState> states = []
		int expected = expectedPaths(ds, hosts, measurements)
		List<Map<String, Object>> hostEntries = hosts.collect { HostRef host ->
			HostMeasurement m = measurements.get(host.id)
			PathState state
			Integer active = null
			Integer total = null
			Boolean mounted = null
			Boolean withdrawn = null
			Boolean poolActive = null
			if (m == null || !m.success) {
				state = PathState.UNKNOWN
			} else {
				MultipathStatus.Device device = m.devices.get(ds.device)
				active = device?.active ?: 0
				total = device?.paths ?: 0
				List<Mount> mounts = m.mounts?.findAll { it.device == ds.device }
				mounted = m.mounts == null ? null : !mounts.isEmpty()
				withdrawn = mounted ? withdrawnOf(mounts) : (mounted == null ? null : Boolean.FALSE)
				poolActive = m.pools == null ? null : m.pools.get(ds.pool) == Boolean.TRUE
				state = PathState.worst([stateOf(device, expected), mountState(mounted, withdrawn, expectMounted),
										 poolState(poolActive, expectMounted)])
			}
			states << state
			counts[state.code] = counts[state.code] + 1
			[
				hostId     : host.id,
				name       : withHostNames ? host.name : null,
				state      : state.code,
				activePaths  : active,
				totalPaths   : total,
				expectedPaths: m?.success ? expected : null,
				mounted    : mounted,
				withdrawn  : withdrawn,
				poolActive : poolActive,
				measuredAt : iso(m?.measuredAt),
				error      : m?.error ?: (mounted == null || withdrawn == null ? m?.mountError : null) ?:
					(poolActive == null ? m?.poolError : null)
			] as Map<String, Object>
		}
		return [
			id    : ds.id,
			name  : ds.name,
			state : hosts ? PathState.worst(states).code : PathState.UNKNOWN.code,
			counts: counts,
			hosts : hostEntries
		] as Map<String, Object>
	}

	/**
	 * Paths a host should have for this LUN: the highest path count any measured host of the cluster reports. When
	 * a path is removed on one host (e.g. iSCSI session logged out), multipath forgets it and reports 1/1, which only
	 * this comparison reveals. Assumes all hosts of a cluster are cabled alike.
	 */
	static int expectedPaths(SharedDatastore ds, List<HostRef> hosts, Map<Long, HostMeasurement> measurements) {
		int expected = 0
		hosts.each { HostRef host ->
			HostMeasurement m = measurements.get(host.id)
			MultipathStatus.Device device = m?.success ? m.devices.get(ds.device) : null
			if (device != null && device.paths > expected) {
				expected = device.paths
			}
		}
		return expected
	}

	/** State of the LUN on a host: {@link PathState#of}, and degraded if it has fewer paths than expected */
	static PathState stateOf(MultipathStatus.Device device, int expected) {
		PathState state = PathState.of(device)
		return state == PathState.OK && device.paths < expected ? PathState.DEGRADED : state
	}

	/** Withdrawn if any mount of the file system is; {@code null} if that is not known for all of them */
	private static Boolean withdrawnOf(List<Mount> mounts) {
		if (mounts.any { it.withdrawn == Boolean.TRUE }) {
			return Boolean.TRUE
		}
		return mounts.any { it.withdrawn == null } ? null : Boolean.FALSE
	}

	/**
	 * What the mount adds to the path state: not mounted or withdrawn means the datastore cannot be used on this host,
	 * whatever the paths say. While Morpheus creates or removes the datastore, a missing mount is expected.
	 */
	static PathState mountState(Boolean mounted, Boolean withdrawn, boolean expectMounted) {
		if (mounted == null) {
			return PathState.UNKNOWN
		}
		if (!mounted) {
			return expectMounted ? PathState.DOWN : PathState.OK
		}
		if (withdrawn == null) {
			return PathState.UNKNOWN
		}
		return withdrawn ? PathState.DOWN : PathState.OK
	}

	/** A pool that is inactive or missing: libvirt cannot use the datastore on this host, even with the mount in place */
	static PathState poolState(Boolean poolActive, boolean expectMounted) {
		if (poolActive == null) {
			return PathState.UNKNOWN
		}
		return poolActive || !expectMounted ? PathState.OK : PathState.DOWN
	}

	private static String iso(Instant instant) {
		return instant?.truncatedTo(ChronoUnit.MILLIS)?.toString()
	}
}
