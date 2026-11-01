# Interface contract

This file describes everything other plugins, scripts and tools may rely on. Changes to routes or data formats are
recorded here in the same commit (see `CHANGELOG.md`).

Plugin code: `storage-path-monitor` · Plugin API 1.4.1 · Morpheus 9.1 or later.

## Detecting the plugin
The plugin is optional for consumers. It is installed and active if `GET /api/plugins` lists an entry with
`code == "storage-path-monitor"`, `enabled == true` and `status == "loaded"`. In the browser, a consumer can simply
call the route: `404` from Morpheus for the whole path (HTML instead of JSON) means the plugin is not installed.

## Detail page (since 0.3.0)

The path states are shown on the detail page of each HVM cluster as **Multipath Status**:
*Infrastructure → Clusters → <cluster> → Storage → Multipath Status* (fourth entry next to Data Stores, Volumes,
Storage Interfaces). If the storage sub-navigation of the page is not found (e.g. a changed Morpheus page layout), the
same view is a main tab of the cluster page instead.

### Link
```
/infrastructure/clusters/<clusterId>[?spm-datastore=<datastoreId>[&spm-host=<hostId>]]#!storage-path-monitor-tab
```
- `#!storage-path-monitor-tab` opens the view (works in both placements; Morpheus selects the parent tab itself).
- `spm-datastore` (optional): opens the host list of that data store, marks it and scrolls to it.
- `spm-host` (optional, together with `spm-datastore`): marks that host in the list.
- Unknown or invisible ids are ignored; the page then shows the normal view.
- Example: `/infrastructure/clusters/1?spm-datastore=21&spm-host=3#!storage-path-monitor-tab`.

### Visibility
The entry is only shown on HVM clusters (`mvm-cluster`) that the user may see, and only with the same role
permissions as the route below. The page loads its data from the route with the user's session, so the route's checks
apply unchanged. It loads when it is opened and reloads every 30 s while it is visible.

## `GET /plugin/storage-path-monitor/paths`

Path states of the shared-LUN datastores of one HVM cluster, per datastore and host.

- **Authentication:** Morpheus UI session (cookie). API bearer tokens are not accepted for plugin routes; Morpheus
  answers them with a redirect (`302`) to the login page.
- **Method:** `GET` only; other methods → `405`.
- **Cost:** reads collected results only, never runs a host command. Datastores are read from Morpheus with the
  user's permissions on every call (a few milliseconds).

### Parameters
| Name | Required | Values | Meaning |
|---|---|---|---|
| `clusterId` | yes | cluster id (number) | HVM cluster (`/api/clusters/:id`) |
| `hosts` | no | `true` (default), `false` | `false` leaves out the per-host list and only returns the counters per datastore |

### Permissions
- Role permission **Infrastructure: Storage** (`infrastructure-storage`) at least `read` **and** **Infrastructure:
  Clusters** (`infrastructure-cluster`) at least `read`; otherwise `403`.
- The cluster must be visible to the user (Morpheus data query with the user, plus tenant check: users of the master
  tenant see clusters of all tenants, other users only those of their own tenant); otherwise `404`.
- Only datastores the user may see are listed (same checks).
- Host names are only returned with **Infrastructure: Compute** (`admin-servers`) at least `read`; otherwise `name`
  is `null` and only the host id is given. Host ids and their states are returned to every user who may see the
  cluster and the datastore (the state of a datastore is a property of the datastore on each host).
- The route is registered with the permission `infrastructure-storage`. Without a session Morpheus answers `302`
  (redirect to the login page). If the user's role has no entry at all for `infrastructure-storage` (never set),
  Morpheus refuses the route itself with `404` and its HTML error page; with the permission set to `none` the plugin
  answers the JSON `403`. Consumers should treat any non-JSON answer like "not available".

