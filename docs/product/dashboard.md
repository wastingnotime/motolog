# Dashboard and parked management

The registered motorcycle opens on the offline dashboard. Large labeled Start,
Stop and Quick refuel controls are for use only while safely stopped. Recording,
GPS-gap and interrupted states use committed ride state. Automatic mode defaults off; ride controls expose explicit Arm/Disarm and manual
ownership takeover. Armed mode remains visible in parked flows and the notification;
see the [automatic lifecycle](rides.md). Motion-based lockout
is absent under the accepted V1 decision. Stop also stays available above parked
screens and through the recording notification, independently of GPS fixes.

Normal-size text uses a compact two-column grid for the four core statistics.
Enlarged text uses a single scrolling column with no fixed text heights. Quick
actions retain 64 dp minimum targets and matching typography. System-bar icon
appearance follows the actual app surface brightness.

Quick refuel opens directly into the two existing fuel modes. Numeric fields expose
a keyboard Done action that releases focus; the form reserves keyboard space so
Review and Save can be scrolled into the visible area. Enter total paid,
liters or unit price, and the motorcycle's owner odometer; review and save returns
to the dashboard. Observed-now is the default, frozen at review so a retry uses the
same observation and operation identity. Optional native date/time pickers support
past observations without requiring a typed timestamp. Registration and standalone
owner readings use the same friendly observation control. Entered operands,
chronological validation and atomic event/reading commits retain the fuel rules.

Today's GPS distance and average use persisted local-day distance and eligible
time, including measured stops and excluding gaps. No eligible pair/time is
unavailable; a measured stopped pair can produce zero. Kilometers since refuel,
last-refuel odometer, observation time and reading age remain separate owner
odometer facts. Missing, stale, future and ambiguous fuel evidence retain explicit
unavailable explanations. GPS cannot provide a missing owner reading.

Maintenance and pending work, ride history, fuel history/owner reading, motorcycle
registration/checklist and settings are parked management routes. Navigation never
ends or resets recording. Current mileage is unknown after 24 hours: historical
as-of mileage can establish that a target was already reached, but an older
below-threshold reading cannot establish current not-due. Calendar due branches
remain independent. Maintenance shows the owner observation time and age, and
manual coverage gaps remain visible. Original checklist provenance is summarized
on rows with edition and full source metadata available under Source details.

## Local query and history boundaries

Dashboard refresh reads the selected local-day aggregates and indexed recent ride
metadata; management reads exclude ride evidence entirely. Fuel reads are scoped
to the registered bike. Registration edits, maintenance projections and commands
retain transactional validation without loading raw GPS history. Parked routes
stop dashboard polling; the small persistent recording status still refreshes.

Ride history pages twenty sessions. A selected ride reads its persisted, versioned
`RideTotal`, eligible duration, average, gap/rejected-fix counts and optional source
details. Speed events use a sequence cursor and gaps page twenty rows per selected
ride, using ride indexes. No screen materializes all historical GPS samples.
Schema 5 adds per-ride totals updated atomically with capture/daily projections.
Its one-time migration streams existing supported-version evidence, excluding
saved gaps and rejected-pair boundaries; unknown algorithms remain unavailable.
Raw evidence and operation receipts are preserved.

Connected checks use synthetic offline records for unavailable/fresh/stale/future
fuel states, deterministic GPS metrics, Start/Stop/recovery, recording-preserving
navigation, history paging, source details and migration. Normal and enlarged-text
emulator review is separate from physical phone touch, GNSS, battery and OEM
background validation; those field checks remain owner-controlled.
