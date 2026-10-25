# Vanilla combat, resident commands and formations

[Architecture](architecture.md) · [简体中文](../zh-CN/combat-architecture.md)

This increment continues draft e40ebc7 and now incorporates master 7cf7259. It provides configurable hostility, defence and escape, server-side orders, actual groups, formation patrols and real melee, bow and crossbow sessions. Optional War now provides selection and shortcut inputs; raids, strategic AI and gun integrations remain unimplemented.

## Module ownership

| Module                            | Responsibility                                                                                           |
| --------------------------------- | -------------------------------------------------------------------------------------------------------- |
| Core                              | Residents, labour, item ownership, lifecycle, general travel and exclusive execution                     |
| Vanilla                           | Everyday hostility, defence, patrol, orders, actual groups, placement, movement and weapon contracts     |
| War                               | RTS selection, shortcut sets, pointer orders and route drafts; raids and strategic AI remain future work |
| Separate gun integrations, future | Native TaCZ or Point Blank execution through Vanilla contracts                                           |

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

Trusted server-thread callers must supply actually enrolled, loaded, unmounted PersonBodies and loaded destinations within 128 blocks in the same realm. The combat page resolves the current keeper authority again for each server UI action, then validates colony membership and target bounds before calling these APIs. Commands for other ResidentKinds require separate validation.

Orders reuse Core's Urge → ColonyLabor → BodyRunner safety lifecycle. The old node releases its tool presentation and travel before the same runner takes the order. Release resumes the latest plan instead of replaying withdrawn work. Core only adds cleanup of expired temporary urge backoffs and the general Node.directTravel() option. Direct travel uses the resident's existing Locomotion; ordinary labour keeps its multimodal journey planning.

Orders are transient. Death, unloading, disenrollment and colony closure release them. Movement that has not completed after 1200 ticks reports FAILED and releases control. Holds and completed retreats wait for explicit release and may suppress everyday needs. The general `Node.staysAtWorkSite()` option has the same runner restore a stance after displacement. Terminal or saved handles confer no control.

GUARD time spent yielding the same runner to fear or combat does not consume its movement budget. Actual command execution resumes that budget; an order that never acquires the runner still has a finite timeout.

## P3: actual UnitGroups and deployment intents

UnitGroups.of(colony).create(people) creates an actual group; ofResident(id) reads exclusive membership. Creation and loading no longer truncate membership to eight. Transfers release the previous group's control. Group identity and member order persist in the colony ledger. Deployments are transient, so groups reload as IDLE.

```java
var group = UnitGroups.of(colony).create(people);
group.move(new Deployment(left, right, front, new Deployment.Formation(2)));
```

Deployment accepts level endpoints in the same realm, a horizontal front approximately perpendicular to the line, and spacing between two and eight blocks. Formation(spacing) retains a single rank; Formation(spacing, columns) supplies multiple ranks. The endpoint line remains at most 128 blocks long and every slot must be distinct and safe. War submits this intent without steering each entity.

- move sends members into their target column in sequence, then restores the line, preventing followers from blocking leaders. If column slots have no footing, it tries the line directly.
- hold establishes a line; gather assembles around a centre; release relinquishes all member orders.
- guard establishes an interruptible guard post, using real GUARD orders and the same group lifecycle as patrol.
- retreat keeps high-priority retreat orders in the actual group and holds on arrival. Other authorized keepers can release them from the combat page; stale group handles cannot override later individual orders.
- Individual replacement orders or releases detach that member. Old formation handles cannot overwrite a new individual order.
- Dead or dismissed members leave; unloaded members remain missing while present members reform; returning members receive fresh slots.
- Unreachable deployments report FAILED and release everyone. Fresh deployment after opening a path can retry without teleportation or endless retries.

The validated acceptance case is four people physically crossing a one-block opening and restoring a line. New compact ranks still require gameplay testing; this does not guarantee all bottlenecks, arbitrary terrain, vehicles or ship deployment. The separate 16/32/64-total-entity combat sample does not establish large-group formation performance. War shortcut selections may overlap; actual group membership cannot.

Temporary transit-column slots may follow safe local footing up to two blocks above or below their planned height. Final line slots remain exact. An unreachable height-adjusted transit slot permits one freshly validated attempt at the final line; failure there releases the orders. A separate four-person acceptance scene covers two single-file bends and two steps, followed by a return patrol.

### Formation patrol and proactive combat

`group.patrol(List<Deployment>)` cycles through 2–16 same-realm deployments using column entry and line restoration. `patrolRoute()`, `patrolStop()` and `phase()` expose progress; the route and engagement state are transient.

