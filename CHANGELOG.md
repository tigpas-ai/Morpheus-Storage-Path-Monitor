# Changelog

## 0.3.0
- Detail view "Multipath Status" on the cluster page (cluster tab provider), placed as fourth entry under Storage when
  the page has that sub-navigation, otherwise as a main tab. Linkable with `#!storage-path-monitor-tab` and the
  optional parameters `spm-datastore` and `spm-host` (see `CONTRACT.md`).

## 0.2.0
- A host with fewer paths for a LUN than the other hosts of the cluster is `degraded`, even if all its remaining
  paths are active (multipath forgets removed paths, e.g. after an iSCSI logout, and reports 1/1).
- New field `hosts[].expectedPaths` in `GET /plugin/storage-path-monitor/paths`.

## 0.1.0
- Background collection of multipath path states per host (`hvmcli storage multipath status --json` through the
  agent), configurable interval, parallelism and timeout.
- Route `GET /plugin/storage-path-monitor/paths?clusterId=<id>[&hosts=false]` (see `CONTRACT.md`).
- Warnings in the Morpheus log on state changes.
