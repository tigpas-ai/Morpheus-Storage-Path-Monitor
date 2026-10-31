package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.model.OptionType
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.Immutable

/**
 * Plugin settings (Administration → Integrations → Plugins). Values outside the allowed range are clamped, missing or
 * unreadable values fall back to the defaults.
 */
@CompileStatic
@Immutable
class MonitorSettings {

	static final String INTERVAL = 'intervalSeconds'
	static final String PARALLEL = 'parallelHosts'
	static final String TIMEOUT = 'commandTimeoutSeconds'

	static final int DEFAULT_INTERVAL = 60
	static final int MIN_INTERVAL = 15
	static final int MAX_INTERVAL = 3600
	static final int DEFAULT_PARALLEL = 4
	static final int MAX_PARALLEL = 16
	static final int DEFAULT_TIMEOUT = 30
	static final int MIN_TIMEOUT = 5
	static final int MAX_TIMEOUT = 300

	static final MonitorSettings DEFAULTS = new MonitorSettings(DEFAULT_INTERVAL, DEFAULT_PARALLEL, DEFAULT_TIMEOUT)

	/** Pause between the end of one collection run and the start of the next */
	int intervalSeconds
	/** Hosts queried at the same time */
	int parallelHosts
	/** Upper bound for one host command */
	int commandTimeoutSeconds

	static List<OptionType> optionTypes() {
		[
			number(INTERVAL, 'Interval (seconds)', 0, DEFAULT_INTERVAL,
				"Pause between two collection runs, ${MIN_INTERVAL}–${MAX_INTERVAL} seconds"),
			number(PARALLEL, 'Hosts in parallel', 1, DEFAULT_PARALLEL,
				"Number of hosts queried at the same time, 1–${MAX_PARALLEL}"),
			number(TIMEOUT, 'Command timeout (seconds)', 2, DEFAULT_TIMEOUT,
				"Upper bound for the command on one host, ${MIN_TIMEOUT}–${MAX_TIMEOUT} seconds")
		]
	}

	private static OptionType number(String field, String label, int order, int defaultValue, String help) {
		new OptionType(name: label, code: "storage-path-monitor.${field}".toString(), fieldName: field,
			fieldLabel: label, displayOrder: order, required: false, inputType: OptionType.InputType.NUMBER,
			defaultValue: defaultValue.toString(), helpText: help.toString())
	}

	/** Settings from the JSON Morpheus stores for the plugin */
	static MonitorSettings parse(String settingsJson) {
		Map settings = [:]
		if (settingsJson?.trim()) {
			try {
				Object json = new JsonSlurper().parseText(settingsJson)
				if (json instanceof Map) {
					settings = (Map) json
				}
			} catch (Exception ignored) {
				// defaults
			}
		}
		new MonitorSettings(
			value(settings.get(INTERVAL), DEFAULT_INTERVAL, MIN_INTERVAL, MAX_INTERVAL),
			value(settings.get(PARALLEL), DEFAULT_PARALLEL, 1, MAX_PARALLEL),
			value(settings.get(TIMEOUT), DEFAULT_TIMEOUT, MIN_TIMEOUT, MAX_TIMEOUT))
	}

	private static int value(Object raw, int defaultValue, int min, int max) {
		String text = raw?.toString()?.trim()
		if (!text?.isNumber()) {
			return defaultValue
		}
		BigDecimal number = text.toBigDecimal()
		if (number < min) {
			return min
		}
		return number > max ? max : number.intValue()
	}
}