- GUARD weight is 1.5, healthy combat 2.5, fear 2 and explicit retreat 4. All use the same BodyRunner.
- Guards with usable weapons attack without being hit first. Both targets and combat stances remain within 12 blocks of the assigned slot. Ranged guards prefer firing from existing footing with sight and range.
- Healthy guards renew a short engagement lease; ENGAGING pauses route progress and travel timeout. Wounded combat does not renew it, allowing fear to preempt.
- After combat, present members return to their original slots and assemble for 20 ticks before advancing. Existing death, missing-member, return and individual-order rules apply.
- HOLDING and the patrol pause also verify members' actual positions. A yielded GUARD returns to MOVING, so cached pre-combat arrival cannot advance the route. Melee stance selection leaves room for navigation's arrival tolerance; actual damage retains native range and sight checks.
- All stops validate before replacing control. Unreachable movement releases as FAILED; a fully unloaded group waits and reforms at its current stop when members return.

### Combat page and point configuration

Vanilla registers `folkways:combat` as a dedicated colony-book page through the existing Front extension API. The page selects loaded residents, creates or chooses an actual group, adds points at the player's feet or a verified aimed support surface, and submits explicit orders. A single point configures a guard post; two to sixteen points configure looping patrol stops. Each point is a formation centre, not one endpoint of a deployment line.

The page also provides retreat, release and melee/bow/crossbow preferences. Actions recheck the authorized colony on the server; stale or foreign group IDs, missing members during deployment, invalid terrain and out-of-range destinations cannot acquire control. Release can still clear a group with unloaded members. Draft points remain in the current server session; restarting does not restore orders. This page does not add War selection tools or hotkeys.

### Optional War inputs in the V combat tool

Core exposes generic OverheadTools.Handler callbacks and Overhead.screenRay(); Vanilla owns CombatPackets, SelectionOrders, CombatSelectionOwnership and actual UnitGroups. Optional folkways_war supplies the interaction handler without owning navigation.

| Input                  | Result                                                                                                          |
| ---------------------- | --------------------------------------------------------------------------------------------------------------- |
| LMB drag / click       | Replace the visible selection with loaded, available colony residents                                           |
| Shift+LMB              | Add boxed residents; toggle a single clicked resident; latch this choice on pointer press                       |
| Ctrl+1–9               | Replace that shortcut with the exact selection; an identical set clears the shortcut while preserving selection |
| 1–9                    | Recall the server's current members of that shortcut; top-row and numeric-pad keys are supported                |
| RMB ground             | Immediately replace the route and move around the clicked logical captain destination                           |
| RMB nonfriendly mob    | Order an explicit native attack; residents, allies and players are excluded                                     |
| Ctrl+RMB               | Immediately execute or append one mandatory waypoint; revisit an earlier point to close a patrol                |
| Esc / empty-ground LMB | Clear the visible selection; a second Esc can leave the overhead screen                                         |

Shortcuts may overlap, while each resident has at most one controlling player and one actual action squad. Vanilla retains a transient server-authoritative selection and nine shortcuts per player. Selection or any shortcut reference reserves that resident from ordinary planned labour; clearing one reference does not release another. Exact replacement ABC→AB releases C when C has no other reference. Rebinding ABC to an already identical shortcut clears only that shortcut; ABC remains selected and reserved.

The active selection pointer gesture suppresses camera descent through Handler.blocksCameraDescent(). Shift is latched at LMB press; releasing it before the mouse cannot turn an additive selection into replacement. Shift outside the selection gesture still lowers the camera. MMB pan and Alt+MMB orbit retain their existing ownership rules.

Core's passive Urge.reserve excludes reserved residents from the labour survey and safely lets go of existing ordinary graph work through the same BodyRunner. The reservation creates no movement destination, formation or navigation owner. Personal needs, fear and autonomous combat still use that runner. Vanilla tracks only the squad command generation issued by that controlling session, including movement, attack, stop and subsequent reassembly: losing the resident's last reference releases that old control without cancelling a newer replacement or a book-page deployment.

Closing V, switching tools or putting away the book suspends interaction rather than cancelling an accepted actual squad or its task; a numbered shortcut is not required. SUSPEND retains the original selection references for commands whose actual squad and generation are still owned by this session, plus all nine shortcuts. Uncommanded temporary selection can be released. Unsent pending additions are not replayed on reopening; authoritative snapshots restore the same squad, progress and shortcuts.

Ordinary Esc/empty-ground deselection, exact Ctrl rebinding and explicit release retain their existing per-resident union-of-references rules. No permanent mission reference overrides ABC→AB releasing C. New selection and commands still require the held book, chosen colony and real authority. Accepted tasks use continuing keeper authority and existing level/realm boundaries instead of book/UI presence. The 100-tick lease concerns temporary input; expiry applies the same suspension trim without killing valid tasks or shortcuts. Forty-tick background heartbeats remain possible but are not task lifelines. Authority loss, chosen-colony changes, realm changes, disconnect, death and entity unload still clean up safely. Offline execution, cross-world commands and restored running handles remain outside this iteration; shortcuts are not persistent yet.

