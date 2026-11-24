## `aim`
<!-- guide:parts/aim -->

Sets a supported Create Big Cannons mount's yaw and pitch.
Real vector `[yaw, pitch]` in degrees. Yaw accepts -360 to 360 and pitch accepts the displayed limits from
maximum depression to maximum elevation. An unassembled mount can advertise the part with pitch limited to zero.
Power the native assembly input and confirm `assembled`. Removing assembly power disassembles the mount.
Connect a Create Hand Crank to each shaft that needs to move, with reduction gearing for heavier cannons.
Write the angles and check `folkways_core:cbc/aim` or graph yaw and pitch. The mount uses CBC's native angle
limit for the last fraction of a tick. A shaft driven by something else reports busy. The cannon must be
unstalled and able to be turned by the mount.

The part turns whatever the mount carries: a big cannon, an autocannon or a drop mortar. An autocannon with a
handle is aimed by its seated gunner, so its mount has no `aim`. The adapter targets the ordinary Cannon Mount
block entity. Fixed Cannon Mounts and Cannon Carriages have no such part.

On a big cannon the part also publishes `<control>.ballistics` for `@std//ballistics` (see `guide:facilities`): the
pivot, muzzle distance, mount frame, aim limits, `loaded` and the flight of what the bore holds, the bore cell by
cell (`item`, `cell`, and a Big Cartridge's `power` and `spent`), `tube`, `chambers`, `max_stress`, `ram_strength`,
`ram_reach`, whether CBC's squib failure is on, and `munitions`: per item, a projectile's own charge power, gravity,
retention, size and blast, a Powder Charge's power and stress, and a Big Cartridge's power and stress per level of its
`createbigcannons:power` component.

## `breech`
<!-- guide:parts/breech -->

Loads an assembled big cannon through its quick-firing breech, one munition per deposit, as a player's hand does.
Item port on a horizontally assembled big cannon with a quick-firing breech. Deposit one projectile, then each
Powder Charge or Big Cartridge in turn. The first deposit opens the breech if needed and waits for CBC's
`quickfiringBreechOpeningCooldown` (default 5 ticks each way). Opening ejects a spent Big Cartridge, as the native
lever does; the port will not open over a live load. Each later deposit pushes the load one cell deeper. The breech
moves a single munition itself; a longer column is rammed as with a Ram Rod, which moves at most `ramRodStrength`
munitions (default 3) and then waits `loadingToolCooldown` (default 20 ticks), so the deposit reports busy until it
may ram again. A hand load therefore ends at `ramRodStrength + 1` blocks.

A Big Cartridge holds its powder in the `createbigcannons:power` data component (1 to 4 by default); request a filled
one with that component, since an empty one cannot ignite. A fired cartridge stays in the first chamber, spent. The next
deposit opens the breech, which ejects it as an item; if the breech is already open over a spent case, the port shuts
it and opens it again, as a hand would.

The port refuses, with a reason, what CBC would not fire safely: a charge before the projectile, a second projectile,
a charge nearest the breech that cannot ignite, a propellant combination CBC rejects (a Big Cartridge must sit
nearest the breech), propellant in a cell that is not a chamber, or total propellant stress above
`getMaxSafeCharges` (the lower of the material's and the breech's strength; every quick-firing breech is rated 2, so
two Powder Charges unless a datapack raises it). Read the reason from `folkways_core:cbc/refusal`. Read the load from
`folkways_core:cbc/charges` (charges ahead of the projectile), `folkways_core:cbc/projectile` (its block ID),
`folkways_core:cbc/stress` (`[stress, safe limit]`) and `folkways_core:cbc/loaded`. A routine can await
`charges` after each deposit. The port lists the bore in loading order and cannot take anything out.

Other ways CBC loads a big cannon:

- A sliding or screw breech opens and closes only under rotation, and a Cannon Loader rams munitions down a tube.
  Neither is a hand action, so Folkways has no part for them: operate the player's own loading structure. Find it
  with `mechanism()`, then calibrate each control with `probe()`: which lever or clutch opens and closes the breech,
  and which gearshift or clutch extends and retracts the rammer. Write a Starlark routine that sequences them, then
  confirm the load with `charges`, `projectile` and `loaded` once the mount is assembled again (see
  `guide:facilities`).
- A player can also push munitions in at the muzzle and ram them with a Ram Rod: use the `muzzle` and `ram`
  parts.

## `muzzle`
<!-- guide:parts/muzzle -->

Pushes a munition into the muzzle of an assembled big cannon, as a player's hand does.
Item port on a horizontally assembled big cannon whose last tube cell is open and unconnected at the muzzle, whatever
its breech. One munition per deposit goes into the muzzle cell, facing along the bore, and only while that cell is
empty: ram it down with `ram` before the next. Load the propellant first and the projectile last. The port refuses,
with a reason in `folkways_core:cbc/refusal`, a munition that could not reach the load (a Ram Rod reaches
`ramRodReach` cells, default 5, so a tube longer than `ramRodReach + 1` cannot be loaded from the muzzle), a load with
a projectile already in the bore or a gap partway down, a spent cartridge in the chamber (open the breech to eject it),
and any seated load CBC would not fire safely, by the same rules as `breech`. The port lists the bore from the first
chamber outward and cannot take anything out.

## `ram`
<!-- guide:parts/ram -->

One stroke of a Ram Rod into the muzzle of an assembled big cannon.
Pulse on the same cannons as `muzzle`. A stroke finds the first munition within `ramRodReach` cells of the muzzle and
pushes the column behind it one cell deeper, at most `ramRodStrength` munitions and only into open tube; a column
already against the breech does not move. A stroke takes `loadingToolCooldown` ticks (default 20). One that moves
nothing is refused, with the reason in `folkways_core:cbc/refusal`. A munition pushed into the muzzle needs as many
strokes as cells lie between the muzzle and the top of the load; `@std//ballistics` counts them.

## `ammo`
<!-- guide:parts/ammo -->

Feeds a mounted autocannon or drop mortar the way a player's hand does.
Item port. On an autocannon it is the breech's ammo container slot: deposit one filled Autocannon Ammo Container
while the slot is empty, and draw the container out at any time, spent or not. Swap a container by drawing the old
one and depositing the new one. `folkways_core:cbc/ammo` counts the rounds left in the container and the breech's
queue; `folkways_core:cbc/loaded` is true while any remain.

On a drop mortar it is the muzzle: deposit one round that CBC accepts as a `DropMortarMunition`. CBC requires an
empty barrel and no round already falling. The mortar launches the round itself after `dropMortarDelay` (default 5
ticks); `loaded` is true while it falls. The same resident must then wait `dropMortarItemCooldown` (default 40 ticks)
before dropping the same kind of round again, so a deposit reports busy until then. A drop mortar has no `fire` part.

Read refusals from `folkways_core:cbc/refusal`. Neither weapon reports `ballistics`.

## `fire`
<!-- guide:parts/fire -->

Fires the cannon on a mount: a pulse on a big cannon, a level on an autocannon.
On a big cannon it is a pulse with no argument. It requires a horizontal assembled big cannon with an ignitable
charge at the start of a contiguous load followed by a projectile. Verify `assembled`, `loaded` and aim first. The
adapter starts closing a quick-firing breech if open and waits for opening progress to reach zero. It then invokes
the native powered input once. Read `loaded` again before reloading or retrying.
Sliding and screw breeches must be closed by the player's structure first.

On an autocannon it takes a whole level from 0 to 15, the redstone power the mount would see. Above zero the breech
keeps firing from its ammunition at CBC's cadence for that level (level 1 waits 120 ticks between rounds, level 15
waits 4); zero stops it. `folkways_core:cbc/fire_rate` reads the level back. A redstone change at the mount sets the
level again from its own signal.
