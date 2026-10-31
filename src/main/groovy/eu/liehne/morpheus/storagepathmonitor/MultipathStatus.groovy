package eu.liehne.morpheus.storagepathmonitor

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.Immutable

/**
 * The fixed host command and the parser for its output.
 *
 * {@code hvmcli storage multipath status --json} lists every multipath device of the host with the number of paths and
 * of active paths, in one call for all LUNs. The command only reads; it is a constant and never contains user input.
 */
@CompileStatic
class MultipathStatus {

	/** The only command the plugin runs on hosts */
	static final String COMMAND = 'hvmcli storage multipath status --json'

	/** Upper bound for the command output; a few hundred bytes per device are expected */
	static final int MAX_OUTPUT_CHARS = 8 * 1024 * 1024

	/** Path counters of one multipath device on one host */
	@Immutable
	static class Device {
		int paths
		int active
	}

	/** Output that cannot be used; the message is safe to show (it never contains the output itself). */
	static class ParseException extends RuntimeException {
		ParseException(String message) {
			super(message)
		}
	}

	/**
	 * Device name (the multipath map name, by default the WWID) → path counters.
	 * @throws ParseException if the output is not the expected JSON or the command reported an error
	 */
	static Map<String, Device> parse(String output) {
		if (output == null || output.trim().isEmpty()) {
			throw new ParseException('empty command output')
		}
		if (output.length() > MAX_OUTPUT_CHARS) {
			throw new ParseException('command output too large')
		}
		Object json
		try {
			json = new JsonSlurper().parseText(output)
		} catch (Exception ignored) {
			throw new ParseException('command output is not valid JSON')
		}
		if (!(json instanceof Map)) {
			throw new ParseException('unexpected command output')
		}
		Map root = (Map) json
		Object rawErrorCode = root.get('errorCode')
		Integer errorCode = toInt(rawErrorCode)
		if (rawErrorCode != null && errorCode == null) {
			throw new ParseException('unexpected command output')
		}
		if (errorCode != null && errorCode != 0) {
			throw new ParseException("command reported error code ${errorCode}".toString())
		}
		Object data = root.get('data')
		if (data == null) {
			return [:]
		}
		if (!(data instanceof List)) {
			throw new ParseException('unexpected command output')
		}
		Map<String, Device> devices = [:]
		for (Object entry : (List) data) {
			if (!(entry instanceof Map)) {
				throw new ParseException('unexpected command output')
			}
			Map map = (Map) entry
			String name = map.get('device')?.toString()
			Integer paths = toInt(map.get('paths'))
			Integer active = toInt(map.get('active'))
			if (!name || paths == null || active == null || paths < 0 || active < 0) {
				throw new ParseException('unexpected command output')
			}
			devices.put(name, new Device(paths: paths, active: Math.min(active, paths)))
		}
		return devices
	}

	private static Integer toInt(Object value) {
		if (value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
			long number = ((Number) value).longValue()
			return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE ? (int) number : null
		}
		String text = value instanceof String ? (String) value : null
		return text?.isInteger() ? text.toInteger() : null
	}
}
