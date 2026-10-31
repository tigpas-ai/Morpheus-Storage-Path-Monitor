package eu.liehne.morpheus.storagepathmonitor

import spock.lang.Specification
import spock.lang.Unroll

class DatastoresSpec extends Specification {

	@Unroll
	def 'device name of #blockDevice is #expected'() {
		expect:
		Datastores.deviceName(blockDevice) == expected

		where:
		blockDevice                                      || expected
		'/dev/mapper/36e843b6c334fbead0243d49f4dbbe2d4'  || '36e843b6c334fbead0243d49f4dbbe2d4'
		'/dev/mapper/mpatha'                             || 'mpatha'
		'/dev/sdb'                                       || null
		'/dev/mapper/'                                   || null
		'/dev/mapper/../sda'                             || null
		'/dev/mapper/a b'                                || null
		'/dev/mapper/a;reboot'                           || null
		null                                             || null
	}

	@Unroll
	def 'device of a datastore row with config #config'() {
		expect:
		MorpheusQueries.deviceOf([config: config]) == expected

		where:
		config                                           || expected
		'{"blockDevice":"/dev/mapper/3abc"}'             || '3abc'
		[blockDevice: '/dev/mapper/3abc']                || '3abc'
		'{"path":"/var/lib"}'                            || null
		'not json'                                       || null
		'{broken'                                        || null
		null                                             || null
	}
}
