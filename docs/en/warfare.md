# Superb Warfare

[Gameplay](gameplay.md) · [Architecture](architecture.md) · [简体中文](../zh-CN/warfare.md)

Install `folkways_warfare` beside Core, Vanilla, Superb Warfare 0.8.9.2 and Kotlin
for Forge 5.12. Modrinth's latest NeoForge 1.21.1 release was checked on
2026-10-24 at [Modrinth](https://modrinth.com/mod/superb-warfare/versions); 0.8.9.2 remains the latest compatible release. Both downloaded jars
are pinned by SHA-512 in `blockwright.toml`.

## Handheld firearms

The combat page offers **Firearm (Superb Warfare)** for an actual group. Guards
and formation patrols collect one supported gun and up to sixteen compatible
loose rounds from registered stores within thirty-two blocks. Existing colonies
must allow the gun in their saved tool permissions. Empty stores never provide
virtual ammunition.

| Gun      | Loose ammunition | Effective range |
| -------- | ---------------- | --------------- |
| Glock 17 | Handgun ammo     | 24 blocks       |
| MP5      | Handgun ammo     | 32 blocks       |
| AK-47    | Rifle ammo       | 48 blocks       |
| M4       | Rifle ammo       | 48 blocks       |
| M870     | Shotgun ammo     | 20 blocks       |
| SVD      | Sniper ammo      | 64 blocks       |

Each shot lends exactly one physical loose round to SBW's magazine transaction,
then uses `GunData.shoot`. Rounds loaded by a player stay in the magazine and do
not replace the loose-round supply requirement. Native heat and bolt state tick
on the actual item; reopening a session cannot bypass RPM or pump time. Stops,
item replacement, death, saving and unloading close the exclusive hand loan.

Physical loadouts use `LoadoutProvider.acceptsWeapon` for supply, deficits and
pack retention. Names and player-loaded rounds are preserved. Guns with perks, property overrides,
explosive or penetrating properties, custom projectiles, automatic reload, incompatible ammo or
unsupported magazine/cost components are refused. This deliberately excludes
HEBullet and incendiary effects rather than guessing their impact footprint.
Changing a held gun to an unsafe variant closes its hand loan before firing.

The target still follows Vanilla's hostility and guard leash rules. A friendly
resident, pet or player in the firing corridor prevents a shot.

## Weapon stations

Place a stationary emplacement within twelve blocks of an assigned guard slot,
in the same realm. This is the existing guard operating area, rather than a new
saved building designation. The nearest free, unlocked station is chosen; player
occupants and other resident operators retain their seats.

Automatic stations include the mortar with ordinary shells, Mk42, Mle1934 and
the stopped FH77BW howitzer with HE cannon shells. Select HE through SBW before
assigning a guard. Other fixed artillery/defence entities must use the same known
projectile configuration. AP penetration, TOW's wire guidance, cluster,
incendiary and custom projectiles are refused; players can operate them themselves. Tanks, aircraft,
boats and driving are excluded. The FH77BW is used only at rest. A mortar has no
seat in SBW; its guard walks beside it. Other stations use their turret's actual
seat, including the howitzer's second seat.

The guard collects two physical shells through normal patrol supply
trips. The selected SBW ammo consumer supplies an exact component-bearing
`ItemSpec`; different shell components cannot substitute. Ammunition moves into
the station inventory and reloads with SBW's timers. Native vehicle APIs
handle turret limits, velocity, gravity, cooldown and firing. Emplacement targets
must also be within the weapon's configured range and projectile lifetime.

Before firing, the operator traces the trajectory and predicted impact, including
spread. Entity safety uses twice SBW's configured explosion radius; block safety
uses the pinned version's maximum flattened block radius (0.6 times the configured
radius), each with the spread allowance. Friendly living entities and registered
colony blocks or stores in those regions prevent firing. Unknown or unloaded
trajectories are refused.

Automatic shell fire requires the mob-griefing permission hook. Actual
resident projectile explosions recheck the rule and impact area before damage,
then post the cancellable NeoForge `ExplosionEvent.Start` before SBW explodes.
Cancelled explosions cannot damage entities or blocks. Each native destruction
tier passes its block candidates through `ExplosionEvent.Detonate` before mutation,
so protection handlers can remove blocks. These patches apply to resident shots;
player-operated SBW weapons keep their own behavior.

The same labour runner owns walking, supply collection and operation. Retreat,
replacement orders, release, revoked patrolling permission, low health, death,
unload and station removal terminate the duty and dismount its seat. A pending
mortar shell remains in the station when its delayed discharge is cancelled.
Queued SBW vehicle shots recheck the current duty and safety at discharge.
Already discharged projectiles continue under SBW's physics; their explosions
still pass the impact protection checks.

## Extension contracts

Vanilla contains no SBW classes. A sibling gun integration registers through:

```java
Weapons.register(adapterId, adapter);
LoadoutRegistry.register(loadoutId, provider);
```

`loadoutId` is a stable namespaced identifier. `LoadoutProvider` supplies the
weapon spec, ammunition amounts, validated options, inference and UI choices.
The combat page reads `LoadoutRegistry.entries()`; each SBW firearm is one choice
under `folkways:warfare/firearm`. Options retain the selected gun. The provider's
`loaded(stack, ammunition)` can count physical loaded rounds towards stock; SBW
keeps player-loaded rounds separate. `WeaponSession.continuousEngagement()` keeps
the same exclusive hand loan between shots.

`PatrolSupplies.registerSupply(id, (colony, body) -> amounts)` adds other duty
equipment to the same reservation-aware trips and pack retention. An existing
GUARD `Order` can own a native seat with `mount(entity)` and release it with
`dismount()`. Replacing or closing that order releases only its owned mount.

Loadout saves now contain version two and namespaced IDs. Version-one
`MELEE/BOW/CROSSBOW` rows explicitly migrate. Unknown extension IDs are retained
while their mod is disabled. Orders and firing intents remain transient. A
version-one `folkways:command_mount` marker on the resident records the owned
vehicle and order UUIDs. Once restored passengers tick, a marker without its live
order releases only the recorded vehicle, then is removed. Saves without the
new marker keep their ordinary riding behavior. The marker grants no restored
command authority.

## Verification

```sh
npm run setup
./gradlew --no-daemon build
./gradlew --no-daemon runGameTestServer
./gradlew --no-daemon -PwarfareTests=true -PgameTestMod=warfare runGameTestServer
./gradlew --no-daemon -PwarfareTests=true runGameTestServer
npm run test:i18n
```

The focused suite covers warehouse-to-gun supply, physical shot counts, empty
ammo, preserved magazines, native bolt timing, station detection, travel,
mount/aim/fire/retreat, component-exact shell supply, the expanded HE damage radius,
unsafe loadout variants, AP/TOW refusal, explosion permission/cancellation and
restoring a running native vehicle/passenger snapshot.