### Response `200`
```json
{
  "clusterId": 1,
  "state": "degraded",
  "lastRunAt": "2026-10-31T15:19:17.800Z",
  "intervalSeconds": 60,
  "datastores": [
    {
      "id": 21,
      "name": "ds-1",
      "state": "degraded",
      "counts": { "ok": 1, "unknown": 0, "degraded": 2, "down": 0 },
      "hosts": [
        {
          "hostId": 1,
          "name": "host-1",
          "state": "ok",
          "activePaths": 2,
          "totalPaths": 2,
          "expectedPaths": 2,
          "measuredAt": "2026-10-31T15:19:23.236Z",
          "error": null
        }
      ]
    }
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `clusterId` | number | as requested |
| `state` | string \| null | worst `state` of all listed datastores; `null` if the cluster has no shared-LUN datastore |
| `lastRunAt` | string \| null | start of the last completed collection run (ISO-8601, UTC, milliseconds); `null` before the first run (about 30 s after the plugin was loaded) |
| `intervalSeconds` | number | configured pause between two runs; results are normally at most `intervalSeconds` + run time old |
| `datastores[]` | array | shared-LUN datastores of the cluster, sorted by name; datastores whose block device is not a multipath device (`/dev/mapper/<name>`) are not listed |
| `datastores[].id`, `.name` | number, string | Morpheus datastore (`/api/clusters/:id/datastores`) |
| `datastores[].state` | string | worst `state` over its hosts; `unknown` if the cluster has not been collected yet |
| `datastores[].counts` | object | number of hosts per state; always contains all four keys |
| `datastores[].hosts[]` | array | one entry per host of the cluster, sorted by host id (missing with `hosts=false`); empty (and all `counts` 0) while the cluster has not been collected yet, e.g. in the first ~30 s after the plugin was loaded or right after the first shared datastore was created |
| `hosts[].hostId` | number | Morpheus server id of the host |
| `hosts[].name` | string \| null | host name, `null` without Compute permission |
| `hosts[].state` | string | state of the LUN on this host, see below |
| `hosts[].activePaths`, `.totalPaths` | number \| null | active and known paths of the LUN on this host; `0`/`0` if the host does not see the LUN; `null` if the state is `unknown` |
| `hosts[].expectedPaths` | number \| null | highest `totalPaths` of this LUN on any measured host of the cluster (since 0.2.0); `null` if the state is `unknown` |
| `hosts[].measuredAt` | string \| null | time the host was last queried (ISO-8601, UTC); `null` if not queried yet |
| `hosts[].error` | string \| null | why the measurement failed (short English text, no command output); `null` on success |

### States
| `state` | Meaning |
|---|---|
| `ok` | all paths of the LUN active, and the host has as many paths as `expectedPaths` |
| `degraded` | some, but not all paths active – or all active, but fewer than `expectedPaths` (a path was removed on this host, e.g. iSCSI session logged out: multipath then forgets it and counts 1/1) |
| `down` | no path active, or the host does not see the LUN at all |
| `unknown` | not measured yet, or the last measurement of the host failed (see `error`) |

Severity for combining hosts and datastores: `down` > `degraded` > `unknown` > `ok`.

The comparison with `expectedPaths` assumes that all hosts of a cluster are connected to the storage alike. If one
host has fewer paths by design, it is permanently reported as `degraded`. A host that is switched off or cannot be
reached is `unknown` (not `down`): its paths cannot be measured.

Possible `error` texts (consumers should display, not parse them): `no answer within <n> s`,
`command failed with exit code <n>`, `command failed on the host`, `command could not be run on the host`,
`host not found`, `empty command output`, `command output is not valid JSON`, `command output too large`,
`unexpected command output`, `command reported error code <n>`, `measurement failed`.

### Errors
From the plugin always JSON `{"error": "<text>"}` with UTF-8 (the `302` and the HTML `404` come from Morpheus).

| Status | When |
|---|---|
| `302` | no UI session (from Morpheus, redirect to the login page, not JSON) |
| `404` (HTML) | from Morpheus: plugin not installed or disabled, or the role has no entry for `infrastructure-storage` |
| `400` | `clusterId` missing or not a number, `hosts` not `true`/`false` |
| `403` | missing role permission (see Permissions) |
| `404` (JSON) | cluster unknown, not an HVM cluster, or not visible to the user |
| `405` | method other than `GET` |
| `500` | internal error (details only in the Morpheus log) |

## Stability
- Fields are only added, never renamed or removed, within the same major version. Consumers must ignore unknown
  fields and unknown `state` values (treat them like `unknown`).
- No raw command output is ever returned.
