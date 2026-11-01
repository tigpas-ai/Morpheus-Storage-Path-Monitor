package eu.liehne.morpheus.storagepathmonitor

import spock.lang.Specification

/**
 * Version, author and repository URL are stated in several places (Gradle properties and jar manifest, plugin
 * metadata, README, CHANGELOG, LICENSE). gradle.properties is the leading source; everything else has to match it.
 * Runs with the project directory as working directory (Gradle default).
 */
class ProjectMetadataSpec extends Specification {

	static String read(String path) {
		new File(path).getText('UTF-8')
	}

	static String version() {
		Properties p = new Properties()
		new File('gradle.properties').withInputStream { p.load(it) }
		p.getProperty('version')
	}

	def 'the version is a plain release version'() {
		expect:
		version() ==~ /\d+\.\d+\.\d+/
	}

	def 'the CHANGELOG has an entry with date for the current version'() {
		expect:
		read('CHANGELOG.md') =~ /(?m)^## ${java.util.regex.Pattern.quote(version())} – \d{4}-\d{2}-\d{2}$/
	}

	def 'the jar manifest names the same repository, author and version source'() {
		given:
		String build = read('build.gradle')

		expect:
		build.contains("'Implementation-URL': '${StoragePathMonitorPlugin.REPOSITORY_URL}'")
		build.contains("'Implementation-Vendor': '${StoragePathMonitorPlugin.AUTHOR}'")
		build.contains("'Implementation-Version': version")
		build.contains("'Plugin-Version': version")
		build.contains("'Morpheus-Code': '${StoragePathMonitorPlugin.CODE}'")
		build.contains("'Plugin-Class': '${StoragePathMonitorPlugin.name}'")
	}

	def 'README and LICENSE name the same repository and author'() {
		given:
		String readme = read('README.md')

		expect:
		readme.contains(StoragePathMonitorPlugin.REPOSITORY_URL)
		readme =~ /(?m)^## Author\s+${StoragePathMonitorPlugin.AUTHOR}$/
		read('LICENSE') =~ /Copyright \(c\) \d{4} ${StoragePathMonitorPlugin.AUTHOR}/
	}
}
