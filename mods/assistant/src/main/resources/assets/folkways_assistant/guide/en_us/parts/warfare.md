## `aim/<uuid>`
<!-- guide:parts/aim/<uuid> -->

Sets a Superb Warfare fixed emplacement's aim in native yaw and pitch.
Real vector `[yaw,pitch]` in degrees. Yaw -360 to 360, pitch -90 to 90, with negative pitch looking up.
The native turret or mortar may have narrower travel. The adapter covers fixed artillery and defense turrets
and the FH-77. Use the assigned gunner position for a turret or a reachable footing beside the mortar.
Write aim while stationary, intact and unlocked, then wait for the actual direction in feedback. The mortar
changes target angles and then moves toward them. Artillery updates its aim vector. A blocked or occupied gun
can refuse the write. Guns on mobile vehicles are not facility parts: their crew aims and fires them under the
vehicle profile (seats, stock and weapon rules) and RTS commands.

## `fire/<uuid>`
<!-- guide:parts/fire/<uuid> -->

Presses the native trigger of a Superb Warfare fixed turret or artillery piece.
Pulse with no argument. Start from a stationary, unlocked, crewed emplacement. Stock its compatible native
backup ammunition through `ammo/<uuid>` and use `aim/<uuid>` to settle the aim. A press may start native
reload and return busy. Wait for reload to settle and for the native shot to be queued and emitted. The shot
can be refused if the gunner leaves before it emits. Check native gun state before another
press. There is no shared reload duration across weapons. Backpack-fed reload is outside this control; mobile
vehicle guns are run by their crew under the vehicle profile. For the mortar see `breech/<uuid>` instead.

## `ammo/<uuid>`
<!-- guide:parts/ammo/<uuid> -->

Transfers stacks through a Superb Warfare vehicle's native ammunition container.
Item port. Supply compatible backup ammunition from beside the hull. Each deposit or draw is limited by the
native slot checks, stack size and stock. Inspect actual receipts. A successful deposit replenishes spare stock
and leaves chambering to the gun's native reload. A non-mortar vehicle with container slots can expose this part
even if it has no Folkways aim or fire signal. Mortars use their single-round breech part.

## `breech/<uuid>`
<!-- guide:parts/breech/<uuid> -->

Loads a Superb Warfare mortar and starts its native countdown.
Item port, one compatible `MortarShellItem` per successful transfer. From beside the intact, stationary,
unlocked mortar, set aim and wait for its feedback before depositing. Deposit requires an empty tube and zero
fire timer, and starts the native launch with the loader as shooter. The timer starts at 25 ticks and
the native launch check occurs at 5 ticks remaining. There is no separate
trigger pulse. Wait for the native timer to return to zero and the tube to clear before another deposit.
A draw can only recover a matching shell if the timer is already zero. This part does not have a dedicated timer
reading in discovery. Use the transfer result and native state.
