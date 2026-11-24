## `destination`
<!-- guide:parts/destination -->

Walks a powered Let it Walk! hull to a world position using its pilot station.
Real vector `[x,y,z]`. X and Z accept ±30,000,000, Y ±4096. The steering loop treats a horizontal distance of 1 block or less as arrival. Y does not command altitude. Assemble the walker, link the station to its core, power it, and write `park=False` at the
core if parked. Submit the destination and let the until-read control continue steering until arrival.
Folkways refreshes native pilot input, turns before walking on longer approaches and can crab during final alignment.
Read the core's world `position` and `heading`, and check power and parking if motion stops. The Sable hull and
its legs need traversable ground and room to turn. This control does not plan a route around obstacles. Sable supplies the moving coordinate frame. Use a structure UUID
for discovery and keep local control positions separate from the world-space destination. Sable itself supplies no
generic propulsion or direct pose write through facility controls.

## `park`
<!-- guide:parts/park -->

Sets native parking on the Let it Walk! walker core.
Boolean. `True` parks the hull, letting it settle on its legs and suppressing pilot movement. `False` releases
parking. Read back `parked`, then check actual pose and movement separately. Write false before driving or
adjusting posture, and true when the walker is in position and should settle. Parking neither assembles nor
powers the walker.

## `height`
<!-- guide:parts/height -->

Sets the Let it Walk! hull's ride-height offset.
Real blocks, from -0.6 to +0.15 times the shortest configured standing height of its walking legs.
Zero is the reference height, negative lowers, positive raises. Use the linked pilot station on a powered,
unparked walker. Folkways holds native raise/lower inputs until within 0.05 blocks of the target, so compare
feedback to the target with a tolerance. Read the actual offset before starting another posture correction.
A walker with no standing leg height has a zero range. Changes in height are relative to the gait, not world Y.

## `lean`
<!-- guide:parts/lean -->

Sets the Let it Walk! hull's roll and pitch lean targets.
Real vector `[roll,pitch]` in radians. Each runs from -25° to +25° (about ±0.43633 rad).
Use the linked pilot station while powered and unparked. Write the target and wait for the observed lean to settle
before relying on the pose. The native core smooths the actual lean toward the target. Folkways keeps nonzero
targets refreshed until replaced, cleared or interrupted. Write `[0,0]` to release to neutral. Ground and feet can
restrict the measured hull pose even when the lean reading reaches the target.

## `limb/<n>`
<!-- guide:parts/limb/<n> -->

Selects and aims one numbered Let it Walk! limb in its native control plane.
Real vector `[forward,up]`, each -1 to 1. Limbs start at 1 in pilot order and are available according to the
assembled joints. The native aim is constrained to a ring, about 0.263 to 1 in radius, so values near the center
or beyond the outer circle may be projected. Command one limb from the linked station, then watch `aim` for the
held target and `x,y,z` or `local` for the actual tip. `arm` distinguishes arms from legs, and `axis` gives the
final link's world direction. The adapter uses one native manual limb selection per core. Let it settle and
release manual aim before resuming normal gait. The internal command feedback uses an empty vector for release
(e.g. when a probe restores the input); the advertised write type is the two-axis target. Joint limits can prevent
the tip reaching the requested aim.

## `key/use`
<!-- guide:parts/key/use -->

Pulses the configured Let it Walk! pilot station use key.
Pulse with no argument. Set the use key's frequency pair on the native pilot station first and use a matching
Create Redstone Link receiver in the mechanism. At least one frequency must be configured for discovery. Write once
to trigger Create's 30-tick linked-controller press and let it expire before needing a fresh edge. Observe the receiver
or the driven mechanism to confirm progress. The key triggers the wiring associated with that pair and has no
separate Folkways latch, release, or action-specific feedback.

## `key/attack`
<!-- guide:parts/key/attack -->

Pulses the configured Let it Walk! pilot station attack key.
Pulse with no argument. Set the attack key's frequency pair on the native pilot station first and use a matching
Create Redstone Link receiver in the mechanism. At least one frequency must be configured for discovery. Write once
to trigger Create's 30-tick linked-controller press and let it expire before needing a fresh edge. Observe the receiver
or the driven mechanism to confirm progress. The key triggers the wiring associated with that pair and has no
separate Folkways latch, release, or action-specific feedback.

## `key/drop`
<!-- guide:parts/key/drop -->

Pulses the configured Let it Walk! pilot station drop key.
Pulse with no argument. Set the drop key's frequency pair on the native pilot station first and use a matching
Create Redstone Link receiver in the mechanism. At least one frequency must be configured for discovery. Write once
to trigger Create's 30-tick linked-controller press and let it expire before needing a fresh edge. Observe the receiver
or the driven mechanism to confirm progress. The key triggers the wiring associated with that pair and has no
separate Folkways latch, release, or action-specific feedback.

## `key/inventory`
<!-- guide:parts/key/inventory -->

Pulses the configured Let it Walk! pilot station inventory key.
Pulse with no argument. Set the inventory key's frequency pair on the native pilot station first and use a matching
Create Redstone Link receiver in the mechanism. At least one frequency must be configured for discovery. Write once
to trigger Create's 30-tick linked-controller press and let it expire before needing a fresh edge. Observe the receiver
or the driven mechanism to confirm progress. The key triggers the wiring associated with that pair and has no
separate Folkways latch, release, or action-specific feedback.