Each Ctrl click is committed immediately. A, B, C, D, B visits A and then loops B→C→D→B. Open routes wait at their last point after the full squad assembles and dwells 20 ticks; a later point continues without repeating the prefix or completed dwell. A still-valid ordinary single move A can be extended by Ctrl B. Closed patrol editing is temporarily frozen: further Ctrl clicks explain that RMB must replace the route. Ordinary RMB always replaces the complete route.

Only accepted server route snapshots draw markers. Selection, resumed interaction and recall acknowledgements restore the exact actual squad's route; rejected replacement orders retain and report the previous route. Mutable control operations and movement orders have monotonic request identities, with stale replies rejected. Combat protocol version 6 requires matching clients and servers. Progress observations include command generation, current stop, completion and waiting count; clients apply progress only to the matching acknowledged route generation.

PointRoute uses no extra captain entity. Stage nine separates transit cohesion from assembly at authored stops. It reads at most six cumulative blocks of useful path from the same real reference resident and live navigation cursor, allowing partial paths. Transit can advance with the reference within two blocks of the progress anchor and other members within three blocks of their slots. Authored stops still require the reference to visit the point and every member to remain within existing Reach tolerance for 20 ticks. Each leg has 2400 effective travel ticks; combat, fear, a wholly unloaded squad and assembled dwell do not consume them.

Slots preserve member offsets and reject overlapping actual body volumes. Reachability probes occur only in planning/recovery batches, with at most twelve candidates per member. Temporary missing paths retry in three batches with ten/twenty-tick backoff. After 100 effective ticks without improving at least half a block, a member receives at most two repath/slot recovery attempts. Exhaustion retains control and reports no path, blocked members or travel timeout with a waiting count, without skipping stops, teleporting or returning to work. This is bounded recovery, not established general bottleneck reliability or large-group performance.

Core's Urge.Priority.CONTROL separates deliberate control from ordinary numeric needs. Move, hold, retreat and explicit attack preempt through the same safe BodyRunner, preserving tool and material settlement. New point-patrol GUARD orders use Order.deliberateGuard(), yielding only for actual combat or wounded danger; legacy autonomous guards retain their previous rules. Extension stun and lifecycle locks remain authoritative.

Handler.cues() supplies one mode-aware hint panel with LMB selection, RMB move/attack, MMB pan and Alt+MMB orbit. The bottom inset reserves room for numbered shortcut cards and selected residents' face/health cards, with selection clicks and pagination. Faces use each appearance's declared skin or geometry fallback instead of rerendering every entity. Tab cycles book gestures and registered tools in both directions of the loop, discarding unfinished drawings when switching.

Explicit attacks approach and fire through the same runner and a real WeaponSession. Authority, target identity, alliances, life and pursuit bounds are continually checked: 600 ticks, 64 blocks from the target's initial point and 128 blocks from commander and resident. WeaponSession.explicitTarget(target, authorized) is an opt-in, revocable, single-target contract; unsupported extensions reject it. Autonomous neutrality and patrol hostility are unchanged, and player PvP is excluded.

The client obtains an authorized roster instead of inferring enrollment from unsynchronized Body attachments. Commands carry an explicit colony ID, resident UUIDs and WorldPos points. The server checks authority, every selected resident, every stop and every final slot before replacing control. Selected members may come from multiple groups; unselected members retain their original group intent, and old handles cannot close replacement orders. Packet safety limits are 256 selected residents and 16 stops, rejecting the whole request on overflow rather than selecting only eight. These limits do not establish large-group performance or terrain reliability.

Camera controls remain MMB pan and Alt+MMB orbit in the combat tool, with the existing account-specific persistent global option controlling other tools. This iteration compiles all mods and GameTest sources before daily-instance replacement and manual testing. Formation, ownership, attack completion, failed waiting and post-combat route GameTest sources require separate compilation and execution results. Run GameTests and Blockwright before updating the PR, including shortcut conflicts, route closure, bottlenecks, death/unload and world reload.

### RTS design audit and next stage

Player-facing RTS design changes receive an independent specialist audit first; reuse rts_design_auditor in this conversation and recreate that role in later sessions. Record command semantics, input conflicts, server authority, completion rules and acceptance scenarios. A design audit is separate from engineering or gameplay verification.

Stage eight implements these audited rules; static design approval does not establish runtime acceptance:

