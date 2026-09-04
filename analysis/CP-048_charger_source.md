# CP-048 — Charger dataset source

Date: 2026-09-04

Decision: **Open Charge Map**, with a second key the driver supplies, queried once per plan
along a bounded window of the route rather than along the whole route.

The competing option was the ORS POI service, which would have cost no second key and no second
dependency. It lost on one thing that cannot be worked around, and one that decides the feature.

## The two candidates

| | ORS POIs (openpoiservice) | Open Charge Map |
| --- | --- | --- |
| Data | OSM `amenity=charging_station` | OCM contributions + imported operator/national datasets |
| Licence | ODbL | CC BY 4.0 for OCM-contributed; imported data keeps its provider's licence |
| Key | the routing key already configured | a second key, free, `X-API-Key` |
| Connector type | not returned | `Connections[].ConnectionTypeID` |
| Power | not returned | `Connections[].PowerKW`, `Amps`, `Voltage` |
| Record age | **not returned** | `DateLastVerified`, `DateLastStatusUpdate` |
| Corridor query | geometry + buffer | `polyline` + `distance` |
| Quota | shares the ORS allowance | no published number, fair use, ban at OCM's discretion |

## Why OCM

**Scope item 4 is impossible with ORS POIs.** The ticket requires that a charger shows its
source and the age of its record, because a charger dataset is wrong the day it is published and
the honest answer is to say how old the claim is. The ORS POI response carries a category and a
handful of OSM tags; there is no timestamp in it at all. Nothing that ships on top of it could
show an age without inventing one.

**Scope item 3 is impossible too.** Filtering to what an MG4 can actually use — CCS and Type 2,
above a power the driver sets — needs connector type and power per connection. OSM records both
inconsistently and the ORS POI response does not return them at any level of detail. A list that
sends a driver to a 3 kW lamp-post socket because it could not tell is worse than no list.

Those two together end the comparison. The second key is a real cost — one more thing to enter,
one more thing that can be wrong — and it is the smaller cost.

**What OCM gives that is directly usable.** Verified against OCM's own published reference data
(`openchargemap/ocm-data`, `reference.json`, read 2026-09-04), not from memory:

- Connector IDs: **25** Type 2 (Socket Only), **1036** Type 2 (Tethered Connector), **33** CCS
  (Type 2). ID 32 is CCS (Type 1) and is not an MG4 connector.
- Status IDs that mean "do not send anyone here": **100** Not Operational, **150** Planned For
  Future Date, **200** Removed (Decommissioned), **210** Removed (Duplicate Listing).
- `DateLastVerified` — OCM's own note calls it "a dynamically computed date based on multiple
  signals", which is exactly the claim to show the driver rather than to hide.

## Licence and attribution

OCM's terms: *"Use of our API or data in an application or service requires appropriate Data
Provider attribution (including license terms) provided in a way visible to the end user."*

User-contributed data is CC BY 4.0. Imported third-party data stays copyright its original
provider and is **not** under the same terms. That is why the app displays each charger's own
`DataProvider` title and, where OCM returns one, its licence string — a single "© Open Charge
Map" line would be wrong for the imported half of the dataset.

This is compatible with the app's distribution. Attribution is a display obligation, met on the
screen that shows the chargers.

## What a query discloses, and the mitigation

An OCM query with the whole route polyline hands a second party the driver's entire trip. That
is more than the routing engine needs and much more than a charger search needs.

So the app sends a **window**, not the route: the geometry from 40 km before the planned stop
distance up to the stop distance itself, a 5 km corridor either side, capped at 10 results. The
useful charger is the last one reachable before the reserve floor, and that is the only stretch
where one is wanted. OCM learns a stretch of road somewhere in the middle of a trip; it does not
learn where the driver started or where they are going.

One query per plan, never per recompute, never on a timer.

## Quota

OCM publishes no numeric limit and instead reserves the right to ban callers making
"excessive/indiscriminate" use, at the administrator's discretion. A limit that is a human
judgement rather than a number is a limit to stay far away from, so the app self-imposes 500 a
day and 10 a minute through the same `RoutingQuota` the routing calls use. Every request follows
a driver action; there is no timer to run away.

The `User-Agent` OCM asks for is set to a static `EVChargePilot` — the same string for every
install, identifying the app and nothing about the car or the driver.

## What is still not done

Live availability stays out, as the ticket says: `StatusType` carries an "automated status" for
some networks and no free source is trustworthy enough to put a "free right now" in front of a
driver. The app shows operational-or-not and the age of the claim, which is what the data can
honestly support.
