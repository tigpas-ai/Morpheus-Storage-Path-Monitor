package eu.liehne.morpheus.storagepathmonitor

import groovy.transform.CompileStatic
import groovy.transform.Immutable

import java.time.Instant

/** A host of a monitored cluster */
@CompileStatic
@Immutable
class HostRef {
	Long id
	String name
}

/** A shared-LUN datastore of a cluster with the multipath device it lives on */
@CompileStatic
@Immutable
class SharedDatastore {
	Long id
	String name
	/** Multipath map name, from {@code /dev/mapper/<name>} */
	String device
}

/**
 * Result of one host command: either the devices (success) or an error message that is safe to show.
 * {@code measuredAt} is the time the command finished or failed.
 */
@CompileStatic
@Immutable(knownImmutableClasses = [Instant])
class HostMeasurement {
	Long hostId
	Instant measuredAt
	Map<String, MultipathStatus.Device> devices
	String error

	boolean isSuccess() {
		return error == null && devices != null
	}

	static HostMeasurement success(Long hostId, Instant at, Map<String, MultipathStatus.Device> devices) {
		new HostMeasurement(hostId, at, devices, null)
	}

	static HostMeasurement failure(Long hostId, Instant at, String error) {
		new HostMeasurement(hostId, at, null, error)
	}
}

/** What the collector currently knows: hosts per monitored cluster and the latest measurement per host. */
@CompileStatic
@Immutable(knownImmutableClasses = [Instant])
class Snapshot {
	Map<Long, List<HostRef>> hostsByCluster
	Map<Long, HostMeasurement> measurements
	/** Start of the last completed collection run, {@code null} before the first one */
	Instant lastRunAt

	static final Snapshot EMPTY = new Snapshot([:], [:], null)
}

/** Datastore helpers */
@CompileStatic
class Datastores {

	static final String MAPPER_PREFIX = '/dev/mapper/'

	/**
	 * Multipath map name from the datastore setting {@code blockDevice} ({@code /dev/mapper/<name>}), or {@code null}
	 * if the datastore does not live on a multipath device.
	 */
	static String deviceName(String blockDevice) {
		if (!blockDevice?.startsWith(MAPPER_PREFIX)) {
			return null
		}
		String name = blockDevice.substring(MAPPER_PREFIX.length())
		return name ==~ /[A-Za-z0-9][A-Za-z0-9._:-]{0,254}/ ? name : null
	}
}
