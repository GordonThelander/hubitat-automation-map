# Community update, 2.4.2

Draft approved by Gordon on 2026-09-28, held for release on Wednesday 2026-09-30.

Screenshot to accompany it: `docs/release-assets/room-manager-2.4.2.webp` (Room Manager with five
devices multi-selected in Not Allocated and dragged into Guest Bathroom, drop target highlighted,
tip card visible).

Two things to check before posting: the app is still named Automation Map everywhere including the
HPM package, so the HAM heading below is ahead of the code; and the production release on `main` is
still 2.3.2 at the time of writing.

---

**Hubitat Automation Manager (HAM) 2.4.2**

**1. Room Manager**

GUI room management and device allocation. All your rooms on one screen, drag devices between them.

The hub already does this a room at a time from Rooms, and Auto Room Sorter can do it automatically
where the device label carries the room name. Room Manager is for the rest: seeing every room at
once, with the unallocated pile sitting next to every destination.

- Drag a device between any two rooms
- Ctrl-click to pick several, drag one, the rest follow. Shift-click takes a run
- Double-click a room name to rename. The small x deletes it
- Create rooms without leaving the page

Nothing reaches the hub until you press Apply. Discard throws it away. I filed 50 devices in about
25 seconds.

**Writing to the hub was added for this and nothing else.** It does not command devices, edit rules,
or touch other apps. The only thing it writes is a device's room. Deleting a room does not delete
its devices.

**2. Hubitat Automation Intelligence (HAI 1.0)**

You will see HAI mentioned around the app. It is HAM's sibling: a modern Rule Machine 5 alternative
with a GUI and a fully portable JSON rule format, currently under development. Nothing in HAM
depends on it. If you do not have it, the map works as before, but HAI benefits from HAM's decoding.

HAM reads what your Rule Machine rules actually do, so it can tell you what would move across. The
HAI RM5 Coverage page measures every rule on your hub against what HAI can do, and the webCoRE
Migration Assessment rates each piston the same way. Both work whether or not HAI is installed.

**3. Interface and quality improvements**

- App tags were wrong. [INT] means ships with the hub, [CUS] means you installed it. Nine apps had
  it backwards, including LIFX Light Manager, CoCoHue, Kasa and Tapo. Now taken from the hub's own
  list rather than one I maintained by hand
- Rule flowcharts open wide enough to read
- The flowchart library loads only when you open a flowchart
- On a phone the map link sits above the Scan button

Rescan after upgrading for the app tags to correct.
