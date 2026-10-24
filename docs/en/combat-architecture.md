# Vanilla combat, resident commands and formations

[Architecture](architecture.md) · [简体中文](../zh-CN/combat-architecture.md)

This increment continues draft e40ebc7 and incorporates master 506eec3. It provides configurable hostility, defence and escape, server-side orders, groups of up to eight people, formation patrols and real melee, bow and crossbow sessions. War and gun integrations remain separate future modules.

## Module ownership

| Module                            | Responsibility                                                                                       |
| --------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Core                              | Residents, labour, item ownership, lifecycle, general travel and exclusive execution                 |
| Vanilla                           | Everyday hostility, defence, patrol, orders, actual groups, placement, movement and weapon contracts |
| War, future                       | Raids, PvE/PvP, strategic AI, RTS UI, box selection, dragged lines and numbered shortcut selections  |
| Separate gun integrations, future | Native TaCZ or Point Blank execution through Vanilla contracts                                       |

Core and Vanilla require neither War nor gun mods. Create, Cookery and Modular Golems retain independent extensions.

## P0/P1: hostility and defence

NativeHostility only installs lightweight goals in the server EntityJoinLevelEvent. World queries happen during normal AI ticks, avoiding queries before the joining chunk reaches FULL.

- Enemy implementations receive one lightweight resident goal. Only types in `folkways:resident_enemies` scan for residents; priority is at least 6 and follows every native target present at installation. Native AI still executes movement, melee, archery and creeper explosions.
- NeutralMob is excluded, including zombified piglins that inherit from Zombie. Endermen retain native neutrality.
- Bodies.of and colony enrollment identify targets, including other registered ResidentKinds. Disenrollment, death and removal end pursuit.
- Repeated joins do not add duplicate goals; newly loaded NBT entities install their own. Tests cover acquisition, pursuit, damage, both monster reloads and native villager targeting.
- Threats.hostile is shared by patrol acquisition and strikes, respecting alliances and neutral anger. Strikes recheck hostility and sight.
- Defaults include native zombie/skeleton variants, spiders, cave spiders and creepers. Spiders only acquire residents below the native brightness threshold of 0.5; patrols leave bright, unprovoked spiders alone. Existing fights disengage through vanilla attack AI.
- Armed residents off duty may defend against their monster attacker for 100 ticks within 12 blocks. Fear outranks defence at 35% health or less. Patrols reuse StrikeNode.
- Unarmed people use damage/fire fear and continue evading a pursuing attacker. Useful separation guides safe points, retained until arrival, failure or 120 ticks old. A failed random sample cannot withdraw an ongoing escape.

### Inheritable hostility tags

Vanilla's `Enemy` is a Java marker interface; NeoForge 1.21.1 has no common entity tag covering the same hostility policy. Patrol retains automatic recognition of Enemy implementations. Data packs can extend or exclude types through these entity tags:

| Tag                                 | Meaning                                                               |
| ----------------------------------- | --------------------------------------------------------------------- |
| `folkways:resident_enemies`         | Opt existing native Enemy attack AI into proactive resident targeting |
| `folkways:undead_resident_enemies`  | Nested list of native zombie and skeleton variants                    |
| `folkways:patrol_targets`           | Additional patrol candidates; includes resident_enemies by default    |
| `folkways:ignored_resident_enemies` | Exclude Folkways targeting and proactive patrol attacks first         |

A data pack can append `data/folkways/tags/entity_type/resident_enemies.json`:

```json
{
  "values": [
    "#my_pack:hostile_undead",
    { "id": "my_mod:monster", "required": false }
  ]
}
```

Tags may include other tags; `replace: true` replaces inherited values. Acquisition and continuation consult current tags, so `/reload` affects already loaded monsters without adding duplicate goals. Neutral state, colony identity and alliances still apply. Tags do not invent an absent attack AI, disable native retaliation or cancel an already burning creeper fuse.

## P2: individual orders and returning to work

The public Vanilla service is `plugins.combat.command.ResidentCommands`:

```java
var commands = ResidentCommands.of(colony);
var order = commands.hold(body, destination);
order.close();
```

| API         | Behaviour                                                              |
| ----------- | ---------------------------------------------------------------------- |
| move        | Reports ARRIVED on arrival and releases control automatically          |
| hold        | Holds until release, optionally facing a direction                     |
| retreat     | Uses higher priority and 1.3 travel pace, then holds                   |
| guard       | Retains a slot, yields to combat/fear, then returns through the runner |
| Order.close | Releases only that handle; an old handle cannot cancel its replacement |
| current     | Reads the active order and its MOVING/HOLDING state                    |
| validate    | Checks without takeover, for validating a complete group first         |

Trusted server-thread callers must supply actually enrolled, loaded, unmounted PersonBodies and loaded destinations within 128 blocks in the same realm. There is no player network endpoint. Future endpoints must validate book authority, colony membership and target bounds. Commands for other ResidentKinds require separate validation.

Orders reuse Core's Urge → ColonyLabor → BodyRunner safety lifecycle. The old node releases its tool presentation and travel before the same runner takes the order. Release resumes the latest plan instead of replaying withdrawn work. Core only adds cleanup of expired temporary urge backoffs and the general Node.directTravel() option. Direct travel uses the resident's existing Locomotion; ordinary labour keeps its multimodal journey planning.

Orders are transient. Death, unloading, disenrollment and colony closure release them. Movement that has not completed after 1200 ticks reports FAILED and releases control. Holds and completed retreats wait for explicit release and may suppress everyday needs. The general `Node.staysAtWorkSite()` option has the same runner restore a stance after displacement. Terminal or saved handles confer no control.

## P3: actual UnitGroups and deployment intents

