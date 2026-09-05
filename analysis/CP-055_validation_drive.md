# CP-055 — The validation drive

Date written: 2026-09-05
Status: instrument built, drive not yet done

One drive answers eight questions. They accumulated because eleven tickets could be finished
on a laptop and verified nowhere else, and answering them one at a time would cost eight
evenings and eight parking spaces. Everything below is armed before departure and records
itself; nothing on this page asks anyone to operate a screen while the car is moving.

**Read this page alone.** It does not assume the repository has been read, and it should still
work six weeks from now.

## What you need

- The **unstable** build of EVChargePilot installed on the head unit. With the car connected,
  `mise run run-unstable` from the repository builds it, installs it and launches it; it sits
  beside the stable app rather than replacing it. The stable build contains none of this — no
  probe, no toggle, no recording — by construction.
- A **USB stick**, formatted so the head unit can write to it.
- The **routing key** and the **charger key** already saved in the app (Charging stop →
  *Routing key*). Without them the route and charger questions cannot fire.
- The **vehicle figures** saved (Charging stop → *Vehicle*): usable capacity, state of health,
  minimum charger power, reserve. The defaults are an MG4 Comfort at 100 % health.
- Trip history that already contains a few drives. Question 6 asks whether the consumption
  model can be fitted, and a model is fitted from past drives, not from this one alone.

## Before leaving, parked

1. Open the app. On the dashboard, check that **Automatic trips** is on, or start a trip by
   hand with **Start trip**. A drive nobody recorded teaches question 6 nothing.
2. Nothing to arm any more — the unstable build arms itself. Check it once anyway: **View
   diagnostics** → **Open evidence capture**, and the line under the button must read
   *Validation mode on*. If it says off, someone turned it off; press the button.
3. **Close the app completely and reopen it.** This matters: question 2 asks whether Android
   grants fine location without ever showing a prompt, and that can only be observed at the
   start of a process, before any screen of the app has asked for anything. Arming, then
   restarting, is what produces a clean reading.
4. In the **car's own navigation** — not this app — enter a destination and start guidance.
   That is what question 1 needs: the transactions being probed can only carry a destination if
   the car has one.
5. In the app, open **Charging stop**, type a destination and press **Search**, then pick a
   result. Choose somewhere far enough that the plan says a stop is needed; if the battery is
   nearly full, pick somewhere further away. A plan with no stop never searches for a charger,
   and question 5 stays blank.
6. **Press *Y aller* at the bottom of the plan.** New, and the one item on this list that
   fails visibly: either the car's navigation opens on that destination, or the screen says
   nothing accepted it. Either answer closes CP-056 — write down which one you saw.

   Note *what* it sent. Where the plan has a charging stop, the button sends **the stop**, not
   the final destination: a `geo:` destination carries no waypoints, so sending the endpoint
   would let the car pick its own road and quietly make every figure on the screen a figure
   about a different trip.

   If it worked, step 4 is already done — the car has a destination, which is all question 1
   needs.
7. Note the charge percentage and the outside temperature the dashboard shows. Two lines in a
   notebook are worth an hour of guessing later.

**Choose a destination you are willing to share.** The bundle records the names of the roads on
the route and the raw bytes of the car's own destination transactions. It leaves the car on a
USB stick. Use a town, a service area, a shop — not home.

## The drive

The shape matters more than the exact roads:

- **A long motorway leg**, 60 km or more, held at a steady speed between 110 and 130 km/h.
  Question 6 needs the charge gauge to move several times — it steps about 2 % every 4 km — and
  the model needs to have seen more than one speed across the history it is fitted from.
- **A departmental section**, 15 km or more, at 70 to 90 km/h. Question 3 asks whether the
  routing sections look like real roads, and a route made only of motorway cannot show that.
- **One planned charge stop** on the way, from step 5 above. You do not have to actually
  charge; the question is whether usable chargers come back for that stretch of road.

While driving, do nothing. The screen can be off. Every probe is already armed, and none of
them needs a tap.

## On arrival, parked

1. Open **Charging stop** once more and search the same destination again. This is what refits
   the consumption model with the drive you have just done, and that refit is question 6's
   answer.
2. Plug in the USB stick.
3. **View diagnostics** → **Export to USB** → pick the stick. Wait for the confirmation naming
   the file.
4. Take the stick out. Nothing else is needed; the app keeps nothing that has to be cleaned up.

## What lands on the stick

One zip. Inside it, `evidence/validation-SWI68-…json` is this drive's answers: eight blocks,
one per question, each naming the ticket it belongs to. Alongside it are the guidance trace,
the signal statistics and the trip record, which the export has always carried.

A block that is empty says why it is empty — either "validation mode was off" or the condition
that would have made it fire. That is deliberate: "no chargers came back" and "the search never
ran" close different tickets, and a blank would not tell them apart.

The file contains no API key, no destination text typed into the app, and no coordinate of
where the car was. It does contain road names and the destination bytes described above.

## What is being asked

| # | Ticket | Question |
| --- | --- | --- |
| 1 | CP-051 | Do the navigation service's transactions 38 and 39 carry the destination the driver already entered in the car? |
| 2 | CP-043 | Is fine location granted at startup without a prompt, because vehicle speed shares its permission group? |
| 3 | CP-049 | Do real routing steps produce sections that look like roads? |
| 4 | CP-049 | Does the routing instance return a second route when asked for alternatives? |
| 5 | CP-048 | Does Open Charge Map return usable chargers along a French motorway? |
| 6 | CP-052 | Do the charge segments the consumption model needs appear on a real drive? |
| 7 | CP-049 | Does the route what-if produce rows on this firmware, or a refusal? |
| 8 | CP-046 | Is a refused or missing location survivable from end to end? |
| 9 | CP-056 | Does anything on this head unit accept a destination from another app? |

## Afterwards

Turn the toggle off (**Disarm validation drive**) once the bundle is safely off the car. Then
the answers go back into the tickets above: each one closes against this bundle, or says what
it still does not know.
