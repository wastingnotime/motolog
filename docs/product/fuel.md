# Refueling and owner odometer

The offline fuel flow implements the accepted [fuel decisions](decisions.md#fuel-odometer-and-freshness).
Open **Refuel and owner odometer** from the registered-bike checklist. Use the
large **New refuel** and **Update odometer** controls while safely stopped.
There is no GPS-derived motorcycle odometer.

## Entry and decimal facts

Both modes save the same immutable `FuelEvent`, linked atomically to a new
confirmed `OWNER` odometer observation:

| Mode | Entered decimal operands | Derived fact |
| --- | --- | --- |
| Liters + total paid | Liters, total BRL | BRL/liter = total / liters |
| Total paid + price per liter | Total BRL, BRL/liter | Liters = total / price |

The existing schema stores the normalized exact operands and mode. Changing
mode clears its quantity and requires review. Every form edit invalidates the
reviewed values until reviewed again. Numeric-field Done hides the keyboard and
moves focus to the existing Review button; it never invokes Review or saves.
The read-only history distinguishes total
paid from price per liter, shows the recorded motorcycle odometer and observation,
and exposes the exact normalized operands, mode, receipt timestamp and derivation.

No binary floating-point money is used. Total accepts at most two decimal places;
liters and unit price accept at most three. Inputs must be positive and finite,
with at most 12 integral digits; signs, exponents, commas and excess precision are
rejected. Whole owner kilometers allow zero and at most 12 digits. Derived values
use decimal scale 12, `HALF_UP`. A derived value rounding to zero at that scale or
requiring more than 12 integral digits is rejected with feedback before saving.
Display uses en-US decimal notation, an explicit BRL label, two places for total
and three for liters/unit price. Display rounding never becomes an operand.
For example, BRL 100 / BRL 6 per liter retains `100` and `6` and derives
`16.666666666667`, displayed as `16.667 L`.

## Observation and retry rules

Forms collect local date/time, IANA zone and explicit UTC offset, defaulting to
the device observation time. Zone/offset agreement is validated; nonexistent DST
times are rejected, and overlap times require a matching explicit offset. Future
observations are rejected. Backdating is allowed only when the value agrees with
both chronological confirmed neighbors, including equal-time peers. Equal values
are allowed. Rejected evidence is never silently repaired; local setup remains
available for privately reviewed uncertain history.

The form retains its operation ID, operands and observation across saved-instance
restoration. `FuelRepository` fingerprints normalized owner intent independently
of receipt time. Identical concurrent submissions and later retries commit once;
changed intent under an already committed ID conflicts. Failure rolls back the
event, reading and operation receipt together. A standalone reading follows the
same authoritative observation and retry rules without creating a refuel.

## Distance projection

`FuelProjection` recomputes from immutable occurred-at evidence, so backdated
insertion cannot change which event is latest. Ties use a stable descending event
ID for presentation and explicitly report ambiguity. A newer refuel with missing
or uncertain linked evidence blocks the result; it never presents an older event
as the latest. The last recorded refuel odometer remains separately visible.

Kilometers since refuel is current confirmed owner km minus latest refuel km only
when the reading is at/after the refuel, the sequence is nondecreasing and
unambiguous, and the current reading is no more than 24 hours old. Exactly 24 hours
is fresh; one millisecond beyond is stale. The fuel screen shows observed-at and
age and reevaluates freshness every second while open. Missing, future, stale,
ambiguous and unusable evidence have explicit unavailable reasons and an
**Update odometer** path. Uncertain/unknown history cannot advance current mileage.
GPS samples are absent from the projection's inputs and cannot fill missing data.

## Verification

`FuelValuesTest` covers equivalent modes, repeating division, range/precision,
nonpositive values, odometer bounds, future observations and DST gaps/overlaps.
`FuelProjectionTest` covers occurred ordering, stable ties, missing/uncertain
evidence, inconsistent sequences and the exact freshness boundary.
`FuelRepositoryTest` exercises Room atomic rollback, backdates against both
neighbors, equal readings, concurrent retry, operation conflict, database reopen
and fresh GPS alongside a stale owner observation. `FuelFlowTest` covers both
forms, review invalidation, saved-state restoration, a fresh reading and read-only
history. `FuelRestartProbeTest` supports separate seed/verify instrumentation
processes with synthetic data. Device matrix and physical field acceptance remain
separate from this slice.
