package eu.liehne.morpheus.storagepathmonitor

import spock.lang.Specification
import spock.lang.Unroll

class HostMountsSpec extends Specification {

	def 'selects GFS2 mounts and normalizes the source'() {
		given:
		String output = '''{"filesystems": [
			{"source": "/dev/sda2", "target": "/", "fstype": "ext4", "maj:min": "8:2"},
			{"source": "/dev/mapper/3abc", "target": "/mnt/one", "fstype": "gfs2", "maj:min": "252:0"},
			{"source": "/dev/mapper/3abc[/sub]", "target": "/srv/bind", "fstype": "gfs2", "maj:min": "252:0"},
			{"source": "/dev/sdx", "target": "/mnt/two", "fstype": "gfs2", "maj:min": "8:16"}
		]}'''

		expect:
		HostMounts.parseMounts(output) == [
			new Mount('3abc', '/mnt/one', '252:0', null),
			new Mount('3abc', '/srv/bind', '252:0', null),
			new Mount('/dev/sdx', '/mnt/two', '8:16', null)]
	}

	@Unroll
	def 'mount output #output is refused'() {
		when:
		HostMounts.parseMounts(output)

		then:
		MultipathStatus.ParseException e = thrown()
		e.message == message

		where:
		output                                       || message
		null                                         || 'empty command output'
		' '                                          || 'empty command output'
		'garbage'                                    || 'command output is not valid JSON'
		'[]'                                         || 'unexpected command output'
		'{"filesystems":"x"}'                        || 'unexpected command output'
		'{"filesystems":["x"]}'                      || 'unexpected command output'
		'{"filesystems":[{"fstype":"gfs2"}]}'        || 'unexpected command output'
	}

	def 'withdraw flags per device number, also with colons in the lock table name'() {
		given:
		String output = '''/sys/fs/gfs2/cluster:one/id:252:0
/sys/fs/gfs2/cluster:one/withdraw:0
/sys/fs/gfs2/cluster:two/withdraw:1
/sys/fs/gfs2/cluster:two/id:252:5
/sys/fs/gfs2/cluster:three/withdraw:0
'''

		expect:
		HostMounts.parseWithdrawn(output) == ['252:0': false, '252:5': true]
	}

	@Unroll
	def 'withdraw output #output is refused'() {
		when:
		HostMounts.parseWithdrawn(output)

		then:
		thrown(MultipathStatus.ParseException)

		where:
		output << [null, '', 'grep: no such file', '/sys/fs/gfs2/a/withdraw:maybe', '/sys/fs/gfs2/a/other:1']
	}

	def 'pool states from the host command'() {
		expect:
		HostMounts.parsePools('''{"errorCode":0,"messages":"","data":[
			{"name":"a","type":"GFS2","state":"active","autostart":"yes"},
			{"name":"b","type":"GFS2","state":"inactive","autostart":"yes"}]}''') == [a: true, b: false]
	}

	@Unroll
	def 'pool output #output is refused'() {
		when:
		HostMounts.parsePools(output)

		then:
		MultipathStatus.ParseException e = thrown()
		e.message == message

		where:
		output                            || message
		null                              || 'empty command output'
		'garbage'                         || 'command output is not valid JSON'
		'[]'                              || 'unexpected command output'
		'{"errorCode":1,"data":[]}'       || 'command reported an error'
		'{"errorCode":0}'                 || 'unexpected command output'
		'{"data":[{"name":"a"}]}'         || 'unexpected command output'
		'{"data":["a"]}'                  || 'unexpected command output'
	}

	def 'a mount the flags do not cover stays unknown'() {
		expect:
		HostMounts.withWithdrawn([new Mount('a', '/a', '1:1', null), new Mount('b', '/b', '1:2', null)], ['1:1': true]) ==
			[new Mount('a', '/a', '1:1', true), new Mount('b', '/b', '1:2', null)]
	}
}
