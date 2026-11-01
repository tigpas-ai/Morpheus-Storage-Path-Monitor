# Requirements and findings

## Goal
A Morpheus plugin (plugin API 1.4.1, Morpheus 9.1) that monitors the multipath storage paths of shared-LUN datastores
on HVM hosts and offers the result to other plugins and tools. One plugin, one job: collect and expose path states.

Background: Morpheus does not store the path state. The host inventory (`/api/servers/:id` → `volumes`) lists only
the first path of each LUN, although a host typically has several.

## Requirements
1. **Background collection on the manager**, independent of any request. One fixed, read-only command per host covers
   all LUNs (no free commands). Configurable interval (default 60 s), few hosts in parallel. Must scale to
   16 hosts × 200 volumes × 16 paths. The background thread stops cleanly in `onDestroy`.
2. **Result per datastore and host:** active/total paths, state `ok` (all active) / `degraded` (some) / `down` (none
   active or LUN missing), time of measurement, error of the query.
3. **Interface:** plugin route `GET /plugin/storage-path-monitor/paths?clusterId=<id>` (UI session), counters and
   states only, no raw data. Permissions: storage read access and visibility of the cluster/tenant. → `CONTRACT.md`.
4. **Security documented:** the plugin runs a command as root through the agent (README, section Security).
5. **Test** of `degraded`/`down` with a really disconnected path – only after explicit approval, one path always stays
   active.

## Design decisions
| Topic | Decision | Reason |
|---|---|---|
| Host command | `hvmcli storage multipath status --json` (constant `MultipathStatus.COMMAND`) | Part of every HVM host (docs 9.1.0 p. 668), one call for all devices, JSON with `device`, `paths`, `active`, `failed`, `state` per device. About 200 bytes per device (200 LUNs ≈ 40 KB). The alternative `multipathd show paths raw format …` returns one line per path (16 × 200 = 3200 lines per host) and would need more parsing |
| Execution | `morpheus.executeCommandOnServer(server, command)` through the agent, `timeout` on the returned `Single` | Only documented way of the plugin API; needs the full `ComputeServer` model (`computeServer.get(id)`, about 0.5 s) – done in the worker, once per host and run |
| Scheduling | own `ScheduledExecutorService` (one daemon thread) plus a fixed pool per run with `parallelHosts` threads; next run `intervalSeconds` after the end of the previous one | Runs never overlap; settings take effect on the next run; `onDestroy` → `shutdownNow` of both |
| Data per request | snapshot in memory (hosts per cluster + last measurement per host); datastores are read per request with the user | No host calls per request; datastore visibility follows the user's permissions |
| Datastore ↔ LUN | `config.blockDevice = /dev/mapper/<name>`; `<name>` is matched against `device` of the command output | Datastore type `libvirt-dir-gfs2` stores the multipath device there (verified). Other datastores are ignored |
| Expected paths | highest path count of the LUN on any measured host of the cluster (`expectedPaths`); fewer → `degraded` | multipath forgets removed paths (test B); assumes alike cabling of all hosts |
| Expected hosts | all hosts of the cluster (servers with `serverGroup` = cluster, no parent) | A shared LUN must be present on every host; a host without it is `down`. The datastore `locations[]` are not used: they are empty while a datastore is provisioning |
| Failed measurement | state `unknown` with `error`, counters `null` | Old counters would look current |
| Error texts | fixed English texts, never command output | Output may contain host details |
| Log | warning on each state change per host/device (first measurement: only devices that are not ok) | Visible in the Morpheus log without a consumer |

## Detail page (0.3.0)
Requirement: a detail page in the Morpheus UI, so the plugin can be used on its own, ideally as fourth entry under
*Clusters → <cluster> → Storage*, with a stable link for other plugins.

- The plugin API 1.4.1 offers `ClusterTabProvider` (`renderTemplate(cluster)`, `show(cluster, user, account)`) for
  **main tabs** of the cluster page only. The entries under Storage (Data Stores, Volumes, Storage Interfaces) are fixed
  markup of the page (Bootstrap pills `#storage .nav-pills` with a `.tab-content`); there is no extension point for them.
- Chosen: cluster tab provider; its script moves the tab into the storage pills while the page is parsed. If the
  structure is not found, the main tab stays (fallback). The view itself is static markup; data comes from the route.
- Verified: Morpheus renders the tab as `<a href="#storage-path-monitor-tab" data-toggle="tab">` with the pane
  `#storage-path-monitor-tab`; the page selects tabs and pills from `#!<id>` in the URL (also the parent tab of a
  pill). Query parameters on the cluster page are ignored by Morpheus (`200`).
- Verified: `show()` receives the user **without** tenant (`user.account` is `null`); the tenant comes as `account`.
  The tenant check and the scope of the cluster query (`DataQuery.account`) use it. `user.permissions` is filled.