- An exact complete actual squad retains its ID when explicitly attacking. Partial or cross-squad selections form one new squad while unselected members retain their intent. An attack replaces the old route. Target death, disappearance, lost hostility, range bounds or timeout ends pursuit and reforms near survivors. All members must assemble without combat or fear borrowing the runner for 20 ticks before holding.
- Target departure events and per-tick validation share the same result classification: zero health or KILLED means TARGET_DEFEATED; a healthy discarded, unloaded or transferred target means TARGET_LOST. Both retain the existing regroup behaviour and stale-handle protection. A new ground route may replace the actual squad; exact STOP and additions to an existing route preserve the applicable squad identity.
- No safe anchor, a member execution failure or 1200 effective regroup movement ticks enters FAILED_WAITING. Control and the reason remain visible without pursuit, route progress or resuming an old patrol's proactive hunting/supply trip. Bounded retaliation against an actual attacker and fear remain available. No survivors cannot count as success; a new order immediately replaces waiting.
- Attack Move arms the next new ground route once; successful server acceptance disarms it, rejection retains it, and deselection/tool exit clears it. Each route fixes DIRECT or ATTACK_MOVE; Ctrl additions inherit that policy. An acknowledged direct route cannot silently change through one appended point: RMB must start a new route. Input following an unacknowledged replacement follows that replacement's mode and waits for its acceptance, without drawing unconfirmed route markers.
- Attack-move automatically engages only existing hostiles, with targets and combat stances bounded to 12 blocks of the pre-combat slot. Combat preserves the logical captain and unfinished stop. The squad returns to its original slots for 20 stable ticks before continuing; each declared stop still requires assembly and a 20-tick dwell. Direct movement keeps travel priority.
- Stop immediately cancels route and pursuit, reforms nearby and retains control with bounded retaliation. An unsafe anchor still accepts stop and reports failed waiting. Release Control atomically removes selected residents from the visible selection and all nine shortcuts, then releases only this session's control. Unselected residents are unaffected.
- Bottom buttons and status show server acceptance, current command, travel/combat/reassembly/waiting and completion reasons. Stop and release await real acknowledgements. Status identifiers are translated on the client. MMB pan, Alt+MMB orbit and existing personal settings stay unchanged, with no new WASD, Shift or Tab shortcut conflicts.

Stage nine addresses manual-client feedback with these additional rules, still requiring manual and pre-PR runtime regression:

- Direct RMB movement and waypoint queues draw from the observed squad center to the active destination and remaining intent, without claiming to visualize the navigator's actual detour. Completed prefixes disappear; patrols retain the cycle. An open route hides after final assembly and its 20-tick dwell, preserving server history, control and later Ctrl append/cycle matching. Holding Ctrl temporarily reveals faded acknowledged history for cycle matching; release restores the remaining route. Only actually displayed, acknowledged markers take part in pixel snapping. Pending input draws no draft and never causes invisible snapping.
- The battle bar targets 48–64 GUI units by reducing decoration and repetition, retaining access to all nine shortcuts, three commands and failure details. Only core hints remain visible, with full help on hover; other tools retain their existing hints. Selected residents show short health bars with one head-point projection per frame. Selection bounds are calculated in input events, and dragging draws only one rectangle. Actual FPS has not been measured, so no single render operation is asserted to explain the reported lag.
- Selection, any shortcut reference or an effective manual order postpones sleep, including failed waiting. Withdrawing the sleep urge lets the existing runner safely release an active lying gesture. Sleep returns after the last control reference and manual order end. Fear, combat and eating retain their existing rules.
- Vanilla time fatigue now uses the actual awake clock for every loaded resident, including idle residents. Thirty awake ticks add one base fatigue point; one Minecraft day reaches the existing sleepy threshold of 800, scaled by FATIGUE effects. Settled work no longer repeats activeTicks time fatigue; walking distance and hunger exertion retain their rules. Sleeping, missing ticks, offline time and the first reload observation do not incur catch-up debt. Existing Tired save values remain readable. Rest mood penalties remain −5/−10 without a second overtime penalty.

The tenth batch's suspension/continuation contract passed an independent static RTS audit. Missing client tracking affects rendering and hit testing only, never automatic server-selection removal. With no new order in flight, authoritative heartbeats can also restore routes when a newer heartbeat replaces the suspension reply in the client cache. New GameTest and E2E sources cover unbound task continuation, input expiry, putting the book away and reopening across a tracking gap. This iteration compiles and checks those sources; real UI round trips and network timing still require runtime acceptance.

Next verify narrow paths, steps, reassembly and actual straggler recovery, then extend detailed cards, persistence and scale evidence. Validate 16–32 residents against equal enemies first; 256 is a packet safety limit. Keep personal camera settings and other tools unchanged.

### Moving combat and post-combat maintenance design

