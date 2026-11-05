package eu.liehne.morpheus.storagepathmonitor

import com.morpheusdata.core.Plugin
import com.morpheusdata.views.HandlebarsRenderer
import groovy.util.logging.Slf4j

import java.util.function.Supplier

/**
 * Monitors the multipath storage paths and mounts of shared-LUN datastores on HVM hosts. Collects in the background
 * ({@link PathCollector}) and offers the result through one read-only route ({@link PathsController}).
 */
@Slf4j
class StoragePathMonitorPlugin extends Plugin {

	static final String CODE = 'storage-path-monitor'
	static final String AUTHOR = 'Pascal Liehne'
	static final String LICENSE_NAME = 'MIT'
	static final String REPOSITORY_URL = 'https://github.com/tigpas-ai/morpheus-storage-path-monitor'

	private PathCollector collector

	@Override
	String getCode() {
		return CODE
	}

	@Override
	void initialize() {
		this.setName('Storage Path Monitor')
		this.setAuthor(AUTHOR)
		this.setSourceCodeLocationUrl(REPOSITORY_URL)
		this.setIssueTrackerUrl("${REPOSITORY_URL}/issues".toString())
		this.setDescription('Monitors the multipath storage paths and mounts of shared-LUN datastores on HVM hosts')
		// Own renderer: with controllers, Morpheus 9.1 otherwise adds the plugin's templates to the shared renderer,
		// which fails (UnsupportedOperationException in DynamicTemplateLoader)
		this.setRenderer(new HandlebarsRenderer('renderer', this.classLoader))
		this.settings.addAll(MonitorSettings.optionTypes())

		AccessPolicy accessPolicy = new AccessPolicy()
		MorpheusQueries queries = new MorpheusQueries(this.morpheus, accessPolicy)
		Supplier<MonitorSettings> settings = { readSettings() } as Supplier<MonitorSettings>
		collector = new PathCollector(queries, new AgentCommandRunner(this.morpheus), settings)
		this.controllers.add(new PathsController(this, this.morpheus, queries,
			{ collector.snapshot } as Supplier<Snapshot>, settings, accessPolicy))
		this.registerProvider(new MultipathStatusTabProvider(this, this.morpheus, queries, accessPolicy))
		collector.start()
	}

	private MonitorSettings readSettings() {
		try {
			return MonitorSettings.parse(this.morpheus.getSettings(this)?.blockingGet())
		} catch (Exception e) {
			log.warn("Storage Path Monitor: could not read plugin settings, using defaults: ${e.message}")
			return MonitorSettings.DEFAULTS
		}
	}

	/** Called when the plugin is removed or replaced: stop the background collection */
	@Override
	void onDestroy() {
		collector?.stop()
	}
}
