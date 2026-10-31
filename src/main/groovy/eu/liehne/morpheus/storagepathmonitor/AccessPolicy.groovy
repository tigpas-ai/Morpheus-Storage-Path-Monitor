package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.model.User

/**
 * Access checks for the signed-in user, based on the Morpheus feature permissions ({@code user.permissions}:
 * code → none/read/full) and the tenant. All checks fail closed: missing data means no access.
 */
class AccessPolicy {

	/** Infrastructure → Clusters */
	static final String CLUSTERS = 'infrastructure-cluster'
	/** Infrastructure → Storage */
	static final String STORAGE = 'infrastructure-storage'
	/** Infrastructure → Compute (hosts) */
	static final String SERVERS = 'admin-servers'

	private static final Set<String> READ_LEVELS = ['read', 'full'] as Set

	boolean canRead(User user, String code) {
		return READ_LEVELS.contains(user?.permissions?.get(code))
	}

	/** Path states are shown per datastore of a cluster: storage and cluster read access */
	boolean canViewPaths(User user) {
		return canRead(user, STORAGE) && canRead(user, CLUSTERS)
	}

	/** Host names are only returned to users who may see hosts */
	boolean canViewHosts(User user) {
		return canRead(user, SERVERS)
	}

	/**
	 * Users of the master tenant may see objects of every tenant (as far as Morpheus returns them); all other users
	 * only objects owned by their own tenant.
	 */
	boolean canSeeTenant(User user, Long ownerId) {
		def account = user?.account
		if (account?.id == null) {
			return false
		}
		if (account.masterAccount) {
			return true
		}
		return ownerId != null && ownerId == account.id
	}
}
