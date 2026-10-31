package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

import java.util.function.Supplier

/**
 * Read-only JSON route, reachable in the browser under {@code /plugin/storage-path-monitor/paths} with the user's UI
 * session. It never runs host commands: it only combines the collected snapshot with the datastores the user may see.
 */
@Slf4j
class PathsController implements PluginController {

	static final String BASE = '/storage-path-monitor'

	private final Plugin plugin
	private final MorpheusContext morpheus
	private final UserDirectory directory
	private final Supplier<Snapshot> snapshot
	private final Supplier<MonitorSettings> settings
	private final AccessPolicy accessPolicy

	PathsController(Plugin plugin, MorpheusContext morpheus, UserDirectory directory, Supplier<Snapshot> snapshot,
					Supplier<MonitorSettings> settings, AccessPolicy accessPolicy = new AccessPolicy()) {
		this.plugin = plugin
		this.morpheus = morpheus
		this.directory = directory
		this.snapshot = snapshot
		this.settings = settings
		this.accessPolicy = accessPolicy
	}

	@Override
	List<Route> getRoutes() {
		[Route.build("${BASE}/paths".toString(), 'paths', Permission.build(AccessPolicy.STORAGE, 'read'))]
	}

	/** GET /plugin/storage-path-monitor/paths?clusterId=…[&hosts=false] */
	JsonResponse paths(ViewModel model) {
		model.response?.setCharacterEncoding('UTF-8')
		User user = model.user
		if (user == null || !accessPolicy.canViewPaths(user)) {
			return error(model, 403, 'forbidden')
		}
		String method = model.request?.method
		if (method != null && method != 'GET') {
			return error(model, 405, 'method not allowed')
		}
		String raw = model.request?.getParameter('clusterId')
		if (!raw?.isLong()) {
			return error(model, 400, 'parameter clusterId must be a number')
		}
		Long clusterId = raw.toLong()
		String hostsParam = model.request?.getParameter('hosts')
		if (hostsParam != null && !(hostsParam in ['true', 'false'])) {
			return error(model, 400, 'parameter hosts must be true or false')
		}
		try {
			if (!directory.clusterVisible(user, clusterId)) {
				return error(model, 404, 'not found')
			}
			List<SharedDatastore> datastores = directory.datastores(user, clusterId)
			MonitorSettings current = settings.get() ?: MonitorSettings.DEFAULTS
			return JsonResponse.of(PathReport.build(clusterId, datastores, snapshot.get(), current,
				accessPolicy.canViewHosts(user), hostsParam != 'false'))
		} catch (Exception e) {
			log.error('Storage Path Monitor: request failed', e)
			return error(model, 500, 'internal error')
		}
	}

	private static JsonResponse error(ViewModel model, int status, String message) {
		JsonResponse response = JsonResponse.of([error: message])
		response.status = status
		model.response?.setStatus(status)
		return response
	}

	@Override
	String getCode() {
		return 'storage-path-monitor.controller'
	}

	@Override
	String getName() {
		return 'Storage Path Monitor Controller'
	}

	@Override
	MorpheusContext getMorpheus() {
		return morpheus
	}

	@Override
	Plugin getPlugin() {
		return plugin
	}
}