This iteration implements suspension and continuing authority. The moving-fire and maintenance coordination below are reviewed design directions, not claims of new implemented buttons, weapon capabilities or runtime validation.

| Intent                  | Rule                                                                                                          | Encounter or completion behaviour                                                                                          |
| ----------------------- | ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Direct movement         | Ground RMB prioritizes the authored destination without proactive route-diverging hunts                       | Hold under control at arrival, with bounded retaliation when needed                                                        |
| Attack-move             | Existing behaviour discovers original hostiles en route, stops and engages within bounds                      | Return to pre-combat slots and resume the unfinished mandatory stop without replaying prefixes or skipping points          |
| True moving attack      | Planned coordination of movement and native attack according to the actual weapon session's declared mobility | Stop firing immediately when target, sight, range, hostility or capability becomes invalid, retaining route intent         |
| Post-combat maintenance | Planned eligibility after about 100 consecutive ticks without effective engagement, firing or new damage      | Maintenance does not replace the player task; a new command immediately interrupts through normal settlement               |
| Explicit rest           | Planned, explicit pause-task/allow-rest intent                                                                | Retain squad control and resumable route intent; rest is not return-to-work, and execution resources are rebuilt on resume |

True moving attack must use the same BodyRunner and exclusive WeaponSession, never a background attack goal, second navigator or duplicate held item. Melee attacks require actual reach without unlimited detached pursuit. Bows retain actual draw, ammunition and durability; crossbows retain native loading and charged components. Movement speed, turning and firing windows follow actual weapon/session capability, defaulting to stationary use when undeclared. A future WeaponAdapter/WeaponSession mobility contract remains a design proposal; no TaCZ, Point Blank or other unimplemented compatibility stub is added. Epic Fight action windows also need real validation before opting in.

Out-of-combat is an observed combat condition, not a new command that silently replaces player intent. Maintenance first belongs to final standby, guard or stop states. Extending patrol dwell for maintenance requires an explicit policy instead of automatic detours or skipped stops. Start with eating actual carried food in place, then consider existing finite supplies, with distinct no-food/no-ammunition/no-bed/unavailable states.

- **Health:** existing Hunger.stepVitals keeps naturalRegeneration, food and saturation rules. There is no instant full heal, nor an incidental change to whether existing natural regeneration may occur during combat.
- **Food:** a planned maintenance window borrows the same runner to consume one actual carried portion, then returns to standby. It does not default to travelling to a distant dining table or creating food; new commands take precedence.
- **Fatigue and mood:** awake residents still tire outside combat. Combat ending does not erase attack memories or rest debt. Controlled sleep requires future explicit rest permission. Rest pauses the entire squad route rather than treating sleepers as missing to skip mandatory points. Missing beds are reported without inventing free sleep.
- **Ammunition and equipment:** reuse actual inventory, registered-store permissions, existing quantity/distance bounds and timeouts. No free arrows, durability repair or overwriting player equipment. New commands withdraw maintenance and return real loans through ordinary settlement.
- **Feedback:** keep command, combat condition and maintenance separate, such as patrol/combat cooldown, guard/eating or attack-move/no ammunition. The 60-unit bar keeps concise state with optional details.

Proceed from validating this iteration's off-screen tasks to improving existing attack-move, bounded post-combat maintenance, explicit rest, then native ranged moving fire. Acceptance includes closing V without shortcuts, putting away the book beyond 100 ticks, reopening the same generation, explicit deselection/rebinding and real authority-loss cleanup. Later cases cover mandatory-stop continuity, gamerule-disabled healing, real food/ammunition costs, maintenance interruption, rest resumption and single navigation/weapon ownership. Raid directors, strategic AI, PvP, RTS-framework migration and gun modules remain outside scope.

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

### Finite patrol supplies

`PatrolSupplies.of(colony)` exposes `loadout(body, Loadout)`, `clear(body)`, `preference(id)` and `status(id)`. A manually designated main hand precedes patrol preferences. Without a designation or lock, explicit preferences choose an available matching actual weapon before ordinary pack order and retain the existing fallback. Changing an automatic preference closes an automatically selected session, while a manually designated weapon's session continues.

Guards and patrollers collect at most one automatic weapon goal. The arrow carry target defaults to eight and can be set to sixteen or thirty-two in resident equipment, including actual loaded ammunition. Native Multishot's projectile copies still count as one paid arrow. An existing bow or crossbow can infer replenishment without a new preference; a locked empty main hand neither buys another weapon nor infers arrows from a spare bow. A supply urge yields to combat, fear and retreat, walks to registered storage within 32 blocks, and rechecks access rules, reservations, real capacity and stock at collection. It uses the same BodyRunner and Staged settlement, preserving item components. Each trip has a 200-tick limit; stockouts and full packs expose status and retry after a bounded delay. New orders revoke old requests. PackKeeps retains the finite loadout through ordinary cargo stowing.

