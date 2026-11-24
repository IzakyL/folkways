## `turn`
<!-- guide:parts/turn -->

Runs a Create Hand Crank or Valve Handle for a signed number of uses.
Integer -64 to 64. Zero requests no uses. Positive is native forward use and negative reverses it. Hand Cranks
produce about 10 ticks of rotation per use, with successive uses refreshed just before they run out. Valve Handles
instead run the configured angle per activation and wait out their native cooldown between activations. A handle
will be busy if another source is already driving its network. Check its `angle` reading for the configured angle.

Use a reachable footing, write the signed count, and wait for the completed count in feedback. Check the downstream
`motion` reading separately. The count is neither RPM nor shaft revolutions. Use `forward_rpm`, the graph ratios and a
short probe to find the direction and travel in the actual build. Completion feedback retains the last count for 100 ticks,
so use fresh actuator readings before issuing an identical command.

## `motion`
<!-- guide:parts/motion -->

Reads the travel of a Create mechanical piston, rope pulley or gantry.
Read-only offset in blocks, normally 0 to the reported `maximum`. For pistons it represents extension along the
pole line, including for sticky pistons. For rope pulleys it represents paid-out rope. A gantry uses the consistent
shaft's facing and counts from its first shaft block, with either a parked or moving carriage. Multiple carriages on
the same shaft make the reading ambiguous. Mirrored rope pulleys have no Folkways motion part. An elevator pulley taken into the colony is ridden through the
elevator integration (contacts at each floor, floor selection in the cabin), not driven through this part; a
player-built lift of another kind can be declared as a facility ride.

Drive the connected rotational source, watch `position`, `moving` and the contraption position, then stop or reverse
the source and confirm the end state. Collision, zero speed and travel limits can all stop movement. Use a probe to
find the signed motion and stopping distance. Elevator floor selection belongs to the elevator integration, not this part.
The sensor takes no write and does not move the mechanism by itself.

## `level`
<!-- guide:parts/level -->

Sets the 0–15 output of a Folkways Control Hub terminal.
Integer, 0 off and 1–15 the corresponding redstone strength. Link the terminal to its hub and have the resident
occupy the hub's required seat. Write the level from that post and watch the downstream mechanism, then write
zero when done. A terminal's signal feedback is distinct from the output setting used as command
feedback. Probe each useful level for thresholds, lag and overshoot. A disconnected or unstaffed hub cannot write.
