Automation Map 2.2.0 is out, upgrading from 2.1.1.

Device discovery now walks the complete device tree the hub returns, not just the top level. A device created by another device's driver rather than an app (Shelly, Bond, and Matter bridges were the reported cases) could be nested under its parent and invisible to the previous flat read. Thanks to community tester Steve (oldcomputerwiz) for reporting this and confirming the fix on his own hub, where Automation Map and Hubitat now agree on the exact device count.

Local Variables get their own nodes on the graph and in exports now, correctly told apart from Hub Variables and Variable Connector devices instead of being lumped in with them.

The Apps, Devices, Hub Variables, and Local Variables dropdowns are each a single searchable control now instead of one long scrollable list.

The automatic-scan time field shows its real default instead of appearing blank. Added a diagnostic-logging toggle for troubleshooting, off by default.

Refreshed the visual style.

Fixed two scan-completion timing issues that could leave the map looking stuck, or briefly hide the map link and counts, right after a scan actually finished. Fixed a related timing issue in registry lookups that could leave dependency information incomplete under specific conditions.

Post here or open a GitHub issue if you hit something.
