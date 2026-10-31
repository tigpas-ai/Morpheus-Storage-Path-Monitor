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
			Map<String, Object> entry = datastoreEntry(ds, hosts, snapshot.measurements, withHostNames)
			if (!withHosts) {
				entry.remove('hosts')
			}
			entry
		}
		List<PathState> states = entries.collect { PathState.valueOf(((String) it.state).toUpperCase()) }
		return [
			clusterId      : clusterId,
			state          : entries ? PathState.worst(states).code : null,
			lastRunAt      : iso(snapshot.lastRunAt),
			intervalSeconds: settings.intervalSeconds,
			datastores     : entries
		] as Map<String, Object>
	}

	private static Map<String, Object> datastoreEntry(SharedDatastore ds, List<HostRef> hosts,
													  Map<Long, HostMeasurement> measurements, boolean withHostNames) {
		Map<String, Integer> counts = PathState.values().collectEntries { [(it.code): 0] } as Map<String, Integer>
		List<PathState> states = []
		List<Map<String, Object>> hostEntries = hosts.collect { HostRef host ->
			HostMeasurement m = measurements.get(host.id)
			PathState state
			Integer active = null
			Integer total = null
			if (m == null || !m.success) {
				state = PathState.UNKNOWN
			} else {
				MultipathStatus.Device device = m.devices.get(ds.device)
				state = PathState.of(device)
				active = device?.active ?: 0
				total = device?.paths ?: 0
			}
			states << state
			counts[state.code] = counts[state.code] + 1
			[
				hostId     : host.id,
				name       : withHostNames ? host.name : null,
				state      : state.code,
				activePaths: active,
				totalPaths : total,
				measuredAt : iso(m?.measuredAt),
				error      : m?.error
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

	private static String iso(Instant instant) {
		return instant?.truncatedTo(ChronoUnit.MILLIS)?.toString()
	}
}
