package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import spock.lang.Specification

class MultipathStatusTabProviderSpec extends Specification {

	Map<String, String> reader = ['infrastructure-storage': 'read', 'infrastructure-cluster': 'read']
	UserDirectory directory = Mock()
	MultipathStatusTabProvider provider = new MultipathStatusTabProvider(null, null, directory)
	ComputeServerGroup cluster = new ComputeServerGroup(id: 7L)

	User user(Map<String, String> permissions) {
		new User(id: 1L, account: new Account(id: 1L, masterAccount: true), permissions: permissions)
	}

	def 'shown when the directory finds the HVM cluster visible'() {
		given:
		User u = user(reader)

		when:
		boolean shown = provider.show(cluster, u, u.account)

		then:
		1 * directory.clusterVisible(u, 7L, u.account) >> true
		shown
	}

	def 'uses the tenant handed over separately when the user carries none (as Morpheus does for tabs)'() {
		given:
		User u = new User(id: 2L, permissions: reader)
		Account tenant = new Account(id: 1L, masterAccount: true)

		when:
		boolean shown = provider.show(cluster, u, tenant)

		then:
		1 * directory.clusterVisible(u, 7L, tenant) >> true
		shown
	}

	def 'hidden when the cluster is not visible or not an HVM cluster'() {
		given:
		User u = user(reader)
		directory.clusterVisible(u, 7L, _) >> false

		expect:
		!provider.show(cluster, u, u.account)
	}

	def 'hidden without permissions, without asking the directory'() {
		when:
		boolean noStorage = provider.show(cluster, user(['infrastructure-cluster': 'full']), null)
		boolean noCluster = provider.show(cluster, user(['infrastructure-storage': 'full']), null)
		boolean noUser = provider.show(cluster, null, null)
		boolean noGroup = provider.show(null, user(reader), null)

		then:
		0 * directory._
		!noStorage && !noCluster && !noUser && !noGroup
	}

	def 'hidden if the check fails'() {
		given:
		User u = user(reader)
		directory.clusterVisible(u, 7L, _) >> { throw new IllegalStateException('db') }

		expect:
		!provider.show(cluster, u, u.account)
	}

	def 'code and name'() {
		expect:
		provider.code == 'storage-path-monitor-tab'
		provider.name == 'Multipath Status'
	}

	def 'asset version is a stable short hash of the bundled assets'() {
		expect:
		provider.assetVersion() ==~ /[0-9a-f]{12}/
		provider.assetVersion() == provider.assetVersion()
	}
}
