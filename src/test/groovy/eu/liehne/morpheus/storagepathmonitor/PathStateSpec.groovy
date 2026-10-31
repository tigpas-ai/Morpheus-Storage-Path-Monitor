package eu.liehne.morpheus.storagepathmonitor

import spock.lang.Specification
import spock.lang.Unroll

class PathStateSpec extends Specification {

	@Unroll
	def 'device with #active of #paths active paths is #expected'() {
		expect:
		PathState.of(new MultipathStatus.Device(paths, active)) == expected

		where:
		paths | active || expected
		2     | 2      || PathState.OK
		16    | 16     || PathState.OK
		2     | 1      || PathState.DEGRADED
		16    | 15     || PathState.DEGRADED
		2     | 0      || PathState.DOWN
		0     | 0      || PathState.DOWN
	}

	def 'missing LUN is down'() {
		expect:
		PathState.of(null) == PathState.DOWN
	}

	def 'worst state wins: down > degraded > unknown > ok'() {
		expect:
		PathState.worst([]) == PathState.OK
		PathState.worst([PathState.OK, PathState.UNKNOWN]) == PathState.UNKNOWN
		PathState.worst([PathState.UNKNOWN, PathState.DEGRADED, PathState.OK]) == PathState.DEGRADED
		PathState.worst([PathState.DEGRADED, PathState.DOWN]) == PathState.DOWN
	}

	def 'codes are lower case'() {
		expect:
		PathState.values()*.code == ['ok', 'unknown', 'degraded', 'down']
	}
}
