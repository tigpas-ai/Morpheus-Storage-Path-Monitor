# Storage Path Monitor – Morpheus plugin

Morpheus plugin that monitors the storage paths (multipath) of shared-LUN datastores on HVM hosts. One plugin, one
job: it collects path states in the background, shows them on the cluster page and offers them through a small
read-only interface for other plugins and tools.

> Tested with **HPE Morpheus 9.1.0** (plugin API 1.4.1), HVM clusters with iSCSI shared-LUN datastores.
> Source: <https://github.com/tigpas-ai/morpheus-storage-path-monitor>

## Contents
| Path | What |
|---|---|
| [src/](src/) | plugin source (Groovy), view template and assets; unit tests under `src/test` |
| [CONTRACT.md](CONTRACT.md) | the interface for consumers: route, fields, states, permissions, deep links |
| [REQUIREMENTS.md](REQUIREMENTS.md) | requirements and the findings about Morpheus they are based on |
| [docs/integration-test.md](docs/integration-test.md) | integration test against a live appliance |
| [scripts/integration-test.sh](scripts/integration-test.sh) | the integration test itself |
| [CHANGELOG.md](CHANGELOG.md), [SECURITY.md](SECURITY.md) | changes per version, reporting security issues |

## Quick start
Build (JDK 17; the code is compiled for Java 11, the Gradle wrapper runs on Java up to 20):

```bash
./gradlew test shadowJar     # → build/libs/storage-path-monitor-<version>-all.jar
```

**Install or update** on a running appliance: *Administration → Integrations → Plugins → Upload File*, or
`POST /api/plugins/upload` with the jar in the multipart field `file`. An update keeps the plugin entry and its
settings (and enables a disabled plugin). The first collection run starts about 30 s after the plugin is loaded; the view then appears under
*Clusters → <cluster> → Storage → Multipath Status*.

**Uninstall**: *Administration → Integrations → Plugins*, delete the plugin, or `DELETE /api/plugins/<id>`. The
background collection stops, the view and the route disappear, the settings are removed; nothing is left on the
hosts. To pause it instead, disable the plugin on the same page.

## Features
- Background collection on the manager, independent of any UI: one fixed, read-only command per host covering all
  LUNs, configurable interval (default 60 s), a few hosts in parallel.
- Result per datastore and host: active/total paths, state `ok` / `degraded` / `down` / `unknown`, time of
  measurement, error of the query. A host with fewer paths for a LUN than the other hosts of the cluster is
  `degraded`.
- Plugin route `GET /plugin/storage-path-monitor/paths?clusterId=<id>` returning counters and states only (no raw
  command output), for users with storage read permission and visibility of the cluster. → [`CONTRACT.md`](CONTRACT.md)
- Detail view **Multipath Status** on each HVM cluster (*Clusters → <cluster> → Storage → Multipath Status*): state per
  data store, expandable to the hosts with paths, time of measurement and errors; linkable (see `CONTRACT.md`).
- State changes (e.g. a LUN going from `ok` to `degraded` on a host) are written to the Morpheus log as warnings.

## Settings
*Administration → Integrations → Plugins → Storage Path Monitor*

| Setting | Default | Range | Meaning |
|---|---|---|---|
| Interval (seconds) | 60 | 15–3600 | pause between the end of one collection run and the start of the next |
| Hosts in parallel | 4 | 1–16 | hosts queried at the same time |
| Command timeout (seconds) | 30 | 5–300 | upper bound for the command on one host |

Example: 16 hosts with 4 in parallel and about 5 s per host take about 20 s per run.

## Requirements on the hosts
- HVM hosts with the Morpheus agent installed and connected.
- `hvmcli` on the host (part of HVM). Multipath device names must match the datastore block device
  (`/dev/mapper/<name>`), which is the case for datastores created by Morpheus.

## Security
- The plugin runs **one** command on each host of an HVM cluster that has a shared-LUN datastore, through the
  Morpheus agent – **as root**: `hvmcli storage multipath status --json`. The command is a constant in the code
  (`MultipathStatus.COMMAND`); nothing from requests, settings or Morpheus objects is added to it. It only reads.
- The command output stays on the manager; the route only returns counters, states and fixed error texts.
- The route checks the role permissions and the tenant of the signed-in user itself and only lists datastores the
  user may see; host names only with Compute read permission (details in [`CONTRACT.md`](CONTRACT.md)).
- When the plugin is replaced by a new version, Morpheus starts the new instance before it stops the old one, so
  for a moment both may query the hosts. The command is read-only, so this only costs one extra call per host.
- Like every plugin, it runs inside the Morpheus manager with full access to its data services.

Reporting a vulnerability: [SECURITY.md](SECURITY.md).

## Scope
Only collecting and showing multipath path states of shared-LUN datastores on HVM hosts. No alerting of its own
beyond the log warnings, no changes on hosts or storage, no other hypervisor types. Consumers build on the route
described in [`CONTRACT.md`](CONTRACT.md).

## Development
```bash
./gradlew test                                  # unit tests, no appliance needed
shellcheck scripts/integration-test.sh          # lint of the integration test
```

Integration test against a live appliance: `scripts/integration-test.sh` installs the built plugin, checks status,
settings, route, permissions and the cluster page, and then restores the previous state (the previously installed
version with its settings and enabled flag, or no plugin). Connection only through environment variables
(`MORPHEUS_URL`, `MORPHEUS_TOKEN`, `TEST_PREFIX`, …) – see [`docs/integration-test.md`](docs/integration-test.md).

The version in `gradle.properties` is the only source of the version number; the jar manifest takes it from there.
A unit test checks that `CHANGELOG.md` has a dated entry for it and that author and repository URL agree across
the jar manifest settings in `build.gradle`, the plugin metadata, this README and `LICENSE`.
Releases are annotated git tags `v<version>`.

## References
- [HPE Morpheus Software documentation 9.1.0](https://support.hpe.com/hpesc/public/docDisplay?docId=sd00008772en_us&docLocale=en_US)
  – chapters "HVM Clusters" and "Plugins"
- [Morpheus plugin developer documentation](https://developer.morpheusdata.com/)

## Author
Pascal Liehne

## License
[MIT](LICENSE), © 2026 Pascal Liehne. HPE, HPE Morpheus and HVM are trademarks of Hewlett Packard Enterprise. This
project is not affiliated with HPE.
