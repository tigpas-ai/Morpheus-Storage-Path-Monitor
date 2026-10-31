package eu.liehne.morpheus.storagepathmonitor

import spock.lang.Specification
import spock.lang.Unroll

class MonitorSettingsSpec extends Specification {

	@Unroll
	def 'parses #json'() {
		expect:
		MonitorSettings.parse(json) == new MonitorSettings(interval, parallel, timeout)

		where:
		json                                                                    || interval | parallel | timeout
		null                                                                    || 60       | 4        | 30
		''                                                                      || 60       | 4        | 30
		'not json'                                                              || 60       | 4        | 30
		'[]'                                                                    || 60       | 4        | 30
		'{"intervalSeconds":"120","parallelHosts":"8","commandTimeoutSeconds":"10"}' || 120 | 8        | 10
		'{"intervalSeconds":90,"parallelHosts":2}'                              || 90       | 2        | 30
		'{"intervalSeconds":"1","parallelHosts":"0","commandTimeoutSeconds":"1"}'    || 15  | 1        | 5
		'{"intervalSeconds":"99999","parallelHosts":"99","commandTimeoutSeconds":"9999"}' || 3600 | 16 | 300
		'{"intervalSeconds":"abc","parallelHosts":"","commandTimeoutSeconds":null}' || 60 | 4        | 30
		'{"intervalSeconds":"75.9"}'                                            || 75       | 4        | 30
	}

	def 'option types use the setting field names'() {
		expect:
		MonitorSettings.optionTypes()*.fieldName == ['intervalSeconds', 'parallelHosts', 'commandTimeoutSeconds']
		MonitorSettings.optionTypes()*.defaultValue == ['60', '4', '30']
	}
}