- Links of the view: data store page `/infrastructure/storage/data-stores/<id>` and host page
  `/infrastructure/servers/<id>` (both verified, `200`).
- Assets via `{{asset '/…'}}` and `nonce`, served under `/assets/plugin/storage-path-monitor/`.

## Findings (verified on a test appliance, Morpheus 9.1)
- `hvmcli storage multipath status --json` exists (hvmcli 1.0.0), answer in about 0.3 s directly on the host;
  through the agent including loading the host about 2.5–5.5 s. Example per device:
  `{"device":"36e8…","transport":"iscsi","paths":2,"active":2,"failed":0,"state":"active"}`, wrapped in
  `{"errorCode":0,"messages":"","data":[…]}`.
- The device name is the WWID (no `user_friendly_names`); it equals the name in `config.blockDevice`.
- `executeCommandOnServer(server, command)` returns `TaskResult` with `success`, `output`, `exitCode`; runs as root
  (no `sudo` needed).
- `listOptions` with `propertyList` including `config` returns the datastore config (the plugin accepts it as JSON
  string or map); the filters
  `refType`, `refId` (also with `in`), `type.code`, `serverGroup.id` (with `in`) work.
- Plugin routes accept only the UI session (bearer token → `302` to the login). A route of a plugin that is not
  installed returns `404` with HTML.
- Replacing the plugin (upload of a new JAR): Morpheus initializes the new instance first and then calls `onDestroy`
  on the old one; the log shows "collection started" followed by "collection stopped", and the new collector keeps
  running (verified via `lastRunAt`).
- Disabling the plugin (`PUT /api/plugins/:id` with `enabled: false`) calls `onDestroy` (collection stops, the route
  answers `404`); enabling it again calls `initialize` (collection starts again). Verified.
- Permissions (verified with test users): `infrastructure-storage` read + `infrastructure-cluster` read without
  `admin-servers` → `200` without host names; `infrastructure-storage` set to `none` → `403` (JSON from the plugin).
  If the role has no entry for `infrastructure-storage` at all (never set), Morpheus refuses the route itself with
  `404` and its HTML error page – the plugin is not called.
- Plugin installation over the API (verified for the integration test): uploading an **older** version is accepted
  (downgrade, e.g. 0.3.0 → 0.2.0); an upload keeps the plugin id and the settings (`config`), but **enables** a
  disabled plugin. `PUT /api/plugins/:id` with `config` replaces the whole settings object. `DELETE /api/plugins/:id`
  removes the plugin (assets and route answer `404` afterwards); a new installation gets a new id and empty settings.

## Path failure tests (test appliance, one host of three, two iSCSI portals, one target, 10 LUNs)
| Test | What happened on the host | Plugin (interval 15 s) |
|---|---|---|
| A: iSCSI traffic to portal 2 dropped (firewall) | multipath: 1/2 active per LUN after about 15 s | `degraded` 1/2 after about 30 s; `ok` 2/2 again 15 s after the rule was removed |
| B: iSCSI session to portal 2 logged out | the paths disappear: multipath reports **1/1** (`paths` 1) | 0.1.0: `ok` 1/1 (wrong). → 0.2.0 compares with the other hosts (`expectedPaths`) and reports `degraded`. After login 2/2 within 30 s |
| C: traffic to both portals dropped | after a few seconds GFS2 withdraws the file system (`Error -5 writing to journal`), followed by a kernel BUG in the GFS2 glock code → kernel panic, kdump, reboot (twice, about 2 min) | host unreachable → `unknown` with `no answer within 30 s`; `ok` again after the reboot once multipath and the mounts are back |

| C again, with I/O queueing for 60 s (`no_path_retry 12`), outage about 50 s | multipath 0/2 active, I/O queued; no withdraw, no reboot; I/O continues afterwards | `down` 0/2 after about 30 s; `ok` 2/2 again about 30 s after the rule was removed |
| C again, with queueing, outage about 100 s | after the queueing time ran out: same withdraw and kernel BUG, reboot | `down` 0/2 while the host was up, then `unknown` until it was back |

Conclusions:
- Without I/O queueing (`features='0'`), a host with mounted GFS2 datastores does not stay up long enough after
  losing all paths to be seen as `down`; it is reported as `unknown`. With queueing, `down` is reported for as long as
  the host survives (verified). `down` is also relevant for a single LUN that is lost (unmapped, missing on one host)
  while the host keeps running.
- The other hosts keep running; the failed host leaves the DLM lockspaces and rejoins after the reboot.

## Open points
1. Users of a sub-tenant: the tenant check is unit-tested; a test with a real sub-tenant user is still open
   (the test appliance has only the master tenant).
2. Group access of roles: whether `DataQuery(user)` filters clusters by the role's group access is not established
   (same open point as in other plugins using the same services).
