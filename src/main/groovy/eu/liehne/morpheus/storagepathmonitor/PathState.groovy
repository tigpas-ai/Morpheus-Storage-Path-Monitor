package eu.liehne.morpheus.storagepathmonitor

import groovy.transform.CompileStatic

/**
 * Path state of a LUN on one host, as returned by the interface (lower-case names, see CONTRACT.md).
 * The order is the severity used to combine several hosts: the worst state wins.
 */
@CompileStatic
enum PathState {
	/** All paths active */
	OK,
	/** Not measured yet, or the measurement failed */
	UNKNOWN,
	/** Some but not all paths active */
	DEGRADED,
	/** No path active, or the LUN is missing on the host */
	DOWN

	String getCode() {
		return name().toLowerCase()
	}

	/** State of a device on a host; {@code null} means the host does not see the LUN at all */
	static PathState of(MultipathStatus.Device device) {
		if (device == null || device.paths == 0 || device.active == 0) {
			return DOWN
		}
		return device.active < device.paths ? DEGRADED : OK
	}

	static PathState worst(Collection<PathState> states) {
		PathState result = OK
		states?.each { PathState state ->
			if (state != null && state.ordinal() > result.ordinal()) {
				result = state
			}
		}
		return result
	}
}
