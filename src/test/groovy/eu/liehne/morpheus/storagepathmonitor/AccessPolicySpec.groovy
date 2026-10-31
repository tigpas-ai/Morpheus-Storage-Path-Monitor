package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import spock.lang.Specification
import spock.lang.Unroll

class AccessPolicySpec extends Specification {

	AccessPolicy policy = new AccessPolicy()

	@Unroll
	def 'paths need storage and cluster read: #perms → #expected'() {
		expect:
		policy.canViewPaths(new User(permissions: perms)) == expected

		where:
		perms                                                                       || expected
		['infrastructure-storage': 'read', 'infrastructure-cluster': 'read']       || true
		['infrastructure-storage': 'full', 'infrastructure-cluster': 'full']       || true
		['infrastructure-storage': 'read']                                          || false
		['infrastructure-cluster': 'read']                                          || false
		['infrastructure-storage': 'none', 'infrastructure-cluster': 'read']       || false
		['infrastructure-storage': 'user', 'infrastructure-cluster': 'read']       || false
		[:]                                                                         || false
	}

	def 'host names need compute read'() {
		expect:
		policy.canViewHosts(new User(permissions: ['admin-servers': 'read']))
		!policy.canViewHosts(new User(permissions: ['admin-servers': 'none']))
		!policy.canViewHosts(null)
	}

	@Unroll
	def 'tenant check: user tenant #accountId (master #master), owner #owner → #expected'() {
		given:
		User user = accountId == null ? new User() : new User(account: new Account(id: accountId, masterAccount: master))

		expect:
		policy.canSeeTenant(user, owner) == expected

		where:
		accountId | master | owner || expected
		1L        | true   | 5L    || true
		1L        | true   | null  || true
		5L        | false  | 5L    || true
		5L        | false  | 6L    || false
		5L        | false  | null  || false
		null      | false  | 5L    || false
	}

	def 'no user, no access'() {
		expect:
		!policy.canSeeTenant(null, 1L)
		!policy.canViewPaths(null)
	}
}
