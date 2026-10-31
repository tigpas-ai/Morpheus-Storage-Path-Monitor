package eu.liehne.morpheus.storagepathmonitor

import spock.lang.Specification
import spock.lang.Unroll

class MultipathStatusSpec extends Specification {

	static final String SAMPLE = '''{
  "errorCode" : 0,
  "messages" : "",
  "data" : [ {
    "device" : "36e843b61e163259d0ca4d491dda757de",
    "transport" : "iscsi", "vendor" : "VENDOR", "size" : "2.0T",
    "paths" : 2, "active" : 2, "failed" : 0, "state" : "active"
  }, {
    "device" : "36e843b6629c8bd8deb4dd4643db2bfd1",
    "transport" : "iscsi", "vendor" : "VENDOR", "size" : "2.0T",
    "paths" : 2, "active" : 1, "failed" : 1, "state" : "active"
  } ]
}'''

	def 'parses devices with path counters'() {
		when:
		Map<String, MultipathStatus.Device> devices = MultipathStatus.parse(SAMPLE)

		then:
		devices.size() == 2
		devices['36e843b61e163259d0ca4d491dda757de'] == new MultipathStatus.Device(2, 2)
		devices['36e843b6629c8bd8deb4dd4643db2bfd1'] == new MultipathStatus.Device(2, 1)
	}

	def 'no data means no devices'() {
		expect:
		MultipathStatus.parse('{"errorCode":0,"data":[]}').isEmpty()
		MultipathStatus.parse('{"errorCode":0}').isEmpty()
	}

	def 'active is capped at the number of paths'() {
		expect:
		MultipathStatus.parse('{"data":[{"device":"d","paths":2,"active":3}]}')['d'] == new MultipathStatus.Device(2, 2)
	}

	@Unroll
	def 'rejects unusable output: #why'() {
		when:
		MultipathStatus.parse(output)

		then:
		MultipathStatus.ParseException e = thrown()
		e.message == message
		!e.message.contains('secret')

		where:
		why               | output                                               | message
		'null'            | null                                                 | 'empty command output'
		'blank'           | '  \n'                                               | 'empty command output'
		'not json'        | 'sudo: secret not found'                             | 'command output is not valid JSON'
		'array root'      | '[1]'                                                | 'unexpected command output'
		'error code'      | '{"errorCode":2,"messages":"secret","data":[]}'      | 'command reported error code 2'
		'error code text' | '{"errorCode":"secret","data":[]}'                   | 'unexpected command output'
		'error code map'  | '{"errorCode":{"a":"secret"},"data":[]}'             | 'unexpected command output'
		'paths as float'  | '{"data":[{"device":"d","paths":2.5,"active":1}]}'  | 'unexpected command output'
		'data not list'   | '{"data":"secret"}'                                  | 'unexpected command output'
		'missing device'  | '{"data":[{"paths":2,"active":2}]}'                  | 'unexpected command output'
		'missing counter' | '{"data":[{"device":"d","paths":2}]}'                | 'unexpected command output'
		'negative'        | '{"data":[{"device":"d","paths":-1,"active":0}]}'    | 'unexpected command output'
	}

	def 'rejects oversized output'() {
		when:
		MultipathStatus.parse('{' + ' ' * MultipathStatus.MAX_OUTPUT_CHARS + '}')

		then:
		MultipathStatus.ParseException e = thrown()
		e.message == 'command output too large'
	}

	def 'the command is a constant without user input'() {
		expect:
		MultipathStatus.COMMAND == 'hvmcli storage multipath status --json'
	}
}
