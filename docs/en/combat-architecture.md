# Vanilla combat, resident commands and formations

[Architecture](architecture.md) · [简体中文](../zh-CN/combat-architecture.md)

This increment continues draft e40ebc7 and incorporates master 2146c09. It provides native hostility, defence and escape, server-side resident commands and actual groups of up to eight people. Resident bows/crossbows, real weapon sessions, War and gun integration remain future work.

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

- Zombie and AbstractSkeleton gain a priority 6 resident goal, after native retaliation, player, villager, iron golem and baby turtle goals. Native AI still executes movement, melee and archery.
- NeutralMob is excluded, including zombified piglins that inherit from Zombie. Endermen retain native neutrality.
- Bodies.of and colony enrollment identify targets, including other registered ResidentKinds. Disenrollment, death and removal end pursuit.
- Repeated joins do not add duplicate goals; newly loaded NBT entities install their own. Tests cover acquisition, pursuit, damage, both monster reloads and native villager targeting.
- Threats.hostile is shared by patrol acquisition and strikes, respecting alliances and neutral anger. Strikes recheck hostility and sight.
- Armed residents off duty may defend against their monster attacker for 100 ticks within 12 blocks. Fear outranks defence at 35% health or less. Patrols reuse StrikeNode.
- Unarmed people use damage/fire fear and continue evading a pursuing attacker. Useful separation guides safe points, retained until arrival, failure or 120 ticks old. A failed random sample cannot withdraw an ongoing escape.

Pack and ToolUse still own working tools. This does not turn the presentation hand into real combat equipment; residents still use the existing melee-tool flow.

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

The concrete acceptance case is four people physically crossing a one-block opening and restoring a line. This is not a guarantee for all bottlenecks, arbitrary terrain, multiple ranks, vehicles or ship deployment. No 16/32/64-unit performance conclusion is claimed. War shortcut selections may overlap; actual group membership cannot.

## P4: assessed weapon contract, no published interface yet

presentHeldItem(copy) is a work presentation; sweepStrayHandStack reclaims other hand stacks. Core Equipment currently manages armour. None of these implement real gun, magazine or component ownership. This increment records the contract without publishing empty WeaponAdapter, WeaponSession or gun stubs.

| Lifecycle        | Required future behaviour                                                                                              |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Acquire          | An adapter identifies real equipment; a session exclusively owns one stack without an owning copy in Pack              |
| Target/authority | Recheck server control, target, hostility and damage permission before execution                                       |
| Sustained action | Tick the native weapon; distinguish READY, FIRING, RELOADING, STOPPING and CLOSED with asynchronous completion         |
| Reload           | Change real equipment/inventory through native reload; report completion, failure or cancellation                      |
| Stop             | Reject new fire intents and wait for native sustained action to finish                                                 |
| Return/lifecycle | Return equipment after stopping; save, unload, death and revoked authority cancel safely and save/drop each stack once |

Next, validate Vanilla melee and bows/crossbows through real equipment sessions, including durability, ammunition, stopping and reload. Separate TaCZ and Point Blank modules can then validate their native contracts.

## Validation entry points

```sh
./gradlew check
./gradlew -PgameTestMod=vanilla runGameTestServer
./gradlew -PcookeryTests=true -PgameTestMod=vanilla runGameTestServer
./gradlew -PgameTestMod=core runGameTestServer
npm run test:unit
npm run typecheck
npm run test:e2e -- tests/e2e/native-hostility.spec.ts tests/e2e/resident-commands.spec.ts --workers=1
```

GameTestServer disables global labour simulation. LaborFixture explicitly advances real labour/travel only for each test's own colony, without changing game runtime behaviour. Client scenarios cover native damage, fear takeover, orders, narrow formations and a saved-world restart.

See [Testing](testing.md) for platform, EULA, dependencies, CI matrix and logs. Local Windows SDK evidence and GitHub Linux CI are recorded separately. Unrun scenarios are not passes.
