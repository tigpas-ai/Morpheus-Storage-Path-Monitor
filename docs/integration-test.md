# Integration test

`scripts/integration-test.sh` tests the built plugin on a live Morpheus appliance (9.1 or later) and afterwards puts
the appliance back into the state it was in before. It complements the unit tests (`./gradlew test`), which run
without an appliance.

```bash
export MORPHEUS_URL=https://morpheus.example.com
export MORPHEUS_TOKEN=…                 # API token of a user with the role System Admin
scripts/integration-test.sh             # builds the jar, tests it, restores the previous state
scripts/integration-test.sh --preflight # only checks whether the test can run and restore; changes nothing
```

Only one test of this plugin may run against an appliance at a time, and nobody else should install or configure
the plugin meanwhile – the test replaces the installed version for a few minutes. Run it when a short interruption of
the plugin is acceptable.

## Requirements
- `bash`, `curl`, `jq`, `git`, and `unzip` or the `jar` tool of the JDK. Building needs JDK 17 (see `README.md`).
- An API token of a user who may manage plugins, roles and users (System Admin) in the master tenant.
- The clock of the machine running the test must not be more than about 30 s ahead of the appliance (the test compares
  the time of the upload with the first collection run of the new version).
- Ideally at least one HVM cluster with a shared-LUN datastore and connected agents. Without one, the data checks of
  the route are skipped with a warning.

## Environment variables
| Variable | Required | Meaning |
|---|---|---|
| `MORPHEUS_URL` | yes | base URL of the appliance (`https://…`) |
| `MORPHEUS_TOKEN` | yes | API token (System Admin) |
| `MORPHEUS_CACERT` | no | CA file (PEM) to validate the server certificate against |
| `MORPHEUS_INSECURE` | no | `1` = skip certificate validation |
| `TEST_PREFIX` | no | name prefix for test objects (default `spmtest-`; `LAB_TEST_PREFIX` is read as a fallback); the test creates `<prefix>spm-it` |
| `SPM_TEST_JAR` | no | jar to test (same as `--jar`); default: build the working tree with `./gradlew shadowJar` |
| `SPM_RESTORE_JAR` | no | jar of the version installed before the test (same as `--restore-jar`); default: built from the git tag `v<installed version>` |
| `SPM_TEST_CLUSTER_ID` | no | test only this HVM cluster; default: every HVM cluster the route accepts |
| `SPM_TEST_REQUIRE_OK` | no | `1` = fail unless all datastores are `ok` (by default other states are only reported – they describe the storage, not the plugin) |
| `SPM_TEST_LOAD_TIMEOUT` | no | seconds to wait for `loaded` after an upload (default 180) |
| `SPM_TEST_RUN_TIMEOUT` | no | seconds to wait for the first collection run of the new version (default 240) |

## What it does
1. **Preflight – no changes yet.** Builds (or takes) the jar under test and reads code and version from its manifest,
   checks the connection and the token, and records the installed plugin: version, id, enabled flag and settings.
   If a version is installed, it needs the jar of exactly that version to restore it: `SPM_RESTORE_JAR`, or the git
   tag `v<version>` of this repository, which is checked out into a temporary worktree and built. The installed plugin
   must be `loaded`. **If anything of this fails, the test stops (exit code 2) before it changes anything.**
2. **Test user.** Creates the role and the user `<prefix>spm-it` (read access to storage, clusters and compute, all
   groups; random password, never printed). Leftovers with exactly these names from an aborted run are removed first.
3. **Install.** Uploads the jar (`POST /api/plugins/upload`) and waits for status `loaded` with the new version;
   checks that it is enabled and that an existing installation keeps its plugin id.
4. **Settings.** The three settings are offered; saving `intervalSeconds=15, parallelHosts=2, commandTimeoutSeconds=60`
   works and reads back unchanged.
5. **Assets and anonymous access.** The plugin's JavaScript and stylesheet are served; the route without a session
   redirects to the login (`302`).
6. **Route.** Signs in as the test user (UI session – plugin routes do not accept API tokens) and checks
   `GET /plugin/storage-path-monitor/paths`: `400` for a missing or invalid parameter, JSON `404` for an unknown
   cluster; for each HVM cluster it waits for the first collection run of the new version and validates the answer
   against [`CONTRACT.md`](../CONTRACT.md) (fields and types, sort order, counts, worst-state rules, datastore ids
   known to Morpheus, one entry per host of the cluster, host names present), `intervalSeconds` follows the setting,
   `hosts=false` leaves out the host lists, and the next collection run follows after the interval. The cluster page
   offers the Multipath Status view. The states per datastore are printed; measurement errors are warnings.
7. **Disable and enable.** While disabled the route is gone; after enabling it answers again.
8. **Permissions.** With changed role permissions of the test user: without Compute read the host names are hidden;
   without Storage read the route answers `403` and the cluster page has no Multipath Status view; without Clusters
   read the route answers `403`.
9. **Restore** – always, also after failed checks, errors and interrupts (Ctrl+C): see below.

## Restore
At the end, and on any exit after the preflight, the script:
- deletes the test user and role (and checks that none is left);
- if a version was installed before: uploads the restore jar (Morpheus accepts older versions), waits for `loaded`,
  writes back the previous settings (the whole settings object) and the previous enabled flag – an upload always
  enables the plugin, so a disabled plugin is disabled again – and compares version, id, enabled flag, status and
  settings with the state before;
- if no version was installed before: deletes the plugin and checks that it is gone.

If restoring fails, the script exits with code 3 and prints the state before the test; check the plugin under
*Administration → Integrations → Plugins*. An upload keeps the plugin id; only a test without a previous
installation creates (and deletes) a new plugin entry.

Not restored: what the plugin itself does while it runs (read-only commands per host and collection run, log
lines). Morpheus keeps uploaded jars in its plugin directory on the manager; after a successful upload only the
current one remains.

## Exit codes
| Code | Meaning |
|---|---|
| `0` | all checks passed, previous state restored |
| `1` | at least one check failed, previous state restored |
| `2` | stopped before any change (missing variable or tool, appliance not reachable, no restore jar …) |
| `3` | restoring the previous state (or removing the test objects) failed – check by hand |
| `64` | usage error |
| `130` | interrupted (previous state restored, see the output) |

## Releases
Tag every released version as `v<version>` (e.g. `git tag -a v0.3.0`), so that a later test can rebuild it to
restore an installation of that version. For an installed version without a tag, provide its jar in
`SPM_RESTORE_JAR`.

The restore matches on the version number, not on the binary: if the installed jar was a development build that
differs from the tagged release with the same version, provide exactly that jar in `SPM_RESTORE_JAR`.

## Notes
- Secrets are never passed on a command line: the token header and request bodies go to curl as files in a private
  temporary directory, the sign-in password through stdin. The test user's password is random and never printed.
- The test prints the plugin settings before and after (they contain no secrets).
