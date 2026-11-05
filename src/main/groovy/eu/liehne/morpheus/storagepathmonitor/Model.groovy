package eu.liehne.morpheus.storagepathmonitor

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.Immutable
import groovy.transform.ToString

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
@EqualsAndHashCode
@ToString
class SharedDatastore {
	final Long id
	final String name
	/** Multipath map name, from {@code /dev/mapper/<name>} */
	final String device
	/** {@code false} while Morpheus is creating or removing it: a missing mount is expected then */
	final boolean provisioned

	SharedDatastore(Long id, String name, String device, boolean provisioned = true) {
		this.id = id
		this.name = name
		this.device = device
		this.provisioned = provisioned
	}
}

/** A GFS2 file system mounted on a host */
@CompileStatic
@Immutable
class Mount {
	/** {@link Datastores#deviceKey} of the mount source */
	String device
	String mountPoint
	/** {@code major:minor} */
	String deviceNumber
	/** {@code null} if not known */
	Boolean withdrawn
}

/** The datastores of a cluster as Morpheus knows them; a mount matching neither set belongs to no datastore */
@CompileStatic
@Immutable
class KnownStorage {
	Set<String> devices
	Set<String> mountPoints

	boolean knows(Mount mount) {
		return mount.device in devices || mount.mountPoint in mountPoints
	}
}

/** A GFS2 mount on a host of the cluster without a datastore in Morpheus, in two runs in a row */
@CompileStatic
@Immutable(knownImmutableClasses = [Instant])
class OrphanMount {
	Long hostId
	String device
	String mountPoint
	Boolean withdrawn
	/** First run that saw it */
	Instant since
}

/** Identity of a mount without a datastore across runs */
@CompileStatic
@Immutable
class OrphanKey {
	Long hostId
	String device
	String mountPoint
}

/**
 * Result of the commands on one host: either the devices (success) or an error message that is safe to show.
 * {@code measuredAt} is the time the commands finished or failed. The mounts are a separate check: {@code mounts} is
 * {@code null} and {@code mountError} set if it failed, while the devices stay valid.
 */
@CompileStatic
@Immutable(knownImmutableClasses = [Instant])
class HostMeasurement {
	Long hostId
	Instant measuredAt
	Map<String, MultipathStatus.Device> devices
	String error
	List<Mount> mounts
	String mountError

	boolean isSuccess() {
		return error == null && devices != null
	}

	static HostMeasurement success(Long hostId, Instant at, Map<String, MultipathStatus.Device> devices,
								   List<Mount> mounts = null, String mountError = null) {
		new HostMeasurement(hostId, at, devices, null, mounts, mountError)
	}

	static HostMeasurement failure(Long hostId, Instant at, String error) {
		new HostMeasurement(hostId, at, null, error, null, null)
	}
}

/** What the collector currently knows: hosts per monitored cluster and the latest measurement per host. */
@CompileStatic
class Snapshot {
	final Map<Long, List<HostRef>> hostsByCluster
	final Map<Long, HostMeasurement> measurements
	/** Start of the last completed collection run, {@code null} before the first one */
	final Instant lastRunAt
	final Map<Long, List<OrphanMount>> orphansByCluster
	/**
	 * Devices of datastores that were already finished when the last completed run started, so every measurement that can
	 * be current was taken after that: only for these a missing mount means something. {@code null}: all devices.
	 */
	final Map<Long, Set<String>> settledByCluster

	static final Snapshot EMPTY = new Snapshot([:], [:], null, [:], [:])

	Snapshot(Map<Long, List<HostRef>> hostsByCluster, Map<Long, HostMeasurement> measurements, Instant lastRunAt,
			 Map<Long, List<OrphanMount>> orphansByCluster = [:], Map<Long, Set<String>> settledByCluster = null) {
		this.hostsByCluster = hostsByCluster.asImmutable()
		this.measurements = measurements.asImmutable()
		this.lastRunAt = lastRunAt
		this.orphansByCluster = orphansByCluster.asImmutable()
		this.settledByCluster = settledByCluster?.asImmutable()
	}
}

/** Datastore helpers */
@CompileStatic
class Datastores {

	static final String MAPPER_PREFIX = '/dev/mapper/'

	/** Common form of a mount source and a datastore block device: the map name for multipath, otherwise the path */
	static String deviceKey(String path) {
		// findmnt shows a bind mount as device[/subdirectory]
		int subdirectory = path.indexOf('[')
		String device = subdirectory > 0 ? path.substring(0, subdirectory) : path
		return device.startsWith(MAPPER_PREFIX) ? device.substring(MAPPER_PREFIX.length()) : device
	}

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
