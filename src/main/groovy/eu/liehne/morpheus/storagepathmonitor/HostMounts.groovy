package eu.liehne.morpheus.storagepathmonitor

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

/**
 * The fixed host commands for the GFS2 mounts of a host and the parsers for their output. Both commands only read and
 * neither touches the file systems themselves (kernel tables only), so they also answer on a hanging file system.
 */
@CompileStatic
class HostMounts {

	/**
	 * All mounts of the host; the GFS2 ones are selected by the parser. Without a type filter, because findmnt ends with
	 * exit code 1 if nothing matches, which could not be told apart from a failure.
	 */
	static final String MOUNT_COMMAND = 'findmnt --list --json --output SOURCE,TARGET,FSTYPE,MAJ:MIN'

	/**
	 * Device number and withdraw flag of every mounted GFS2 file system. {@code lock_module/withdraw} is write-only and
	 * would make grep fail. Only run if the host has GFS2 mounts: the directory may not exist otherwise.
	 */
	static final String WITHDRAW_COMMAND = 'grep -r --include=id --include=withdraw --exclude-dir=lock_module . /sys/fs/gfs2'

	/** The libvirt storage pools of the host with their state; part of every HVM host */
	static final String POOL_COMMAND = 'hvmcli storage list --json'

	static final String POOL_ACTIVE = 'active'

	static final String FILESYSTEM_TYPE = 'gfs2'

	private static final String SYSFS_LINE = '^/sys/fs/gfs2/[^/]+/(id|withdraw):(.*)$'

	/**
	 * GFS2 mounts from the output of {@link #MOUNT_COMMAND}, without the withdraw state.
	 * @throws MultipathStatus.ParseException if the output is not the expected JSON
	 */
	static List<Mount> parseMounts(String output) {
		if (output == null || output.trim().isEmpty()) {
			throw new MultipathStatus.ParseException('empty command output')
		}
		if (output.length() > MultipathStatus.MAX_OUTPUT_CHARS) {
			throw new MultipathStatus.ParseException('command output too large')
		}
		Object json
		try {
			json = new JsonSlurper().parseText(output)
		} catch (Exception ignored) {
			throw new MultipathStatus.ParseException('command output is not valid JSON')
		}
		Object filesystems = json instanceof Map ? ((Map) json).get('filesystems') : null
		if (!(filesystems instanceof List)) {
			throw new MultipathStatus.ParseException('unexpected command output')
		}
		List<Mount> mounts = []
		for (Object entry : (List) filesystems) {
			if (!(entry instanceof Map)) {
				throw new MultipathStatus.ParseException('unexpected command output')
			}
			Map map = (Map) entry
			if (map.get('fstype') != FILESYSTEM_TYPE) {
				continue
			}
			String source = map.get('source')?.toString()
			String target = map.get('target')?.toString()
			if (!source || !target) {
				throw new MultipathStatus.ParseException('unexpected command output')
			}
			mounts << new Mount(Datastores.deviceKey(source), target, map.get('maj:min')?.toString(), null)
		}
		return mounts
	}

	/**
	 * Device number ({@code major:minor}) → withdrawn, from the output of {@link #WITHDRAW_COMMAND}.
	 * @throws MultipathStatus.ParseException if the output has an unexpected form
	 */
	static Map<String, Boolean> parseWithdrawn(String output) {
		if (output == null || output.trim().isEmpty()) {
			throw new MultipathStatus.ParseException('empty command output')
		}
		if (output.length() > MultipathStatus.MAX_OUTPUT_CHARS) {
			throw new MultipathStatus.ParseException('command output too large')
		}
		Map<String, String> ids = [:]
		Map<String, Boolean> flags = [:]
		for (String raw : output.readLines()) {
			String line = raw.trim()
			if (line.isEmpty()) {
				continue
			}
			if (!line.matches(SYSFS_LINE)) {
				throw new MultipathStatus.ParseException('unexpected command output')
			}
			int separator = line.indexOf(':', line.lastIndexOf('/'))
			String path = line.substring(0, separator)
			String file = path.substring(path.lastIndexOf('/') + 1)
			String directory = path.substring(0, path.lastIndexOf('/'))
			String value = line.substring(separator + 1).trim()
			if (file == 'id') {
				ids.put(directory, value)
			} else if (value == '0' || value == '1') {
				flags.put(directory, value == '1')
			} else {
				throw new MultipathStatus.ParseException('unexpected command output')
			}
		}
		Map<String, Boolean> result = [:]
		flags.each { String directory, Boolean withdrawn ->
			String id = ids.get(directory)
			if (id != null) {
				result.put(id, withdrawn)
			}
		}
		return result
	}

	/**
	 * Pool name → active, from the output of {@link #POOL_COMMAND}.
	 * @throws MultipathStatus.ParseException if the output is not the expected JSON or the command reported an error
	 */
	static Map<String, Boolean> parsePools(String output) {
		if (output == null || output.trim().isEmpty()) {
			throw new MultipathStatus.ParseException('empty command output')
		}
		if (output.length() > MultipathStatus.MAX_OUTPUT_CHARS) {
			throw new MultipathStatus.ParseException('command output too large')
		}
		Object json
		try {
			json = new JsonSlurper().parseText(output)
		} catch (Exception ignored) {
			throw new MultipathStatus.ParseException('command output is not valid JSON')
		}
		if (!(json instanceof Map)) {
			throw new MultipathStatus.ParseException('unexpected command output')
		}
		Map root = (Map) json
		Object errorCode = root.get('errorCode')
		if (errorCode != null && errorCode != 0) {
			throw new MultipathStatus.ParseException('command reported an error')
		}
		Object data = root.get('data')
		if (!(data instanceof List)) {
			throw new MultipathStatus.ParseException('unexpected command output')
		}
		Map<String, Boolean> pools = [:]
		for (Object entry : (List) data) {
			String name = entry instanceof Map ? ((Map) entry).get('name')?.toString() : null
			String state = entry instanceof Map ? ((Map) entry).get('state')?.toString() : null
			if (!name || !state) {
				throw new MultipathStatus.ParseException('unexpected command output')
			}
			pools.put(name, state == POOL_ACTIVE)
		}
		return pools
	}

	/** The mounts with their withdraw state; a mount the flags do not cover stays {@code null} (unknown) */
	static List<Mount> withWithdrawn(List<Mount> mounts, Map<String, Boolean> withdrawn) {
		return mounts.collect { Mount m -> new Mount(m.device, m.mountPoint, m.deviceNumber, withdrawn.get(m.deviceNumber)) }
	}
}
