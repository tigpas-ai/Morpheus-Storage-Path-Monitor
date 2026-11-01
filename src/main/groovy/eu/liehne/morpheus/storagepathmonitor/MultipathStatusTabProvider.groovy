package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

import java.security.MessageDigest

/**
 * Tab "Multipath Status" on the detail page of HVM clusters. The page itself carries no data: its script loads the
 * path states from {@code GET /plugin/storage-path-monitor/paths} with the user's session, so the route's permission
 * and tenant checks apply unchanged. {@link #show} hides the tab from users who could not read the route anyway.
 */
@Slf4j
class MultipathStatusTabProvider extends AbstractClusterTabProvider {

	static final String CODE = 'storage-path-monitor-tab'
	static final String NAME = 'Multipath Status'
	static final List<String> ASSETS = ['assets/storage-path-monitor.js', 'assets/storage-path-monitor.css']

	private final Plugin plugin
	private final MorpheusContext morpheus
	private final AccessPolicy accessPolicy
	private final UserDirectory directory
	private String assetVersion

	MultipathStatusTabProvider(Plugin plugin, MorpheusContext morpheus, UserDirectory directory,
							   AccessPolicy accessPolicy = new AccessPolicy()) {
		this.plugin = plugin
		this.morpheus = morpheus
		this.directory = directory
		this.accessPolicy = accessPolicy
	}

	/**
	 * Shown on HVM clusters the user may see, to users who may read the route; type and tenant are checked with the
	 * same query as the route. Morpheus hands over the user without its tenant ({@code user.account} is empty) and
	 * the tenant as {@code account}.
	 */
	@Override
	Boolean show(ComputeServerGroup cluster, User user, Account account) {
		if (cluster?.id == null || !accessPolicy.canViewPaths(user)) {
			return false
		}
		try {
			return directory.clusterVisible(user, cluster.id as Long, user.account ?: account)
		} catch (Exception e) {
			log.warn("Storage Path Monitor: could not check cluster ${cluster.id} for the tab: ${e.message}")
			return false
		}
	}

	@Override
	HTMLResponse renderTemplate(ComputeServerGroup cluster) {
		ViewModel<Object> model = new ViewModel<>()
		model.object = [clusterId: cluster.id, assetVersion: assetVersion(), version: plugin?.version ?: '',
					   author: StoragePathMonitorPlugin.AUTHOR, license: StoragePathMonitorPlugin.LICENSE_NAME,
					   repositoryUrl: StoragePathMonitorPlugin.REPOSITORY_URL]
		return getRenderer().renderTemplate('hbs/multipathStatusTab', model)
	}

	/** Short hash of the assets, appended to their URLs so browsers load a new version after an upgrade */
	String assetVersion() {
		if (assetVersion == null) {
			MessageDigest digest = MessageDigest.getInstance('SHA-256')
			ASSETS.each { String path ->
				InputStream stream = getClass().classLoader.getResourceAsStream(path)
				stream?.withCloseable { digest.update(it.bytes) }
			}
			assetVersion = digest.digest().encodeHex().toString().take(12)
		}
		return assetVersion
	}

	@Override
	ContentSecurityPolicy getContentSecurityPolicy() {
		return null
	}

	@Override
	String getCode() {
		return CODE
	}

	@Override
	String getName() {
		return NAME
	}

	@Override
	Plugin getPlugin() {
		return plugin
	}

	@Override
	MorpheusContext getMorpheus() {
		return morpheus
	}
}