The stock goal does not remove an existing player-given weapon. Extension adapters that throw after inventory mutations require readable component-identical counts for rollback; arbitrary unreadable or externally mutating inventories remain outside the validated compatibility scope.

Bows and crossbows use the general `CoreSettings.defaultTool(ItemSpec)` declaration for default permission, without becoming a mandatory vocation kit for every patroller. Saved colony tool lists remain authoritative; existing colonies may need to allow ranged weapons in production settings, with actual stock in registered storage.

Core's declaration-time `KitRules.supplyPolicy(owner, policy)` extension only controls ordinary automatic equipment deliveries. Vanilla pauses these deliveries during manual orders or a real weapon loan. Existing requests withdraw through the normal planning path and resume after control returns. Survey observes policy changes even when inventory has not changed. Tool retention and other tasks' stock reservations remain in effect.

## Resident equipment: first batch

Resident details in the book provide an expandable equipment area, previous/next residents, six designated slots, real inventory sources, locks, unequip, arrow targets and filling empty slots. Switching residents retains the selected slot. No hotkeys, selection, shortcuts, squad identity, routes or navigation change.

Core `PersonalItems` records the resident and purpose on the actual ItemStack's custom data, without a second inventory. Personal equipment, spares, loans and cargo share real pack capacity. Planning, material extraction, hauling and work tools exclude personally marked items; Cookery loans and clothing respect the same boundary. Normal rations retain their existing rules; food designated as equipment and then kept as a spare is excluded from meals.

`Equipment.locked/lock/manual` supplies persistent generic locks and automatic clothing bookkeeping handoff. Vanilla `ResidentEquipment.read/act` owns equipment rules and exchanges. `assigned` references the physical designated weapon: it stays in Pack while idle and moves into the exclusive WeaponSession hand during combat; the working hand presentation is read-only. Manual equipment defaults to locked. Breakage leaves an empty locked slot rather than selecting another item. Unlocking permits existing automatic weapon selection and inferred supplies, including a personal spare bow, while retaining ownership protection. No free repair or breakage replacement is introduced.

`WeaponAdapter.supportsAssignment/openAssigned` adds an opt-in exact assignment contract, defaulting to refusal while retaining the binary entry point `open(worker)`. Native and independent Epic Fight adapters support exact selection. An unadapted plugin cannot rescan and substitute another assigned item. Epic Fight borrows the designated physical stack rather than another weapon of its type.

- Requests carry a resident UUID and a single-use server snapshot ticket. The server rechecks colony authority, life and loading, control ownership, menu and complete inventory state. Replays, stale requests and different residents are rejected.
- Player transfers and first claiming unmarked carried items require the same space within six blocks. Remote changes use only that resident's available personal items. Borrowed goods and unmarked cargo during an active task are read-only, including clothing already occupying native slots.
- Exchanges simulate complete final capacity first. Old equipment returns only to the incoming item's source: returning to a player clears ownership; remaining with the resident becomes a personal spare. Insufficient space rejects the transaction without dropping items. New exchanges and existing patrol supplies preserve room for an outstanding weapon loan to return.
- A busy main hand rejects related changes without ending its session or task. Free armour slots, carry targets and real arrow transfers do not add a navigator. Targets create no items; manual refill moves finite stock from a nearby player's main inventory and reports shortages or lack of room.
- Fill-empty uses unique compatible personal candidates only. It does not guess between several items, and mixed results report equipped, ambiguous and capacity-blocked slot counts. Automatic offhand fill respects native offhand purpose; manual offhand placement accepts real items. Actual shields use the defense windows below.
- Saving and unloading retain physical items, designations and locks without persisting active navigation or weapon sessions. Death and colony revocation release each real item once; world drops, returns to players and fired ammunition clear personal ownership. Native charged components and durability survive.

### Warehouse goals and one fulfilment request

The equipment area has separate manual and loadout tabs, without enlarging the V HUD. Vanilla `EquipmentPlans` owns colony-shared templates, resident goals and finite requests, reusing Core ownership, reservations, Staged settlement and the single BodyRunner. Built-in melee guard and archer templates accompany up to sixteen custom templates saved from actual equipment. Templates record item types and arrow targets, without copying items, enchantments, names or durability; manual assignment remains the way to designate one exact weapon.