UnitGroups.of(colony).create(people) creates an actual group of one to eight people; ofResident(id) reads exclusive membership. Transfers release the previous group's control. Group identity and member order persist in the colony ledger. Deployments are transient, so groups reload as IDLE.

```java
var group = UnitGroups.of(colony).create(people);
group.move(new Deployment(left, right, front, new Deployment.Formation(2)));
```

Deployment accepts level endpoints in the same realm, a horizontal front approximately perpendicular to the line, and spacing between two and eight blocks. It currently supplies one rank with at most eight distinct block positions. Future War code can submit the intent without steering each entity.

- move sends members into their target column in sequence, then restores the line, preventing followers from blocking leaders. If column slots have no footing, it tries the line directly.
- hold establishes a line; gather assembles around a centre; release relinquishes all member orders.
- Individual replacement orders or releases detach that member. Old formation handles cannot overwrite a new individual order.
- Dead or dismissed members leave; unloaded members remain missing while present members reform; returning members receive fresh slots.
- Unreachable deployments report FAILED and release everyone. Fresh deployment after opening a path can retry without teleportation or endless retries.

The concrete acceptance case is four people physically crossing a one-block opening and restoring a line. This does not guarantee all bottlenecks, arbitrary terrain, multiple ranks, vehicles or ship deployment. The separate 16/32/64-total-entity combat sample does not establish large-group formation performance. War shortcut selections may overlap; actual group membership cannot.

### Formation patrol and proactive combat

`group.patrol(List<Deployment>)` cycles through 2–16 same-realm deployments using column entry and line restoration. `patrolRoute()`, `patrolStop()` and `phase()` expose progress; the route and engagement state are transient.

- GUARD weight is 1.5, healthy combat 2.5, fear 2 and explicit retreat 4. All use the same BodyRunner.
- Guards with usable weapons attack without being hit first. Both targets and combat stances remain within 12 blocks of the assigned slot. Ranged guards prefer firing from existing footing with sight and range.
- Healthy guards renew a short engagement lease; ENGAGING pauses route progress and travel timeout. Wounded combat does not renew it, allowing fear to preempt.
- After combat, present members return to their original slots and assemble for 20 ticks before advancing. Existing death, missing-member, return and individual-order rules apply.
- All stops validate before replacing control. Unreachable movement releases as FAILED; a fully unloaded group waits and reforms at its current stop when members return.

## P4: real native weapon sessions

`plugins.combat.weapon` publishes `WeaponAdapter`, `WeaponSession` and `Weapons`. `Weapons.register(id, adapter)` registers independent native implementations during setup and rejects duplicate IDs. Defaults implement real melee, bows and crossbows; no gun stubs are included.

| API / state                | Behaviour                                                                                               |
| -------------------------- | ------------------------------------------------------------------------------------------------------- |
| accepts / usable / range   | Identify equipment, real compatible ammunition or a loaded crossbow, and effective range                |
| open                       | Move one actual stack from Pack into the exclusive main hand, owned and ticked by the current work node |
| READY / FIRING / RELOADING | Tick native melee, bow draw and crossbow loading/firing; recheck hostility, sight, range and lifecycle  |
| stop / STOPPING            | Terminal rejection of new shots and cancellation of charging without discharging a bow                  |
| close / CLOSED             | Idempotently return the actual stack; overflow drops once                                               |

Work presentation copies and real equipment have distinct ownership. Bows reuse native ProjectileWeaponItem draw/shoot; crossbows use releaseUsing/performShooting, consuming real arrows, preserving charged components and changing durability. Empty ranged equipment falls back to an available melee weapon. Native stop completes synchronously; existing projectiles keep flying.

Entity serializes attachments before addAdditionalSaveData, so ResidentEntity closes and returns the weapon at the saveWithoutId entry before Pack is serialized. Unloading, disenrollment, death and colony revocation terminate the session. Loaded crossbow components persist on the actual item.

Native equipment sessions currently support ResidentEntity. Other ResidentKinds retain their original ToolUse melee and declared barehanded combat, including while carrying weapons; actual hand sessions need their owning extension. TaCZ, Point Blank and asynchronous gun actions need actual adapters and separate validation. Core Equipment continues owning armour.

## Validation entry points

```sh
./gradlew check
./gradlew -PgameTestMod=vanilla runGameTestServer
./gradlew -PcookeryTests=true -PgameTestMod=vanilla runGameTestServer
./gradlew -PgameTestMod=core runGameTestServer
npm run test:unit
npm run typecheck
npm run test:e2e -- tests/e2e/native-hostility.spec.ts tests/e2e/resident-commands.spec.ts tests/e2e/combat-hostility-policy.spec.ts tests/e2e/formation-patrol.spec.ts tests/e2e/weapon-sessions.spec.ts --workers=1
```

GameTestServer disables global labour simulation. LaborFixture explicitly advances real labour/travel only for each test's own colony, without changing game runtime behaviour. Client scenarios cover native damage, fear takeover, orders, narrow formations and a saved-world restart.

With `FOLKWAYS_COMBAT_BENCH=1`, opt-in `combat-performance.spec.ts` records 16/32/64 total entities, half residents and half zombies, after 200 warm-up ticks with 100 sampled ticks. Residents receive real swords and GUARD orders, and both sides have 1024 health to retain the population during combat. Artifacts contain paired engine and full Pre-to-Post tick durations plus actual target/navigation counts. They impose no invented performance threshold and do not cover arbitrary terrain or mod combinations.

See [Testing](testing.md) for platform, EULA, dependencies, CI matrix and logs. Local Windows SDK evidence and GitHub Linux CI are recorded separately. Unrun scenarios are not passes.
