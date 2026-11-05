# Security

The plugin runs inside the Morpheus manager with full access to its data services, and it runs commands as root on
every HVM host (through the Morpheus agent). A flaw in it can expose data across tenants or affect the hosts.

## Reporting a vulnerability
Please do **not** open a public issue. Use GitHub's private vulnerability reporting instead
(**Security** tab → **Report a vulnerability**) and include the affected release, the steps to reproduce and what an
attacker gains. Reports are answered as soon as possible.

## Supported versions
Only the latest release receives fixes.

## Design rules that limit the impact
- The commands on the hosts are constants (listed in the [README](README.md#security)) and only read; nothing from
  requests, settings or Morpheus objects is ever added to them.
- Raw command output never leaves the manager; the route returns counters, states and fixed error texts, plus device
  and mount point of a mount without a data store, for users who may see hosts.
- The route and the cluster view check role permissions and tenant of the signed-in user themselves and only show
  what the user could see in Morpheus (details in [CONTRACT.md](CONTRACT.md)).
- Host calls happen only in the background collection with bounded parallelism, never per request.