- Self, current selection and actual squad are explicit scopes with member names and counts. More than thirty-two recipients rejects the whole request. Each resident is checked separately for authority, loading and control; partial failures have individual results. A numeric shortcut does not implicitly become an actual squad.
- Preview checks gaps and available sources without taking items. Applying a goal only saves intent. Warehouse fulfilment is an explicit **single request**: shortages retry with backoff until fulfilled or cancelled, then stop while retaining the goal. Later arrow consumption or breakage requires another request. Continuous maintenance, automatic breakage replacement and free repairs are deferred.
- Empty template fields retain equipment, and manual locks take precedence. Automatic replacements do not create manual locks. Old items become personal spares in the actual pack; insufficient room rejects replacement, including room owed to an outstanding weapon loan. Arrows include actual loaded ammunition.
- Available personal inventory precedes the nearest eight registered stores within thirty-two blocks in the same space, with at most 128 cells inspected per inventory. Collection rechecks access and other work's reservations. Each leg has a 240-tick deadline; stockouts and failures back off at least 100 ticks. Physical reach and sight remain required.
- Only idle or stable HOLD/GUARD residents start collection. A stable command lends its existing runner while retaining its identity, station and squad route. Collection does not consume command travel timeout; afterwards the original command returns to its station. New movement, attack, retreat or danger revokes the old request; fear and combat have priority. Drawn but uncommitted items follow ordinary staged restoration.
- Squads pause route advancement, dwell, blocked-member recovery and timing during a valid refill loan, including completed single-point routes. A fresh twenty-tick assembled dwell follows return. Replacement commands invalidate the loan before this pause is considered.
- Save templates, goals and unfinished request intentions, without live nodes, reservations or weapon sessions. Loading reconstructs and validates requests. Batch results bind to the goal identity and revision accepted by that operation and show current status and gaps. Rejected members retain that operation's failure; later changes explicitly show replacement. Previews are identified stock snapshots, and normal sync does not repeatedly scan warehouses.

### Shields and Epic Fight

`ResidentShields.guard/lower` uses the real offhand and native blocking path, preserving direction, the five-tick raise delay, piercing and bypass rules. Real blocking consumes durability; breakage consumes the shield once. Axe disable lasts 100 ticks and is saved and synchronized. Melee without an available shield retains its ten-tick attack. A successful raise fixes that attack at twenty ticks: defense through tick fourteen, shield down for the final five ticks, damage at tick twenty. Breakage or disable cannot advance that pending hit. Bows and crossbows retain exclusive main-hand use with the shield lowered.

Independent `mods/epicfite` (mod ID `folkways_epicfight`) targets Epic Fight 21.17.3.1 up to but excluding 21.18. Exact equipment uses the selected real stack. Native animation collisions cause damage and recheck live explicit-target authority, identity, range and sight. A shield gives a cancellable six-tick preparation window: FIRING keeps the work node alive while collision remains unauthorized. The shield lowers before animation starts; unshielded timing is unchanged. Stop, authority loss, saving, unloading and death revoke queued attacks and return the weapon idempotently. Explicit targeting does not permit damage to players or other residents.

Core `BodyActions` lets independent animation extensions pause ordinary body actions after revocation and cleanup, so stun cannot freeze a loan's return. `WeaponSession.defeatedTarget` and public patrol `StrikeNode` settle real animation-frame kills once. `acceptsBarehanded` retains the native unarmed animation entry point.

### Alerting, explosive avoidance and native movement

Patrol discovery starts at twenty-four blocks plus effects. Autonomous combat positions and pursuit retain the twelve-block station boundary. Ranged residents may shoot outward from legal positions within actual weapon range; melee does not pursue outside it. STOP still retaliates only against actual attackers, and neutrality and alliances remain authoritative. Autonomous melee does not approach creepers. Native bow aiming uses its actual full-draw speed, arrow drag and gravity rather than the slower skeleton projectile's excessive arc compensation.

Healthy residents recognize nearby creepers: six blocks ordinarily, eight when ignited or swelling, and ten when charged. `CombatEvasion` tries at most twelve candidate paths; the current BodyRunner owns one `EvasionNode` for at most 100 ticks without replacing the command. Emergency explosive avoidance may retreat within a sixteen-block local station domain; ordinary kiting retains twelve. A failed proposal does not enter a new unbounded random escape, while original damage/fire fear remains. Movement, retreat and replacement commands revoke old actions. Kiting is retreat, stop, shoot, and retreat again when approached, without moving fire or a guaranteed kill when range or terrain is insufficient.

Vanilla `CombatMotions` is an optional native-action interface whose exclusive session belongs to the current node. The Epic Fight bridge uses native directional STEP movement and its native evasion window, with a forty-tick cooldown and at most twenty ticks/three blocks per action. It invents no invulnerability. Each frame checks solid footing, walls, ledges, loading, realm, live authority and the local domain. If unavailable, the same node uses its existing navigator. Sprint applies actual native speed to that path for a twenty-tick window, without another AI goal. Cancellation, authority loss, hard stun, saving and unloading reclaim the action and temporary dimensions. These are bounded resident decisions rather than the player-only DodgeSkill.

