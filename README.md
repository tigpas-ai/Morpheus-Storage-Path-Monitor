# Storage Path Monitor – Morpheus plugin

Morpheus plugin (plugin API 1.4.1, Morpheus 9.1) that monitors the storage paths (multipath) of shared-LUN datastores
on HVM hosts. One plugin, one job: it collects path states in the background and offers them through a small
read-only interface for other plugins and tools.

Status: being set up.

## Planned features
- Background collection on the manager, independent of any UI: one fixed, read-only command per host covering all
  LUNs, configurable interval (default about 60 s), few hosts in parallel.
- Result per datastore and host: active/total paths, state `ok` / `degraded` / `down`, time of measurement, errors.
- Plugin route returning counters and states only (no raw command output), for users with storage read permission
  and visibility of the cluster.

## Security
The plugin runs a command as root on each host through the Morpheus agent. The command is fixed and read-only.

## License
MIT (planned).
