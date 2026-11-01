# Security

The plugin runs inside the Morpheus manager with full access to its data services, and it runs a command as root on
every HVM host with a shared-LUN datastore (through the Morpheus agent). A flaw in it can expose data across tenants
or affect the hosts.

## Reporting a vulnerability
Please do **not** open a public issue. Use GitHub's private vulnerability reporting instead
(**Security** tab → **Report a vulnerability**) and include the affected release, the steps to reproduce and what an
attacker gains. Reports are answered as soon as possible.

## Supported versions
Only the latest release receives fixes.

## Design rules that limit the impact
- The command on the hosts is a constant (`hvmcli storage multipath status --json`) and only reads; nothing from
  requests, settings or Morpheus objects is ever added to it.
- Raw command output never leaves the manager; the route returns counters, states and fixed error texts only.
- The route and the cluster view check role permissions and tenant of the signed-in user themselves and only show
  what the user could see in Morpheus (details in [CONTRACT.md](CONTRACT.md)).
- Host calls happen only in the background collection with bounded parallelism, never per request.