### Generic ammunition and post-combat recovery

`WeaponAdapter.ammunition(actualWeapon, reserve)` defaults to empty. Extensions may declare `Ammunition(stock, compatible, target, loaded)`: both the stock query and actual component predicate must match, and loaded counts paid physical units, not Multishot copies. `Weapons.ammunition` does not require current `usable` readiness, so an empty weapon can request stock. Bows/crossbows declare native supported projectiles and retain their original session consumption; no gun adapter is invented.

- Designated ranged weapons continuously check ammunition, triggering below half their carry target and filling to the target including loaded units. A persistent resident switch cancels only automatic ammunition, independently of explicit whole-kit requests. Shortages and capacity failures expose status and backoff.
- Automatic collection needs about 100 ticks without a visible nearby hostile, firing or fresh damage. It starts while idle, at a stable personal station or with a completely assembled squad at its final station. Internal six-block route prefixes and looping patrols do not start warehouse trips. New danger, commands or disabling the switch withdraw requests, reusing existing staged physical collection, reservations, component checks, return space and squad pauses.
- Whole weapon/armour fulfilment remains one explicit request. Only declared ammunition gets continuous maintenance. Undesignated legacy patrol weapons retain their existing finite supply transaction rather than treating spare task cargo as another assignment.
- After about 100 quiet ticks, an injured resident may spend 32 ticks eating one actual carried ration in place. Personal orders must be stable or idle. Squads must be fully assembled; looping patrols eat only at authored mandatory stops, without departing for food or using internal route prefixes. A fresh full twenty-tick dwell follows the meal.
- Recovery eating permits real food at ordinary meal-threshold gaps such as food level seventeen. Food permission/safety, personal-item protection, paid consumption and container remainders remain authoritative. New commands, nearby enemies or damage cancel chewing without consuming before completion. Hunger, saturation and `naturalRegeneration` still determine healing; no free heal, fatigue/mood reset or implicit sleep is added. Missing rations and disabled regeneration are visible.

Resident details expand to 420 logical pixels within the existing window bounds, with up to nine inventory columns. Dragging a source cell to any equipment slot is only a preview; dropping submits the existing resident/source/single-use ticket for atomic exchange. Switching residents, cancelling, stale snapshots or releasing outside the window cannot transfer early. Reverse removal still uses Unequip. Ammunition and recovery states appear in details without enlarging the V HUD.

This iteration receives independent RTS audit. Static approval is separate from client and GameTest evidence recorded in delivery logs. Continuous whole-kit maintenance, automatic breakage replacement, reverse spare withdrawal, arbitrary third-party weapons and gun integrations remain deferred. Future War submits batch intentions through Vanilla rather than moving items itself.

## Validation entry points

```sh
./gradlew check
./gradlew -PgameTestMod=vanilla runGameTestServer
./gradlew -PcookeryTests=true -PgameTestMod=vanilla runGameTestServer
./gradlew -PgameTestMod=core runGameTestServer
./gradlew -PepicfightTests=true -PgameTestMod=epicfite runGameTestServer
npm run test:unit
npm run typecheck
npm run test:e2e -- tests/e2e/native-hostility.spec.ts tests/e2e/resident-commands.spec.ts tests/e2e/combat-hostility-policy.spec.ts tests/e2e/formation-patrol.spec.ts tests/e2e/weapon-sessions.spec.ts --workers=1
npm run test:e2e -- tests/e2e/combat-page.spec.ts tests/e2e/combat-supplies.spec.ts tests/e2e/combat-endurance.spec.ts tests/e2e/formation-terrain.spec.ts tests/e2e/combat-golems.spec.ts --workers=1
```

GameTestServer disables global labour simulation. LaborFixture explicitly advances real labour/travel only for each test's own colony, without changing game runtime behaviour. Client scenarios cover native damage, fear takeover, orders, narrow formations and a saved-world restart.

With `FOLKWAYS_COMBAT_BENCH=1`, opt-in `combat-performance.spec.ts` records 16/32/64 total entities, half residents and half zombies, after 200 warm-up ticks with 100 sampled ticks. Residents receive real swords and GUARD orders, and both sides have 1024 health to retain the population during combat. Artifacts contain paired engine and full Pre-to-Post tick durations plus actual target/navigation counts. They impose no invented performance threshold and do not cover arbitrary terrain or mod combinations.

See [Testing](testing.md) for platform, EULA, dependencies, CI matrix and logs. Local Windows SDK evidence and GitHub Linux CI are recorded separately. Unrun scenarios are not passes.
