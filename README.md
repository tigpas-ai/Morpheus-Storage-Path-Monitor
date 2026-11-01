# Storage Path Monitor – Morpheus plugin

Morpheus plugin (plugin API 1.4.1, Morpheus 9.1) that monitors the storage paths (multipath) of shared-LUN datastores
on HVM hosts. One plugin, one job: it collects path states in the background and offers them through a small
read-only interface for other plugins and tools.

## Features
- Background collection on the manager, independent of any UI: one fixed, read-only command per host covering all
  LUNs, configurable interval (default 60 s), a few hosts in parallel.
- Result per datastore and host: active/total paths, state `ok` / `degraded` / `down` / `unknown`, time of
  measurement, error of the query.
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

## Build
Requires JDK 17 (the code is compiled for Java 11; the Gradle wrapper runs on Java up to 20).

```bash
./gradlew test shadowJar     # → build/libs/storage-path-monitor-<version>-all.jar
```

Install the JAR under *Administration → Integrations → Plugins → Upload File* or with `POST /api/plugins/upload`
(multipart field `file`). The first collection run starts about 30 s after the plugin is loaded.

## Tests
- Unit tests: `./gradlew test` (no appliance needed).
- Integration test against a live appliance: `scripts/integration-test.sh` installs the built plugin, checks status,
  settings, route, permissions and the cluster page, and then restores the previous state (the previously installed
  version with its settings and enabled flag, or no plugin). Connection only through environment variables
  (`MORPHEUS_URL`, `MORPHEUS_TOKEN`, …) – see [`docs/integration-test.md`](docs/integration-test.md).

## Requirements on the hosts
- HVM hosts with the Morpheus agent installed and connected.
- `hvmcli` on the host (part of HVM). Multipath device names must match the datastore block device
  (`/dev/mapper/<name>`), which is the case for datastores created by Morpheus.

## License
MIT, see [`LICENSE`](LICENSE).
