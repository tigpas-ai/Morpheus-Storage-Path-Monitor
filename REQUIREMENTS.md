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
| Expected hosts | all hosts of the cluster (servers with `serverGroup` = cluster, no parent) | A shared LUN must be present on every host; a host without it is `down`. The datastore `locations[]` are not used: they are empty while a datastore is provisioning |
| Failed measurement | state `unknown` with `error`, counters `null` | Old counters would look current |
| Error texts | fixed English texts, never command output | Output may contain host details |
| Log | warning on each state change per host/device (first measurement: only devices that are not ok) | Visible in the Morpheus log without a consumer |

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
  `admin-servers` → `200` without host names; without `infrastructure-storage` → `403`.

## Open points
1. `degraded`/`down` with a really disconnected path (requirement 5) – waiting for approval.
2. Users of a sub-tenant: the tenant check is unit-tested; a test with a real sub-tenant user is still open
   (the test appliance has only the master tenant).
3. Group access of roles: whether `DataQuery(user)` filters clusters by the role's group access is not established
   (same open point as in other plugins using the same services).
