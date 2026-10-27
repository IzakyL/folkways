# Vanilla defence and optional RTS commands

[Architecture](architecture.md) · [简体中文](../zh-CN/combat-architecture.md)

Vanilla provides everyday hostility, defence, escape, drawn patrol routes, finite supplies and real melee, bow and crossbow sessions. Optional `folkways_rts` owns direct resident commands and their server/client interaction. This split changes ownership without changing accepted command or input semantics. The optional Superb Warfare and TACZ integrations are described below; optional `folkways_raid` adds colony raids through Vanilla defence. Strategic AI remains unimplemented.

## Module ownership

| Module                    | Responsibility                                                                                                                                                                                                                                                                                                                                     |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Core                      | Residents, labour, item ownership, lifecycle, general travel, control reservations and exclusive execution; generic book, overhead and personal-camera APIs                                                                                                                                                                                        |
| Vanilla                   | `plugins.combat.group`: shared movement/formation/regroup planning; `plugins.combat`: NativeHostility, Threats, CombatTags, weapons, LoadoutRegistry/LoadoutProvider and PatrolSupplies/PatrolSupplyNode; `plugins.patrol`: Beats, Wards, WatchNode, PatrolPresence, StrikeNode and the existing drawn patrol trade; awake time and defence mixins |
| RTS                       | `rts.combat.command`: ResidentCommands, CommandNode, AttackNode and CommandContent; `rts.combat.group`: UnitGroup, UnitGroups and command adapters; `rts.combat.control`, `net` and `ui`; `rts.client`: CombatController, CombatProjection, CombatCards and RtsClient                                                                              |
| Optional gun integrations | Warfare implements SBW and `mods/tacz` implements native TACZ through Vanilla profiles and weapon sessions; Point Blank remains future work                                                                                                                                                                                                        |

`mods/rts` registers `folkways_rts` through `FolkwaysRtsMod`. It requires Vanilla and Core on both client and server. Neither Vanilla nor Core requires RTS. Create, Cookery and Modular Golems retain independent extensions.

Vanilla's small `DefenseControl` service API observes current manual orders, selection reservations and failed waiting. Without RTS it returns empty observations. RTS supplies `CommandDefenseControl` through the existing colony-service extension; guards expose their original live order object, destination, defensive-only state and engagement callback. Exact object identity preserves supply-trip revocation on order replacement. Defence keeps its existing guard leash, wounded-fear priority and retaliation; failed waiting cannot restart an old patrol hunt or supply trip. Living uses the same observations to defer sleep while control exists.

The entire `folkways:combat` page, desk, overhead tool and combat payload registration belong to RTS. Vanilla alone offers neither the one-point UnitGroup guard nor the two-point UnitGroup patrol page; its drawn patrol route remains available. RTS registers this page for squads and orders, displaying each resident’s assigned profile. Vanilla registers a separate `folkways:loadouts` page for equipment editing and assignment. Patrol also owns the drawn sentry zones described below. Shared loadout labels stay with Vanilla's API; page and RTS strings ship in RTS in all four locales. Existing keys stay stable except the renamed `folkways.rts.*` input/status namespace.

### Save and protocol compatibility

The colony ledger owner `folkways:unit_groups`, group UUIDs and ordered `groups`/`members` schema remain unchanged. RTS reads these existing bags directly; no destructive conversion is needed. Core preserves unknown owner bags when RTS is absent, so saving Vanilla alone does not discard membership. Reinstalling RTS restores surviving roster membership as IDLE. Orders, deployments, routes, selection, shortcuts and running handles remain transient and are never resumed from a save. Vanilla retains `folkways:patrol_loadouts` and the existing loadout migration.

RTS registers its own payload handlers on its mod bus. Payload IDs and the dedicated version-6 registrar remain unchanged; required mod dependencies and matching protocol versions apply on both sides. Vanilla alone registers no combat payloads.

### Restructure design audit

The independent `rts_design_auditor` granted final static design approval for this ownership change against the existing input and lifecycle contracts. Accepted scope is the module split, optional empty defence observations, unchanged RTS book registration, save-owner retention and test relocation. Acceptance covers vanilla-only hostility/drawn patrol/finite supplies, guard supply replacement, failed waiting and bounded retaliation, sleep suppression, suspension/input expiry and authority-loss cleanup, exact shortcut reference unions, rejected-order snapshots and old-save round trips. Modifier ownership, server authority, visible failure reasons and completion rules remain as documented below. This static audit is separate from engineering tests and real-client gameplay acceptance.

Moving fire, maintenance, explicit rest, strategic AI, persistent shortcuts and scale work remain deferred.

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

The public RTS service is `rts.combat.command.ResidentCommands`:

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

Deployment accepts level endpoints in the same realm, a horizontal front approximately perpendicular to the line, and spacing between two and eight blocks. Formation(spacing) retains a single rank; Formation(spacing, columns) supplies multiple ranks. The endpoint line remains at most 128 blocks long and every slot must be distinct and safe. RTS submits this intent without steering each entity.

- move sends members into their target column in sequence, then restores the line, preventing followers from blocking leaders. If column slots have no footing, it tries the line directly.
- hold establishes a line; gather assembles around a centre; release relinquishes all member orders.
- guard establishes an interruptible guard post, using real GUARD orders and the same group lifecycle as patrol.
- retreat keeps high-priority retreat orders in the actual group and holds on arrival. Other authorized keepers can release them from the combat page; stale group handles cannot override later individual orders.
- Individual replacement orders or releases detach that member. Old formation handles cannot overwrite a new individual order.
- Dead or dismissed members leave; unloaded members remain missing while present members reform; returning members receive fresh slots.
- Unreachable deployments report FAILED and release everyone. Fresh deployment after opening a path can retry without teleportation or endless retries.

The validated acceptance case is four people physically crossing a one-block opening and restoring a line. New compact ranks still require gameplay testing; this does not guarantee all bottlenecks, arbitrary terrain, vehicles or ship deployment. The separate 16/32/64-total-entity combat sample does not establish large-group formation performance. RTS shortcut selections may overlap; actual group membership cannot.

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

RTS registers `folkways:combat` as a dedicated colony-book page through the existing Front extension API. The page selects loaded residents, creates or chooses an actual group, adds points at the player's feet or a verified aimed support surface, and submits explicit orders. A single point configures a guard post; two to sixteen points configure looping patrol stops. Each point is a formation centre, not one endpoint of a deployment line.

The page also provides retreat, release and the assigned profile names. Equipment editing and resident assignment live in Vanilla’s Loadout profiles page. Actions recheck the authorized colony on the server; stale or foreign group IDs, missing members during deployment, invalid terrain and out-of-range destinations cannot acquire control. Release can still clear a group with unloaded members. Draft points remain in the current server session; restarting does not restore orders. RTS also installs the overhead selection handler described below.

### Optional RTS inputs in V RTS mode

Core exposes generic OverheadTools.Handler callbacks and Overhead.screenRay(); optional folkways_rts owns CombatPackets, SelectionOrders, CombatSelectionOwnership, actual UnitGroups and the interaction handler. Commands still execute through Core’s existing BodyRunner and locomotion.

| Input                  | Result                                                                                                          |
| ---------------------- | --------------------------------------------------------------------------------------------------------------- |
| LMB drag / click       | Replace the visible selection with loaded, available colony residents                                           |
| Shift+LMB              | Add boxed residents; toggle a single clicked resident; latch this choice on pointer press                       |
| Ctrl+1–9               | Replace that shortcut with the exact selection; an identical set clears the shortcut while preserving selection |
| 1–9                    | Recall the server's current members of that shortcut; top-row and numeric-pad keys are supported                |
| G                      | Toggle the persistent Move / Attack move input mode; no selection or command required                           |
| RMB ground             | Replace the route using the current Move / Attack move input mode around the logical captain destination        |
| RMB nonfriendly mob    | Order an explicit native attack; residents, allies and players are excluded                                     |
| Ctrl+RMB               | Immediately execute or append one mandatory waypoint; revisit an earlier point to close a patrol                |
| Esc / empty-ground LMB | Clear the visible selection; a second Esc can leave the overhead screen                                         |

Shortcuts may overlap, while each resident has at most one controlling player and one actual action squad. RTS retains a transient server-authoritative selection and nine shortcuts per player. Selection or any shortcut reference reserves that resident from ordinary planned labour; clearing one reference does not release another. Exact replacement ABC→AB releases C when C has no other reference. Rebinding ABC to an already identical shortcut clears only that shortcut; ABC remains selected and reserved.

The active selection pointer gesture suppresses camera descent through Handler.blocksCameraDescent(). Shift is latched at LMB press; releasing it before the mouse cannot turn an additive selection into replacement. Shift outside the selection gesture still lowers the camera. MMB pan and Alt+MMB orbit retain their existing ownership rules.

Core's passive Urge.reserve excludes reserved residents from the labour survey and safely lets go of existing ordinary graph work through the same BodyRunner. The reservation creates no movement destination, formation or navigation owner. Personal needs, fear and autonomous combat still use that runner. RTS tracks only the squad command generation issued by that controlling session, including movement, attack, stop and subsequent reassembly: losing the resident's last reference releases that old control without cancelling a newer replacement or a book-page deployment.

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
- G toggles a persistent local Move / Attack move input preference; RMB ground submits that policy and LMB always selects. Acceptance, refusal and deselection do not consume it. Each route fixes DIRECT or ATTACK_MOVE; Ctrl additions must match the current preference in both directions, including pending replacements. A mismatch fails visibly without changing the route; RMB starts a new route. Pending input captures its submitted policy and waits for acceptance without drawing unconfirmed markers. Leaving the RTS input screen or changing player/colony/realm resets the preference to Move without cancelling accepted tasks.
- Attack-move automatically engages only existing hostiles, with targets and combat stances bounded to 12 blocks of the pre-combat slot. Combat preserves the logical captain and unfinished stop. The squad returns to its original slots for 20 stable ticks before continuing; each declared stop still requires assembly and a 20-tick dwell. Direct movement keeps travel priority.
- Stop immediately cancels route and pursuit, reforms nearby and retains control with bounded retaliation. An unsafe anchor still accepts stop and reports failed waiting. Release Control atomically removes selected residents from the visible selection and all nine shortcuts, then releases only this session's control. Unselected residents are unaffected.
- The bottom status shows the current authoritative command, travel/combat/reassembly/waiting and completion reasons. Stop and release await real acknowledgements. Status identifiers are translated on the client. MMB pan, Alt+MMB orbit and existing personal settings stay unchanged, with no new WASD, Shift or Tab shortcut conflicts.

Stage nine addresses manual-client feedback with these additional rules, still requiring manual and pre-PR runtime regression:

- Direct RMB movement and waypoint queues draw from the observed squad center to the active destination and remaining intent, without claiming to visualize the navigator's actual detour. Completed prefixes disappear; patrols retain the cycle. An open route hides after final assembly and its 20-tick dwell, preserving server history, control and later Ctrl append/cycle matching. Holding Ctrl temporarily reveals faded acknowledged history for cycle matching; release restores the remaining route. Only actually displayed, acknowledged markers take part in pixel snapping. Pending input draws no draft and never causes invisible snapping.
- The battle bar reserves 60 GUI units in the compact-layout follow-up below, retaining access to all nine shortcuts, command hotkeys and execution-failure details. Only core hints remain visible, with full help on hover; other tools retain their existing hints. Selected residents show short health bars with one head-point projection per frame. Selection bounds are calculated in input events, and dragging draws only one rectangle. Actual FPS has not been measured, so no single render operation is asserted to explain the reported lag.
- Selection, any shortcut reference or an effective manual order postpones sleep, including failed waiting. Withdrawing the sleep urge lets the existing runner safely release an active lying gesture. Sleep returns after the last control reference and manual order end. Fear, combat and eating retain their existing rules.
- Vanilla time fatigue now uses the actual awake clock for every loaded resident, including idle residents. Thirty awake ticks add one base fatigue point; one Minecraft day reaches the existing sleepy threshold of 800, scaled by FATIGUE effects. Settled work no longer repeats activeTicks time fatigue; walking distance and hunger exertion retain their rules. Sleeping, missing ticks, offline time and the first reload observation do not incur catch-up debt. Existing Tired save values remain readable. Rest mood penalties remain −5/−10 without a second overtime penalty.

The tenth batch's suspension/continuation contract passed an independent static RTS audit. Missing client tracking affects rendering and hit testing only, never automatic server-selection removal. With no new order in flight, authoritative heartbeats can also restore routes when a newer heartbeat replaces the suspension reply in the client cache. New GameTest and E2E sources cover unbound task continuation, input expiry, putting the book away and reopening across a tracking gap. This iteration compiles and checks those sources; real UI round trips and network timing still require runtime acceptance.

Next verify narrow paths, steps, reassembly and actual straggler recovery, then extend detailed cards, persistence and scale evidence. Validate 16–32 residents against equal enemies first; 256 is a packet safety limit. Keep personal camera settings and other tools unchanged.

### RTS command keys and ledger-style battle bar

The command defaults are **G** for Toggle movement mode, **H** for Stop / hold position and **J** for Release Control.
They are registered as rebindable `KeyMapping`s under **Folkways RTS** and handled only by the active RTS screen.
A/S conflict with the existing WASD camera polling; X also belongs to vanilla creative toolbar loading.
G/H/J avoid vanilla defaults and existing RTS digits, Ctrl/Shift/Alt, Tab, Esc, Space and V. Runtime base-RTS
mapping inspection found no other bindings on G/H/J. The pinned TACZ 1.1.8-hotfix-r7 uses G fire-select/H inspect,
and SBW 0.8.9.2 uses H edit-mode/J loiter configuration, but their handlers reject any open Screen, including RTS.
Custom bindings or other mods can still introduce conflicts, shown in Controls; camera controls are unchanged.
The generic `Cue.bound` resolves registered mapping names for KeyHints, including rebound keys and mouse buttons.
`MOVE_MODE` keeps the persisted `key.folkways.rts.attack_move` ID, with its new toggle label, so player rebindings survive.

Unmodified G toggles Move / Attack move as a local persistent preference, including with an empty selection.
It sends no packet and never rewrites an accepted or pending order. LMB always selects; RMB ground submits the
current policy, while RMB on a nonfriendly mob remains explicit attack. Same-mode Ctrl+RMB starts/appends/closes
routes; a different current or pending route mode produces a failure in either direction. Use plain RMB to
start a replacement. Acceptance/refusal, selection/recall, Stop/Release and first Esc deselection retain the mode.
Every existing cancel/reset path restores Move: leaving the tool/mode or V screen, opening a temporary screen,
putting the book away, logout and player/colony/realm changes. No Core lifecycle API changes are needed.
Held keys retain the existing screen repeat latch. Stop/Release retain server validation and control lifecycles.
The RMB hint shows both modes, highlighting the active one in gold/bold and dimming the other; the G hint names
the toggle and resolves the rebound mapping. The bottom input-mode label is separate from server task status.

The three command buttons remain removed. The 60-GUI-unit bottom bar has separate input-mode/count and
command/failure rows, followed by left-aligned shortcut cards and centered portraits/health/paging on one row.
Adaptive slot widths and portrait paging prevent overlap at 1920×1080 and 854×480. Matching groups use only
the book pressed sprite and ordinary ink; the match compares the nonempty selection to complete server membership. It draws the book's existing `desk/panel` and oak button
sprites through LDLib's nine-slice `SpriteTexture`, uses `Rows.INK`/`QUIET`, and draws text without shadows.
There is no copied texture set or replacement widget framework. Portraits use the space freed by the buttons.

The bottom-left status row now has a failure-only transient override. Synchronization/busy submission,
no selection, invalid/unloaded/wet ground, selection/route limits, closed patrol edits, too-short cycles,
a bidirectional input/route mode mismatch through Ctrl append, and correlated server rejection are displayed
in the existing warning-red ink. Eight local failure translation keys are restored in all four locales; the
generic server rejection reuses `folkways.combat.error.rejected`. No success/sent/accepted translations return.

One failure replaces the previous message and its deadline. It expires after 3000 wall-clock milliseconds,
restoring the latest authoritative command/phase/result; persistent execution failures remain red. Successful
commands neither create a transient nor prolong an existing failure. Cancelling/resetting the tool or changing
context clears the message. Only matching current-context replies may report rejection; heartbeat and passive
SUSPEND/CLEAR lifecycle replies are excluded. G works without selection; orders still report select-first.

RTS packet paths still send no vanilla action-bar messages. The failure text is drawn inside the existing bottom
status row, and its hover keeps the authoritative route summary. Shared `folkways.combat.saved` and
`folkways.combat.error.rejected` translations remain because `CombatDesk` uses them in the book page; book-page
configuration feedback stays unchanged. Server authority, ACKs and rejected-route retention remain intact.

The independent `rts_design_auditor` approved this final static design, including input/lifecycle contracts,
modifier ownership, server authority, observable failures and acceptance scenarios. This approval is separate from
engineering/runtime results below. The existing `tests/promo/combat-rts.take.ts` still looks for the removed
Attack Move button; updating that separate automation remains deferred under the instruction not to edit promo files.
Runtime regression also exposed stale E2E fixtures: residents must enter the level before enrollment, combat
fixtures must assign a profile under the existing default Flee policy, and page/overlay assertions must wait for
server synchronization. The separate Loadouts navigation failure remains enabled: `PageKinds.all()` lists only
the first page in each enrollment, while Loadouts is Patrol's secondary page, so the expected
`folkways.loadouts.title` navigation entry is absent. That entry-point repair is now covered by the loadout UX follow-up below. The melee supply case now verifies that an
unassigned resident makes no inferred kit claim before its profile and GUARD command are applied. These are
fixture/assertion updates to current contracts, without server gameplay changes.
Acceptance covers persistent G toggling, ground RMB and same-mode Ctrl routes, LMB selection, repeat suppression,
modifier ownership, invalid ground retaining mode/route/selection, rebound keys, inactive-tool isolation,
Stop/Release acknowledgements and reference cleanup,
plus readable nine-card/portrait layouts at 1920×1080 and 854×480. New moving-fire, maintenance, rest, persistence,
arbitrary-terrain and scale work remain deferred.

### Command-key and battle-bar engineering results (2026-10-26)

Linux / Java 21 validation uses `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
The final production code builds successfully; warnings are existing deprecated API warnings.

| Check                                                                | Result                                                                                                                                |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `./gradlew build`                                                    | Passed, all modules packaged                                                                                                          |
| `./gradlew -PrtsTests=true -PgameTestMod=rts runGameTestServer`      | All 78 required tests passed                                                                                                          |
| `npm run test:i18n`                                                  | Passed, all four locales synchronized                                                                                                 |
| `npm run typecheck`                                                  | All seven TypeScript projects passed                                                                                                  |
| `npm run test:e2e -- tests/e2e/combat-rts-input.spec.ts --workers=1` | Passed: real pointer/key input, repeat/modifier handling, invalid ground, both confirmations, rebinding, suspension, Stop and Release |
| RTS E2E regression, latest results per case across runs              | 11 passed, 1 failed; with the input case above, 12 passed / 1 failed                                                                  |

The regression commands used `npm run test:e2e -- <specs> --workers=2`. Cases cover `combat-camera` (3),
`combat-overhead-suspend`, `resident-commands` (including restart), `formation-terrain`, `formation-patrol`,
`combat-supplies` (2), `combat-endurance` and `weapon-sessions`. All these passed. `combat-page` passed its
real group creation, guard, patrol, release and retreat steps, then failed at the existing missing Loadouts
navigation entry: `Error: Missing navigation or registered page folkways.loadouts.title`. This remains an
unresolved regression limitation, not an all-green E2E claim. No test was skipped or weakened to hide that entry.

Earlier runs exposed a Java test-task integer cast, asynchronous overlay/page reads, obsolete enrollment order,
and unassigned combat fixtures; those were corrected and rerun. The first cold-cache input launch was stopped
before gameplay and rerun after local immutable assets were prepared. Logs are
`/tmp/folkways-rts-ui-input-acceptance.log`, `/tmp/folkways-rts-ui-regression.log`,
`/tmp/folkways-rts-ui-regression-fixed.log`, `/tmp/folkways-rts-ui-regression-acceptance.log`,
`/tmp/folkways-rts-ui-page-final.log` and `/tmp/folkways-rts-ui-gametest-final.log`.

Both game screenshots were inspected: the paper/wood frame, nine numbered cards, portraits, health bars and
status stay readable at GUI scale 2. Explicit sprite-buffer flushes keep the paper outside the text scissor
and prevent buffered card backgrounds from covering vanilla faces. Screenshots are local test artifacts:

- `tests/out/e2e/shots/combat-rts-input-2026-10-26T15-21-09-534Z-857d0b-book-bar-1920x1080.png`
- `tests/out/e2e/shots/combat-rts-input-2026-10-26T15-21-09-534Z-857d0b-book-bar-854x480.png`

This bounded input/visual acceptance does not validate hostile attack-move recovery, arbitrary terrain or scale.
Static design approval, server GameTests and real-client observations remain separate evidence.

### Screenshot-review follow-up

This historical screenshot-review batch extended removal to the action-bar region immediately above the battle
bar. Its fully silent failure policy is superseded by the failure-only follow-up below. The five
`CombatPackets` feedback sites are deleted: move/attack success and rejection, stop/waypoint rejection, and
control-operation rejection (including release). The unused Component import and old action-bar Javadoc are
removed. This changes no packet, validation, authoritative acceptance, state snapshot or lifecycle rule.
Book-page configuration feedback remains in `CombatDesk`; no global HUD-message filter hides unrelated messages.

The panel content now starts 14 GUI units from the screen edge: four outside, seven for the existing wood
border and three for breathing room. Status text, count, scissor and hover bounds share that inset. The bar
reserves 64 GUI units, with the portrait row moved within the paper so its wood frame clears the outer border.
All nine usable shortcut slots, including empty slots, use the existing oak button texture and ink text.
Chosen/hover states use the book's existing pressed/hover textures. Portraits use the same oak frame, inset
faces and health bars; selection, shortcut references and paging keep their original meaning.

The independent `rts_design_auditor` approved this final follow-up static design against the current user-approved
input/lifecycle contracts, modifiers, server authority, remaining observable failures and acceptance scenarios.
Accepted scope is packet feedback removal and these visual corrections. Generic rejected commands and control
operations lost their last transient text explanation at that stage; persistent execution-state failures stayed visible.
No replacement notification system, main-book navigation repair or wider RTS work is included.

Engineering verification for the final follow-up code:

| Check                                                                                                                                          | Result                                                                                     |
| ---------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `./gradlew build -x test` (E2E setup)                                                                                                          | Passed; all modules packaged                                                               |
| `./gradlew -PrtsTests=true -PgameTestMod=rts runGameTestServer`                                                                                | All 78 required tests passed                                                               |
| `npm run test:e2e -- tests/e2e/combat-rts-input.spec.ts --workers=1`                                                                           | Passed, including active overlay checks after accepted orders and all five rejection paths |
| `npm run test:e2e -- tests/e2e/combat-rts-input.spec.ts tests/e2e/combat-camera.spec.ts tests/e2e/combat-overhead-suspend.spec.ts --workers=2` | All four cases passed in the combined initial run                                          |
| `npm run test:i18n`, `npm run typecheck`, `npm run format:md:check`                                                                            | Passed                                                                                     |

The input E2E separates real-input acceptance from explicit invalid move/attack/waypoint and stale stop/release
packet probes. It observes the actual active `Gui.overlayMessageString` and timer without clearing or filtering
messages. Earlier observation-task attempts used the wrong GUI field; the corrected task uses the declaring
`Gui` class and the pinned 1.21.1 field name. A non-RTS book-gesture hint from Tab is allowed to expire naturally
before the final RTS order. The final full input run passes. Logs are
`/tmp/folkways-rts-ui-rework-acceptance.log`, `/tmp/folkways-rts-ui-rework-e2e.log` and
`/tmp/folkways-rts-ui-rework-gametest.log`; the initial combined log includes the observation-task failure and
four passing camera/suspension cases. No production behavior changed for those test corrections.

New game screenshots were inspected at both sizes: no Updated/action-bar feedback, no clipped initial A,
normal oak empty slots, a populated selected shortcut, and inset wood portrait frames with visible health bars:

- `tests/out/e2e/shots/combat-rts-input-2026-10-26T15-43-48-061Z-3abde5-book-bar-1920x1080.png`
- `tests/out/e2e/shots/combat-rts-input-2026-10-26T15-43-48-061Z-3abde5-book-bar-854x480.png`

These five passing E2Es and 78 GameTests are this follow-up's engineering evidence. The earlier independent
Loadouts-navigation failure and unchanged promo automation remain deferred; they were not rerun or declared fixed.

### Failure-only command feedback

The persistent-mode follow-up below supersedes the G-specific one-shot behaviour recorded in this batch.

This historical batch restored operation failures to the existing bottom status row, with a single red
message lasting three seconds. New failures replace both text and deadline; successful/sent/accepted operations
stay silent and never refresh the timer. Expiry reveals the latest persistent command state rather than a saved
copy. Tool cancellation, logout and colony/realm changes clear the transient. Failure reporting is restricted to
the active RTS tool, so background acknowledgements cannot repopulate an inactive screen.

Restored local reasons are synchronization/busy submission, select-first (then including empty G/H/J), invalid ground,
selection/waypoint limits, closed route, a loop with fewer than two distinct points, and an attempted direct-route
mode change through Ctrl append. Existing route closure remains legal at the point limit. The client shows the
existing generic rejection only for correlated route or SELECT/BIND/RECALL/RELEASE replies in its current context.
Stale/out-of-context replies and heartbeat/SUSPEND/CLEAR failures do not create text. No server packet schema,
authority check, successful acknowledgement, selection-reference rule or one-shot attack-move acceptance rule
changes. Shared book success feedback is untouched; no RTS success translation or action-bar send is restored.

The independent `rts_design_auditor` approved the final static design against the current input/lifecycle
contracts, modifier ownership, server authority, failure observability and acceptance scenarios. Runtime evidence
is separate: empty G never arms; invalid ground preserves the arm and selection; current route refusals show a
failure; stale stop/release replies do not refresh it; a new server refusal replaces the local-ground failure;
after about three seconds the transient disappears and successful orders stay quiet. Fresh control refusal
reporting is statically reviewed; the runtime control probe tests a stale reply only.

| Check                                                                | Result                                                                         |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| `./gradlew build -x test` (E2E setup)                                | Passed, all modules packaged                                                   |
| `./gradlew -PrtsTests=true -PgameTestMod=rts runGameTestServer`      | All 78 required tests passed                                                   |
| `npm run test:e2e -- tests/e2e/combat-rts-input.spec.ts --workers=1` | Passed with real-time appearance/replacement/expiry and success-silence checks |
| Combined `combat-camera` / `combat-overhead-suspend` E2E cases       | All four passed in the initial combined run                                    |
| `npm run test:i18n`, `npm run typecheck`, `npm run format:md:check`  | Passed                                                                         |

The initial input run found that the wet-ground fixture could flow into neighboring formation slots while the
new timeout checks ran. Draining that test patch and reprojecting after the screenshot resize fixed the fixture;
production command validation was retained. Final logs are `/tmp/folkways-rts-failure-only-acceptance.log` and
`/tmp/folkways-rts-failure-only-gametest.log`; `/tmp/folkways-rts-failure-only-e2e.log` preserves the initial input
failure and four passing camera/suspension cases. No test clock or HUD text was cleared to force expiry.

The final 1920×1080 game screenshots were inspected: the single red failure is readable within the paper inset,
with no stacked notification; after timeout the normal authoritative status returns without success feedback.

- Failure: `tests/out/e2e/shots/combat-rts-input-2026-10-26T15-57-31-494Z-7618ec-failure-only-1920x1080.png`
- Normal: `tests/out/e2e/shots/combat-rts-input-2026-10-26T15-57-31-494Z-7618ec-book-bar-1920x1080.png`

The loadout UX follow-up below resolves main-book Loadouts navigation; promo automation remains deferred.

### Persistent movement mode and battle-bar layout (2026-10-27)

The 80-unit layout in this historical batch is superseded by the compact-layout follow-up below; its input contract remains current.

The independent `rts_design_auditor` approved the final static design for this user-requested batch, separately
from the engineering evidence below. G now toggles a local persistent Move / Attack move input preference.
It needs no selection and sends no command; ground RMB and a first Ctrl+RMB waypoint capture the chosen policy.
LMB always selects. Successful/rejected acknowledgements never consume or overwrite the preference. Pending
replacements and queued additions retain their captured policy; a Ctrl append with the other policy fails in
either direction without changing the old route. A plain RMB creates a replacement in the current mode.
Selection, recall, Stop, Release and first Esc deselection preserve the preference. Every departure from the RTS
input screen resets it to Move, including temporary book screens, tool/top-level mode changes, closing V and
putting the book away. Context/reset paths do the same; accepted server tasks still continue through SUSPEND.

`MOVE_MODE` retains `key.folkways.rts.attack_move` as its persisted mapping ID so existing player bindings survive.
Only its displayed action changes. The old arm generation, acknowledgement consumption, G+LMB confirmation and
one-shot hint translations are removed. Four locales show both RMB ground modes with gold/bold for the current
one and gray for the other. The mode toggle and the neutral, bidirectional route-mismatch failure are translated.
The three-second singleton failure policy, generic correlated server rejection and silent successes are unchanged.

The bar now reserves 80 GUI units instead of 64 to keep its four rows separate: input mode/count, server
status/failure, centered selected portraits and their paging controls, and nine shortcut slots at the bottom left.
It still uses the book panel/oak button sprites, ink fonts and 14-unit inset. No texture copies or Core widget or
camera changes are introduced. Hit regions follow the rendered page, and the portrait row centers its actual
visible count. The larger height is the deliberate replacement for the earlier compact-height target.

The input E2E uses real key/pointer input for empty-selection toggling, repeat/modifier ownership, LMB selection,
recall/Esc mode retention, persistent accepted/rejected RMB orders, attack-move Ctrl append, both mismatch
directions, toggling back to direct movement, preference across acknowledgement, first Ctrl attack waypoint,
repeated ground RMB, release retention and tool/mode reset. It observes actual cue component colors/bold and the
bottom mode component, plus in-bounds, nonoverlapping, left-aligned groups and centered portraits at both sizes.
Failure replacement/real-time expiry and success silence remain asserted. Packet probes are still explicit
server-refusal tests, not evidence of player gestures. Hostile engagement, arbitrary terrain/scale, each context
reset combination, the separate Loadouts navigation repair and forbidden promo automation updates remain deferred.

The first combined run passed all four camera/suspension cases but failed a later input-test group-ID comparison:
new route replacements had legitimately created another squad. The assertion now compares the last accepted
squad and command, retaining the exact identity/continuation check. The corrected input run passed. The final run
also covers the preserved mapping ID. Logs: `/tmp/folkways-rts-move-mode-e2e.log` (initial),
`/tmp/folkways-rts-move-mode-acceptance.log` (corrected input), `/tmp/folkways-rts-move-mode-final.log` (final),
and `/tmp/folkways-rts-move-mode-gametest.log` (server tests). Final results and screenshot paths follow.

Final engineering results: `./gradlew :rts:compileJava` and E2E setup's `./gradlew build -x test` passed;
`./gradlew -PrtsTests=true -PgameTestMod=rts runGameTestServer` passed all 78 required tests.
`npm run test:e2e -- tests/e2e/combat-rts-input.spec.ts tests/e2e/combat-camera.spec.ts tests/e2e/combat-overhead-suspend.spec.ts --workers=2`
passed all five cases in the final run. `npm run test:i18n`, `npm run typecheck`, `npm run format:md:check` and
`git diff --check` passed. The final 1920×1080 and 854×480 screenshots were visually inspected: all nine groups
are at the bottom left, portraits/pages are centered without overlap or clipping, and input mode and status are
separate and readable. The failure screenshot also retains a readable mode label above its single red message.

- 1920×1080: `tests/out/e2e/shots/combat-rts-input-2026-10-26T18-07-47-063Z-3cf2be-book-bar-1920x1080.png`
- 854×480: `tests/out/e2e/shots/combat-rts-input-2026-10-26T18-07-47-063Z-3cf2be-book-bar-854x480.png`
- Failure: `tests/out/e2e/shots/combat-rts-input-2026-10-26T18-07-47-063Z-3cf2be-failure-only-1920x1080.png`

### Compact battle bar and pressed group slots (2026-10-27)

The independent `rts_design_auditor` approved this final static design separately from runtime verification. The bar
returns to 60 GUI units, with the existing book panel drawing 59 units (118 pixels at GUI scale 2). Input mode/count
and command/failure remain on separate lines. The bottom row shares nine left-aligned group slots with centered
portraits and paging arrows. Slot width adapts to reserve at least three portraits and both arrows at 854×480,
GUI scale 2. The symmetric portrait region begins at least eight GUI units beyond the group row, including the
previous-page arrow; larger selections use the existing paging controls. All content stays inside the 14-unit
paper inset, with no new texture, font, command or camera changes.

A matching group uses only the existing book `button_pressed` sprite. The extra red stripe and red digit are
removed; other slots use normal/hover book sprites and all digits use ordinary ink. The nonempty selection must
match the complete server shortcut membership, removing a client-tracking dependency from the visual match.
Availability counts/faces retain their tracking rules. Identical shortcut sets may both be pressed; partial
intersection and empty selection never press a slot. No last-clicked-slot state or unique active group is invented.
The hit model records the texture used for drawing so acceptance can observe the rendered choice directly.

The real-input E2E now checks exact/partial membership, numeric recall, card-click recall, duplicate groups and
same-set Ctrl unbinding against that texture. It also asserts 60-unit reservation, both resolutions' group/portrait
bounds, centered portraits, eight-unit spacing including arrows, and no overlapping hit boxes. Existing movement
mode, failure replacement/expiry, success silence and suspension assertions remain enabled. Tracking-gap visual
round trips, arbitrary resolution/GUI-scale combinations and the broader RTS roadmap remain deferred; this batch
does not claim them as runtime acceptance. At the Minecraft minimum GUI width of 320 (640×480 at scale 2), group
gaps collapse to zero to retain one centered portrait and both arrows. An extra real-input check pages forward
and back at that size. No language text changes are required.

Engineering results: `./gradlew :rts:compileJava` and E2E setup's all-module `./gradlew build -x test` passed.
`./gradlew -PrtsTests=true -PgameTestMod=rts runGameTestServer` passed all 78 required tests. The combined
`combat-rts-input`, `combat-camera` and `combat-overhead-suspend` E2E run passed all five cases; after the static
audit found and fixed the minimum-width paging issue, the input spec passed again with the new paging checks.
`npm run test:i18n`, `npm run typecheck`, `npm run format:md:check` and `git diff --check` passed. Logs are
`/tmp/folkways-rts-compact-e2e.log`, `/tmp/folkways-rts-compact-final.log` and
`/tmp/folkways-rts-compact-gametest.log`. Final screenshots were inspected at both required resolutions: the
pressed slot has no extra red mark, the panel is 118 pixels tall at scale 2, and text/controls do not clip or
overlap. The separate 1920×1080 failure capture also remains readable.

- 1920×1080: `tests/out/e2e/shots/combat-rts-input-2026-10-26T18-16-51-382Z-439f4c-book-bar-1920x1080.png`
- 854×480: `tests/out/e2e/shots/combat-rts-input-2026-10-26T18-16-51-382Z-439f4c-book-bar-854x480.png`
- Failure: `tests/out/e2e/shots/combat-rts-input-2026-10-26T18-16-51-382Z-439f4c-failure-only-1920x1080.png`

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

Proceed from validating this iteration's off-screen tasks to improving existing attack-move, bounded post-combat maintenance, explicit rest, then native ranged moving fire. Acceptance includes closing V without shortcuts, putting away the book beyond 100 ticks, reopening the same generation, explicit deselection/rebinding and real authority-loss cleanup. Later cases cover mandatory-stop continuity, gamerule-disabled healing, real food/ammunition costs, maintenance interruption, rest resumption and single navigation/weapon ownership. Strategic AI, PvP, RTS-framework migration and gun modules remain outside scope.

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

### Colony equipment profiles

`LoadoutProfiles.of(colony)` owns multiple immutable `LoadoutProfile` records: UUID, editable name, ordered `Entry` records and optional legacy selection data. Each entry has its own UUID, `WEAPON` / `ARMOR` / `SUPPLY` kind and component-aware `ItemSpec`. Weapons and armor target one physical item per entry; armor names one of head/chest/legs/feet. Supply entries store the player’s target and threshold, with `0 <= threshold <= target <= 4096`. Duplicate and overlapping entries consume distinct physical units. Matching includes components during preview, withdrawal, settlement, loaded-ammo counting, deficit reporting and retention.

The existing colony ledger owner `folkways:patrol_loadouts` now stores `version: 4`, `profiles` and `assignments`. Profile rows contain `id`, `name`, `entries`, and optional `legacy` (`loadout`, `options`); entries contain `id`, `kind`, `items`, optional `slot`, `target`, and `threshold`. Assignment rows contain `resident` and `profile`. Unloaded residents retain assignments; departed residents lose assignments without deleting reusable profiles. Deleting a profile unassigns everyone using it. Duplicating a profile copies entries without copying assignments.

Version-one enum names and version-two `Selection` IDs/options migrate once. Identical selections deduplicate into one profile. Native bow/crossbow selections become a weapon entry plus arrows targeting eight, with threshold eight to retain the former below-eight behavior. Unknown integration IDs/options remain in assigned, visibly unavailable profiles and survive rename, duplicate and repeated saves. ItemSpec data with an absent component codec is preserved verbatim as an unavailable entry through rename, duplicate and save, and decodes again when the codec returns. A returning provider can resolve them through `legacyWeapon` and `legacySupplies`; extension quantities are not forced to the native arrow default. `PatrolSupplies.loadout` / `preference` remain compatibility helpers over migrated profile assignments, not a second resident preference store.

`LoadoutProvider` describes an actual stack: `accepts`, inventory-aware `usable`, `ranged`, `range`, `indirect`, `longRange`, optional ammunition `ItemSpec`, `magazineSize`, component-aware `roundsLoaded`, `loadedAmmunition`, `cycleTicks`, and `suggestedAmount`. Adding a weapon prefills an editable supply entry from these parameters. Suggested quantities never control later collection. Gun modules register this metadata and their actual `WeaponAdapter` / `WeaponSession` execution separately; no fake gun behavior is supplied. `profile.hasRanged()` queries profile contents without equipping a resident or requiring ammunition.

The colony book’s **Loadout profiles** page is available without RTS. A paged profile list with naming actions sits beside the selected profile's summary and compact equipment rows; narrow windows stack the panes and allow horizontal equipment scrolling. **+ Add** opens one menu for weapons, typed variants, supplies, observation gear and four armor slots. Every entry shows its icon, name, short shortage status and target; only supplies expose an editable target, refill threshold and one save button. Reorder, remove and repick use stable entry IDs. Hovering shortage status distinguishes assigned residents' missing items from warehouse deficits. Stock previews observe registered-store permissions and reservations without reserving items.

**Assign residents…** opens a book-style paged resident list with names and current work/status. Choosing a loadout or No loadout applies immediately. Each request rechecks keeper authority, colony membership, expected previous assignment and target profile revision; rejected changes show a notice and retain authoritative row values. Unloaded people retain assignable rows. Closing the window does not undo accepted changes. Deletion clears assignments. Add/repick dialogs capture profile/entry identity and revision when opened.

The loadout ledger migrates retired exact-component and enchantment pins recursively before component decoding, so missing enchantment codecs cannot strand plain items/tags. Typed variant and ammunition components remain. TACZ held-attachment predicates are removed while GunId remains. Converted records are saved as version 4; generic Core ItemSpec components remain available elsewhere. The page rejects retired exact, enchantments and held_weapon actions. Patrol no-profile Flee/engage policy and collection lifecycle retain their contracts.

### Loadout page UX design audit (2026-10-27)

The independent `rts_design_auditor` approved this scoped static design and the necessary navigation repair: `PageKind.inNavigation()` opts only Loadouts into main-book navigation while other secondary pages keep their window behaviour. Accepted changes include two panes with narrow stacking, compact rows with accessible horizontal scrolling, one add menu, immediate per-resident assignment, stable dialog identities, expected-assignment and target-profile revision checks, visible rejection, and pre-decode pin migration preserving typed gun/ammunition identities. Input, modifiers, selection, commands, server authority and existing collection/session lifecycle contracts remain current. This audit is separate from engineering tests and real-client layout acceptance.

A link from the main resident list and bulk assignment are deferred; the dedicated popup supplies the scoped workflow. Patrol/sentry/route/station staffing and the broader RTS roadmap remain outside this batch. Acceptance covers paging/reordering, valid and invalid supply amounts, replacement, per-resident/no-loadout assignment, deletion, unload persistence, stale/foreign/revoked requests, removed action rejection, migration/reload, and Chinese/English layouts.

Engineering verification: `./gradlew build` passed; `npm run test:unit` passed 141/141 and `npm run test:i18n` passed 8/8. Core GameTests passed 484/484, Vanilla with TACZ installed 769/769, TACZ 42/42 and Warfare 38/38. The affected `combat-page.spec.ts` and new `loadout-page.spec.ts` each passed with one client at a time. The latter exercises the add menu, replacement/reordering, explicit amount save, immediate assignment/no-loadout, deletion, profile paging, a long profile name and target 4096. Real-client Chinese and English wide layouts and English narrow horizontal access were captured and inspected; row clipping and long button text overflow found during that inspection were fixed and recaptured. These are UI acceptance results, not a claim of full gameplay validation. The optional Warfare test world used temporary memory-backed storage to avoid slow synchronous disk flushing after all assertions passed; production configuration is unchanged.

### Finite profile collection and weapon choice

Assigned people collect their own missing profile items through `PatrolSupplies` / `PatrolSupplyNode`. Ordinary residents and drawn patrollers can collect; guard orders retain their existing collection authority. Other manual orders, failed waiting and ordinary selection reservations do not create a maintenance detour. Collection yields to combat, fear and retreat, walks to registered storage within 32 blocks, and rechecks permissions, reservations, components, capacity and stock on arrival. A trip is limited to 200 ticks; stockouts and full packs retry after bounded backoff. New orders or profile revisions revoke stale trips. Refilling starts strictly when carried supply is below threshold and remains latched across partial trips until target. Actual loaded ammunition counts once, with its components. Nothing spawns equipment or repairs durability.

`Weapons.select(worker, target, current)` considers the actual hand loan and carried profile weapons in order. Ready ranged weapons serve ranged targets with sight; nearby targets or exhausted ammo select melee, with existing usable carried tools/equipment as fallback. The old session closes before another opens. Both `StrikeNode` and RTS `AttackNode` use this choice and target-specific approach ranges. Native sessions continue checking hostility or explicit-target authority, sight, range, ammunition and lifecycle; wounded fear still outranks combat.

Core `Equipment` owns the four armor slots and refuses incompatible physical items. Its demand flag `automaticSupply=false` lets profiles use finite resident collection instead of ordinary kit delivery. An unrelated player-given armor piece is never overwritten; the blocked slot is visible. Invalid armor and weapons without a provider report unavailable. Offhand/shields remain deferred because the existing equipment owner supports humanoid armor only. Native hand sessions and profile collection currently serve `ResidentEntity`; other ResidentKinds keep their existing extension-owned ToolUse/barehanded execution.

Patrolling declares no weapon kit or tool-list editor. Optional `KitRules.defaultTool` / `defaultTools` and `CoreSettings.defaultTool` were removed; bow/crossbow permissions are no longer injected. Combat collection uses profile goods, independent of saved trade tool permissions. Non-combat trades retain their existing whitelist and automatic tool behavior. Drawn ward work itself has no weapon tool gate; fighting still requires the combat policy and usable equipment. Existing player-given weapons remain physical possessions. Core’s independent `KitRules.supplyPolicy` remains for ordinary delivery lifecycle coordination.

### Equipment profile design audit

The independent `rts_design_auditor` granted final static design approval for Vanilla ownership of profiles, display-only RTS equipment information, unchanged input/modifier/selection/order contracts, server keeper checks on every mutation, revision-checked edits, complete bulk-membership validation before mutation, and collection/session revocation without replacing routes. Acceptance scenarios cover two physical sets, component-pinned refusal, strict threshold and partial refill, close-range/ammo weapon switching, no-profile policy, migration deduplication/unknown IDs, invalid equipment, occupied armor slots, stale UI authority and unchanged guard handles. Engineering GameTests are separate evidence; this audit is not real-client gameplay validation.

Offhand ownership, new ResidentKind native hand integrations, other gun modules, moving fire, maintenance and the existing RTS roadmap remain deferred. The loadout UX follow-up validates the live Vanilla editor/assignment round trip and RTS display; its engineering evidence is recorded above.

## Sentry posts, observations and patrol help

Vanilla's patrol trade owns the `folkways:sentry` box delegation and the **Sentry posts** book page.
Draw a small zone with the existing zone tool, then use that page to post or unpost residents.
The existing drawing modifiers, RTS selection shortcuts and direct orders keep their meanings.
Only native people with an assigned profile whose `hasRanged()` is true may be posted; ammunition
is not required for assignment. Other resident kinds report an explicit unsupported-kind refusal
until their owning integration provides native hand/station operation.

Capacity is `min(safe standing cells, max(1, floor(horizontal area / 4)))`. Vertical layers cannot
increase the horizontal area. A drawn cell can identify the feet cell or its supporting block;
posted feet stay in that column, with passable headroom and safe footing. Four floor cells hold
one resident; a 5×5 floor holds six if it has six safe stances. Posts never chase targets.
Desired resident-to-zone assignments persist under `folkways:sentry_posts`, using `assignments`
rows containing `resident` and `zone` UUIDs. Unloaded residents retain their assignment. Removal
of the resident or zone removes it. Invalidated profiles and reduced capacity suspend operation,
with a reason on the zone card, rather than silently changing the desired assignment.

`SpottedTargets.of(colony)` keeps bounded, transient UUID/entity-ID/dimension/position/tick
observations. Sight is now observer–target dependent: the baseline and vigilance feed the
height/light/physical-optics formula described under Dynamic pairwise sight below.
Actual line of sight is required. A defending
resident reports every hostile it sees through `Threats.hostile`, with no facing restriction.
Only a genuine report refreshes a hidden position. Death, unload, expiry and loss of hostility
invalidate reports. Neutral anger is checked again against the defender using the report.
An indirect shot also refuses a target displaced more than two blocks from its observed position.
No query loads a missing chunk or persists observations.

A sentry uses an available ranged profile weapon, including with a mixed melee/ranged profile,
and never borrows a station merely contained by its zone. Independent entity posts use their own staffing slot. Effective range comes from `LoadoutProvider`.
Own-sight fire requires that range; shared observations allow fire beyond the operator's sight
radius. Direct weapons still require their own line of sight and an ally-clear shot at discharge.
`LoadoutProvider.indirect(stack)` opts into indirect selection; shared-fire adapters must implement
`WeaponSession.spottedTarget(target, observedPosition, authorized)` and check that live permission
immediately before every discharge. Native bow/crossbow sessions support the observation lease
while retaining their own direct trajectory and finite ammunition. Retargeting cancels the old
shot. Each new indirect shot binds an immutable report; hidden entities never supply new aim
coordinates. Healthy sentry combat uses priority 2.5; idle duty uses 1.5 and yields to fear,
collection and deliberate RTS control. Wounded people remain subject to fear.

Drawn patrollers acquire enemies using sight rather than the former fixed twelve-block scan.
Indirect-capable defenders can acquire an unseen shared target on their own. A direct ranged provider can
opt in with `longRange(stack)`; shared acquisition then requires own line of sight and current effective
range, and adds no pursuit range. Native providers leave `longRange` disabled. Both flags require
the session's live observation lease. A sentry can call
nearby, healthy, armed route workers when a visible threat exceeds its weapon range or reaches
melee distance of the post. Calls pick nearest responders, cap their number per target and expire
within a bounded duration. Fighting residents, posted sentries, manual orders, selection
reservations and failed waiting are excluded. A call supplies a combat urge to the existing
runner; it does not finish a watch, rebuild a lap or replace the route. Target loss, stale reports,
timeout or new direct control revoke the call, allowing the unfinished route to resume. RTS GUARD
retains its separate twelve-block slot/stance leash and its existing defensive-only behavior.

`WeaponStations.register(id, finder)` is a Vanilla-only integration hook. A finder returns stable
`Station` instances for fixed weapons in a zone. A station declares its `WorldPos`, effective
range, indirect flag, ammunition `ItemSpec`, optional cycle ticks/validity and firing origin, synchronous `operate`
and `release` callbacks. Vanilla owns exclusive leases and removes one real matching ammunition
unit for each successful operation; integrations must not consume that unit again. False operation
consumes nothing. The default origin is 1.5 blocks above the station cell; integrations can supply their
actual muzzle position. Station range, terrain and ally checks use that origin, including the operator if it
stands ahead of the muzzle. There is no concrete station or free ammunition in Vanilla. A lease is released
on departure, preemption, invalid profile, missing station, death, unload, disenrollment, zone
removal and colony closure. The GameTest station is a test double, not a shipped weapon.

The zone card displays posted and refused residents, reasons, the global sight cap and current targets.
Hovering or selecting a post shows server-described maximum sight bounds, including each resident's
vigilance and physical optics; actual discovery still depends on pairwise height and target light.
The assignment page reports unavailable residents, full posts, unsafe footing, direct control,
missing weapons/ammunition, blocked shots and station failures. Mutations pass through the book's
current keeper-authority checks and revalidate zone ownership and colony membership on the server.
Patrol currently has no Ponder folder, so no separate sentry lesson is registered.

All new server options are under `[sentries]` in Vanilla's server config:

| Key                    | Default | Meaning                                        |
| ---------------------- | ------- | ---------------------------------------------- |
| `maxSentryCells`       | 25      | Maximum drawn volume in cells                  |
| `defenderSightRadius`  | 32      | Sight radius before vigilance, blocks          |
| `spottedTargetTtl`     | 100     | Maximum observation age, ticks                 |
| `maxSpottedTargets`    | 128     | Maximum observations per colony                |
| `sentryCallRadius`     | 48      | Maximum responder distance from caller, blocks |
| `sentryCallResponders` | 2       | Maximum responders per target                  |
| `sentryCallTicks`      | 200     | Maximum duration of a diversion, ticks         |

### Sentry design audit and acceptance

The independent RTS design audit checks unchanged input/modifier ownership, current keeper
authority, manual-order and reservation precedence, route continuation, immutable observations,
finite ammunition, exclusive station lifecycle and visible failure states. The independent
`rts_design_auditor` granted final static approval, including the explicit `longRange()` opt-in.
Accepted changes are the patrol-owned post, effective/suspended assignment rules, circular sight,
immutable colony observations, discharge-time permission and friendly-fire checks, bounded calls
through the existing runner, fixed-weapon leases and book/card/ring feedback. Existing inputs,
selection reservations, manual orders, failed waiting and GUARD leashes remain intact. Static
review and server GameTests are separate from real-client gameplay validation.

Acceptance scenarios include melee-profile refusal and assignment round trips; actual bow fire
when a hostile enters sight; an out-of-range crossbow post calling a route worker and its return;
a scout-only observation used by an indirect test station; expiry and hidden displacement blocking
fire; an ally entering the direct shot corridor; and exclusive station release on departure,
death and unloading. Other native resident-kind adapters, other concrete weapon integrations, moving fire, maintenance and strategic AI remain deferred. A live client
review of post cards and rings remains separate acceptance work.

### Sentry engineering results

On Linux with JDK 21, the final sentry implementation passed the following commands. Gradle used
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and the isolated cache
`GRADLE_USER_HOME=/tmp/folkways-sentry-gradle`.

| Command                                       | Final result                                |
| --------------------------------------------- | ------------------------------------------- |
| `./gradlew build`                             | PASS; 44 tasks, no failures                 |
| `./gradlew runGameTestServer`                 | 1,233 required tests passed, 0 failed       |
| `./gradlew -PrtsTests=true runGameTestServer` | 1,324 required tests passed, 0 failed       |
| `npm run test:i18n`                           | PASS; 1 file containing 8 cases, 0 failures |
| `npm run typecheck`                           | PASS; all 7 TypeScript projects, 0 errors   |
| `npm run format:md:check`                     | PASS; 0 files needing formatting            |

Eleven new Vanilla sentry tests and one RTS interaction regression cover the accepted scenarios.
The long-range metadata test checks an actual emitted projectile and real ammunition consumption;
it does not establish ballistic accuracy for arbitrary integration weapons. An earlier combined
run failed `aCampfireBurnSendsTheResidentAway` once; the final base and RTS runs passed it without
changing that test or its production behavior. There are no failing test names in the final runs.
Live-client post-card/ring acceptance remains unrun.

## Profile staffing and autonomous patrol teams

Vanilla owns `PatrolStaffing` under `folkways:patrol_staffing`. Each drawn sentry zone and
patrol route has **Allowed loadouts** and one **Headcount** in its own configuration panel.
The multi-select popup lists all colony profiles. An empty allowed set selects nobody;
zero headcount disables staffing and never restores individual legacy laps or manual postings.
Sentries still need ranged equipment and safe stance capacity; incompatible choices remain
visible as shortages. Routes accept any allowed assigned profile. There is no resident pinning
or separate staffing book page. Route **Retry** remains in the route panel.

Every explicitly enrolled weapon station automatically requests one otherwise idle combat
resident with any assigned loadout, including melee or supply-only profiles. Station enrollment
and its exact native entity UUID remain mandatory. The **Sentry posts** overview shows station
identity, operator and operational failures, and offers **Withdraw station** even while unloaded.
No per-station profile or post configuration exists. `StationPost` is only a transient geometry
adapter for the existing runner, not a saved staffing post.

Reconciliation first reserves incumbents across every target, then fills vacancies at UUID-sorted
stations before UUID-sorted zones/routes; candidates are UUID-sorted. New stations never steal
valid zone/route incumbents. A resident serves only one target. Missing members retain their claim
for 200 ticks. Direct control, selection reservations and failed waiting suspend effective duty
without replacing manual handles; live duty checks revoke weapon sessions and station leases.
Death, departure, trade loss and incompatible profile assignments release automatic membership.
Capacity and ranged-capability loss remain visible suspensions. Missing ammunition, occupied
seats, blocked shots and inaccessible footing remain separate operational failures.

Saved target rows now contain `allowed` profile UUIDs, `count` and internal `positions` with stable
identity and optional incumbent membership. Positions preserve stance and delayed-duty identity;
players do not configure individual positions. Shrinking headcount rechecks live eligibility and removes
vacancies before valid incumbents; shifted stance identities revoke obsolete duty work. Runtime station incumbents persist separately as
`operators`, while station configuration is derived exclusively from enrollment. Old `slots` load
as profile union plus slot count; `pin` is discarded, while valid incumbent `member` claims can
remain. Old station slot/profile posts are discarded and enrolled stations become automatic.
Earlier `sentry_posts.assignments` migrate into aggregate allowed profiles and count, then clear.
Unknown/deleted profile IDs remain visible until the picker replaces them. Profile-page
`shortageFor(profile)` means compatible unmet demand, shared across allowed profiles and stations;
it must not be summed across profiles as distinct mandatory requirements.

Each picker/count edit rechecks the held-book keeper authority, colony, selected target,
dimension and configuration revision. Popups capture their original target so stale edits cannot
apply to a newly selected target. This batch changes no RTS shortcut, modifier, selection,
command packet, loadout editor or resident loadout-assignment workflow.

`plugins.combat.group.GroupDeployment` supplies formation geometry. `GroupRoute` supplies the
logical progress anchor, bounded path/slot planning, safe connected formation cells, preserved
member offsets and local regroup-anchor search. Callers provide their own destination validator.
RTS `Deployment` and `PointRoute` adapt these APIs to the existing command validator; RTS keeps
its command handles, mandatory-stop lifecycle, control ownership and `folkways:unit_groups`
ledger schema. `GroupMotion` adds autonomous assembly, loose transit cohesion, combat pause and
reformation without owning a navigator. `PatrolTeams` supplies ordinary runner work in Vanilla.

Composed routes assemble and walk with their filled members even when other slots are short.
They watch each stop, turn at the route ends and keep their unfinished stop through combat or
sentry diversions. One member's valid engagement pauses the team; members retain native
hostility, sight, ammunition and weapon-session checks. They return to the retained formation
and assemble for 20 ticks before continuing. Fear and finite supply collection can borrow the
same runner. Manual orders, selection reservations and failed waiting remain authoritative.
Sentry calls sort teams and legacy individuals together by distance, breaking ties by UUID.
A team's distance is its nearest filled member, and all its members must be inside the call radius.
Calls count each whole team as one responder unit, retain the existing duration bound,
and resume the original route after target loss or expiry. A straggler waits at most 200 ticks
per attempt, with two formation replans; exhausted recovery or 1200 effective transit steps
shows a blocked team and releases its autonomous movement work instead of skipping the stop.
The route configuration panel offers **Retry route**; changed membership also permits recovery.

### Aggregate staffing design approval

The independent `rts_design_auditor` approved the implemented aggregate model after checking
current input/lifecycle contracts, modifier ownership, server authority, target/revision sealing,
incumbents-first/stations-first allocation, unload grace, RTS suspension and lease revocation,
observable shortages, and retry/withdrawal accessibility. Migration preserves actual incumbent
members while dropping pins and old station profile restrictions. Accepted scope is this staffing
flow and its migration; loadout editing/assignment, strategic AI, new RTS commands, moving fire,
and the broader roadmap remain outside this batch. This is a static design approval, separate
from GameTests and real-client acceptance. The following pin-era audit/results are historical;
the aggregate contract above defines current behavior.

### Aggregate staffing verification (2026-10-27)

Engineering checks are separate from the static design approval above. With JDK 21:

| Check                                                                        | Result               |
| ---------------------------------------------------------------------------- | -------------------- |
| `./gradlew build`                                                            | PASS                 |
| `npm run test:unit`                                                          | 141 passed, 0 failed |
| `npm run test:i18n`                                                          | 8 passed, 0 failed   |
| Core GameTests (`-PgameTestMod=core`)                                        | 484 passed, 0 failed |
| Vanilla + RTS GameTests (`-PrtsTests=true -PgameTestMod=vanilla`)            | 786 passed, 0 failed |
| Warfare GameTests (`-PwarfareTests=true -PgameTestMod=warfare`)              | 38 passed, 0 failed  |
| TACZ GameTests (`-PtaczTests=true -PgameTestMod=tacz`)                       | 42 passed, 0 failed  |
| Affected client specs (`patrol-blackbox`, `profile-staffing`, `tacz-combat`) | 3 passed             |

GameTest commands run `./gradlew <flags> runGameTestServer`. Final Vanilla/Warfare runs used
an external temporary Gradle init script setting only `gameDirectory` to
`/tmp/profile-staffing-gametest/<suite>` to avoid slow synchronous disk saves.
Client commands use `flock /tmp/folkways-e2e-gpu.lock npx playwright test --config
 tests/e2e/playwright.config.ts <specs> --workers=1` (one client at a time).
The actual client checks cover multi-selection, total shortage, zero-count release, ranged
restriction, and English/Chinese panel layout. PNGs were opened and checked for clipping,
overlap and empty content. The separate one-off native station overview screenshot failed
at client startup with OpenGL 0.0 even on one retry under the shared GPU lock; that native
client screenshot remains unverified. Warfare's 38 server tests passed. No launcher or
OpenGL configuration was changed to work around the failure.

### Staffing design audit and acceptance

The independent `rts_design_auditor` granted final static design approval after reviewing stable
incumbent retention, immutable call permissions, whole-team diversion, bounded recovery, paged
editing, nearest-responder selection and deliberate-control precedence. Accepted scope is
staffing, pins, shortage feedback, autonomous route teams and shared navigation/formation
primitives. Engineering verification is recorded separately below. New GameTests
cover exact-profile filling and shortage, stable assignments, pin reload/mismatch, ranged-only
sentry slots/capacity/exclusivity, death refill, partial-team physical movement, combat/regroup/
unfinished-stop continuation, whole-team calls and legacy individual laps.

Live-client review of staffing pages/cards and formation movement remains separate acceptance
work. Gun integrations, strategic AI, moving fire, maintenance and the existing broader
RTS roadmap remain deferred.

### Staffing engineering results

The staffing batch uses Linux/JDK 21 with
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and
`GRADLE_USER_HOME=/tmp/folkways-sentry-gradle`.

| Command                                                         | Final result                                |
| --------------------------------------------------------------- | ------------------------------------------- |
| `./gradlew build`                                               | PASS; 44 tasks, 0 failures                  |
| `./gradlew runGameTestServer`                                   | PASS; 1,247 required tests, 0 failures      |
| `./gradlew -PrtsTests=true runGameTestServer`                   | PASS; 1,339 required tests, 0 failures      |
| `./gradlew -PrtsTests=true -PgameTestMod=rts runGameTestServer` | PASS; 77 required tests, 0 failures         |
| `npm run test:i18n`                                             | PASS; 1 file containing 8 cases, 0 failures |
| `npm run typecheck`                                             | PASS; 7 TypeScript projects, 0 errors       |
| `npm run format:md:check`                                       | PASS; 0 files needing formatting            |

Fourteen new base staffing GameTests and one optional RTS regression cover the requested cases,
plus unload grace, pin exclusivity, deleted/edited profiles, capacity suspension, stable refill,
blocked recovery, paged access past 32 rows, current keeper authority and live call revocation.
The RTS regression checks that manual takeover revokes the entire call without replacing the
manual handle. Existing RTS tests are unchanged. `WeaponStations`, `SpottedTargets` and
`LoadoutProvider` are unchanged.

The initial `sentryCompositionRejectsMeleeAndEnforcesCapacityAndExclusivity` failure exposed an
incumbent-stealing fill pass; global incumbent reservation fixed it. A later extension of the
same test initially left safe lower footing in its fixture; removing both supports made the
capacity-loss assertion valid. All final runs have zero failing test names. No patrol Ponder lesson is
registered in this tree, so there was no lap narration to update. Live-client acceptance and
arbitrary-terrain/scale evidence remain separate from these server tests.

## Superb Warfare port and design audit

`mods/warfare` registers `folkways_warfare`, depends on Core and Vanilla, and uses
Superb Warfare 0.8.9.2 plus Kotlin for Forge 5.12.0, pinned with SHA-512. It has no
RTS dependency. The old branch's own commits are `583a57b5`, `0432e652`,
`dfa5c07e` and `162fb737`; the already present shared API work from `0b829c87`,
`73356f1d` and `20b5d113` is not reapplied.

Accepted scope is the six actual handheld guns, item-usage metadata, finite
profile ammunition, fixed station integration (now explicitly staffed entity posts), indirect mortar and
FH77BW observations, direct station sight, native delayed emission, ballistic
and impact refusal, explosion protection and temporary-seat cleanup. The old
16-round selection, two-shell duty allowance, trade tool registration, guard
radius and independent station urge are removed. Profiles own editable supply
targets and thresholds. Players add a ranged weapon and explicit shell supplies
to the posted resident's profile; [Warfare setup](warfare.md) gives the steps.
Loaded gun rounds count as physical stock and are consumed before loose rounds.

The Vanilla station API retains synchronous `operate` and adds optional delayed
`engage`/`Discharge` execution. Preparing a native shot consumes nothing. The
captured single-use transaction rechecks its exact lease, duty, immutable Spot,
range, direct sight, operating reach and integration trajectory safety at native
emission. It reserves a matching physical round, passes that actual variant to
the integration, refunds failed insertion and starts cooldown only on success.
Mortar slot copies exist only within a synchronous native call, never between
ticks. Native queued vehicle work captures the original pending request; unrelated
player queues pass through unchanged. Actual muzzle prediction includes
configured dispersion, entity blast radius `2 * radius` and separate block
radius `.6 * radius`. A one-degree aiming allowance accommodates SBW's turret
settling; prediction still traces its actual direction.

Vanilla owns temporary seats through `WeaponStations.Lease`, persisting only
vehicle/lease identity under `folkways:station_seat`. Live duties never resume.
After passenger restoration, orphan cleanup also recognizes the old
`folkways:command_mount` marker and releases only the recorded seat. Station
removal, unposting, invalidated profiles, revoked patrol trade, preemption,
death, unload and colony closure close leases. Existing arrival tolerance also
applies when returning from supply collection; native mounted seats remain at
their independently enrolled station instead of trying to navigate back to an ordinary stance.
Only independent-post safe stances prefer their own station; integrations can refuse operators
outside their operating reach. Optional RTS recognizes only these owned seats
for selection/command validation and releases them after complete validation.
Invalid commands preserve the current duty. No inputs or modifier ownership change.

The independent `rts_design_auditor` granted final static design approval after
reviewing input/lifecycle contracts, server authority, modifier ownership,
observable failures and acceptance scenarios.
The queue audit found and corrected interception of unrelated player firing;
component-aware loaded counts and discharge-time reach/refund identity were also
reviewed. Static design review is separate from engineering results below and
from live-client gameplay acceptance.

Acceptance includes scout-only mortar emission; ally and own-block impact holds;
outside-zone exclusion; immutable/displaced observations; finite magazines,
cadence and shell-profile logistics; delayed cancellation; exclusive seat,
retreat/trade/death/unload/removal cleanup; snapshot and old-marker orphan
release; failed projectile-insertion refunds; and permission/Start/Detonate
protection. The optional Vanilla `warfareTest` source set exercises its real
patrol observation path without exporting internal services.

AP, TOW, vehicles, unknown projectiles, perks and property-overridden weapons
remain excluded. Other gun integrations, new ResidentKinds, moving fire and the
existing RTS roadmap remain deferred. The retained ballistic approximation does
not establish accuracy for every emplacement, terrain or intermediate hostile
collision; explosion-time safety remains mandatory. Broader animation, post-card
and every-emplacement acceptance remains separate from the handheld skin-pose
verification below.

### SBW resident handheld arm poses

SBW 0.8.9.2 registers `GunGeoItem` client extensions whose `getArmPose` delegates
to the gun's own pose method. `PoseTool.pose` selects `BOW_AND_ARROW` for ordinary
aim and `CROSSBOW_CHARGE` while reloading, charging or grounded sprinting; some gun
subclasses override the pose. Minecraft's player renderer queries this extension.
The resident skin renderer did not, and Warfare had no `ResidentWeaponPoses`
provider, so residents kept ordinary item arms despite their real held gun.

`WarfareClient` now registers a client-only provider, accepts stacks through the
existing adapters and queries the native item extension. A reset humanoid model
evaluates the returned pose, main arm, head aim and animation phase. Its two arm
rotations feed the existing skin/Geo pose interface; the skin renderer copies
them to sleeves. No gun names or arm-angle tables are added to the renderer.
Future guns accepted by the adapter list use the same bridge.

As with TACZ, the state source is the server-synchronized actual main hand:
`claimWeaponHand` lends the gun during combat and `releaseWeaponHand` removes it.
The client does not inspect the server-local session field or independently
authorize aiming, targets or shots. A raised gun does not establish that the
current shot passed sight, finite-ammunition or friendly-corridor checks.

The independent `rts_design_auditor` approved this static design with no blocking
findings. Accepted scope preserves current input/modifier ownership, selection,
commands, server authority, visible failure states and session revocation. The
acceptance fixture covers native arm rotations, sleeves, supported and refused
stacks, real sentry engagement and hand release. Engineering results and actual
screenshots are separate evidence. Geo/maid/slim appearance combinations,
multiple clients and sprint/reload/riding/swimming visual combinations remain
deferred; this change does not expand weapon support or the RTS roadmap.

Engineering verification on Linux/JDK 21 passed `./gradlew build`, all 28 required
tests in `./gradlew -PwarfareTests=true -PgameTestMod=warfare runGameTestServer`,
`npm run test:i18n` and the visual suite's TypeScript check. The final
`npm run visual:take -- weapon-poses.spec.ts --workers=1` passed its real-client
case. All six adapters, both main arms and three head pitches produced 36 native
model comparisons with maximum error **0**. Actual AK-47/M870/TACZ M4A1 sentries
raised both arms, damaged their targets and cleared the pose on session release;
sampled arm/sleeve errors were also **0**. The three screenshots were inspected.

Artifacts live under
`tests/out/visual/playwright/weapon-poses-SBW-residents-00154-them-on-release-beside-TACZ/`:
`ak_47-aiming.png`, `m_870-aiming.png`, `tacz_m4a1-aiming.png`,
`native-variants.json` and `combat-poses.json`.
The `weapon_poses` profile reuses the existing, unchanged offscreen capture
fixture to report the active EGL context's real GL version. Initial launches
failed SBW's GL feature check without it; an early-window experiment also failed
context initialization and was removed. A later fixture run timed out waiting
for all three poses because Minecraft clamps target health to 1024. The final
fixture records real damage and heals targets each tick, retaining finite real
ammunition and native weapon sessions. These startup/fixture failures are not
counted as successful gameplay checks.

### Warfare engineering results

On Linux with JDK 21, the port passed the following commands. Gradle used
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and
`GRADLE_USER_HOME=/tmp/folkways-sentry-gradle`.

| Command                                                                 | Result                                      |
| ----------------------------------------------------------------------- | ------------------------------------------- |
| `./gradlew build`                                                       | PASS; 51 tasks, 0 failures                  |
| `./gradlew runGameTestServer`                                           | PASS; 1,235 required tests, 0 failed        |
| `./gradlew -PwarfareTests=true runGameTestServer`                       | PASS; 1,261 required tests, 0 failed        |
| `./gradlew -PwarfareTests=true -PgameTestMod=warfare runGameTestServer` | PASS; 26 required tests, 0 failed           |
| `npm run test:i18n`                                                     | PASS; 1 file containing 8 cases, 0 failures |
| `npm run typecheck`                                                     | PASS; 7 TypeScript projects, 0 errors       |
| `npm run format:md:check`                                               | PASS; 0 files needing formatting            |

The additional command
`./gradlew -PrtsTests=true -PgameTestMod=rts runGameTestServer` passed all 78
required tests, including the new station-seat retreat regression, with 0 failures.

The 26 Warfare tests include actual native mortar and Mk42 emissions. Two new
Vanilla tests cover delayed ammunition settlement and owned/legacy seat cleanup.
There are no failing test names in these final runs. Development iterations
corrected resident enrollment order, ordinary arrival tolerance, invalid fixture
stack size and the exact SBW vehicle overload hook. These are server-side results,
not real-client gameplay validation.

### Staffing integration after the combat-base rebase

The Warfare branch retains Vanilla's shared `GroupDeployment`, `GroupRoute` and `GroupMotion` ownership.
Composed area sentries use authored slot indices within ordinary stance ordering, including vacancies before a
filled slot. Independent station posts order approach stances toward their bound entity. Sight rings use the same stance unless the resident owns a station seat, whose actual position is used.
Zones without compositions retain explicit resident posting and its existing acceptance tests.

Station permission checks the current assigned profile against the slot's required profile before delayed native
emission, even before the next staffing reconciliation. A mismatch exposes `folkways.sentry.profile_mismatch`;
reconciliation retains the desired pin with a mismatch status and profile shortage. Each sentry node captures its
stable slot UUID, and replacement slots create new duty identities. An old queued shot cannot borrow a replacement
slot's permission. Service maintenance releases revoked nodes and their exclusive station leases.

The independent `rts_design_auditor` granted static design approval for these integration changes. Accepted scope
includes unchanged input/modifier ownership, keeper-authorized staffing edits, server authority, manual-control
precedence, finite ammunition, emission-time safety and visible failure states. Focused regressions cover sparse
slot stance/ring alignment, replacement duty invalidation and a pending mortar shot revoked by switching between
two ranged profiles. Engineering results are recorded separately below. Real-client staffing, cards, rings and
station gameplay acceptance, arbitrary terrain/scale and the wider RTS roadmap remain deferred.

Engineering verification after this rebase reports 1,250/0 passed/failed base GameTests, 1,277/0 with Warfare
installed and 27/0 in the Warfare-only suite, using the three corresponding commands below. `./gradlew build`
passes with 51 actionable tasks; `npm run test:i18n` passes 1/0 and `npm run typecheck` passes all seven configurations.
Markdown formatting and changed-Java line lengths also pass. The first combined run failed
`staffingProfileMismatchRevokesPendingMortarBeforeReconciliation` because the fixture expected preparation on its
first tick. Waiting for native preparation before changing the profile fixed it; the final runs have no failing tests.

## TACZ profiles and sentry integration

Optional `mods/tacz` (`folkways_tacz`) ports PR #67's native `IGunOperator` execution,
physical calibre-specific ammunition, bounded scripted reloads, session-generation
burst guard, explosive/incendiary attachment and final-payload rejection, allied
shot hold, in-flight colony protection, per-gun caps and holding poses. Its only
Folkways dependency is Vanilla (and transitively Core), never RTS. The existing
shared-API commits were already present and were not replayed.

`TaczLoadouts` implements the item-usage provider: supported actual guns, exact
`custom_data.AmmoId`, direct ranged classification, gun-data range bounded by the
existing caps, magazine/loaded-round metadata and RPM/bolt/burst cycle ticks.
Three magazines are only a suggestion for a newly added supply row. Player profile
targets and strict resupply thresholds replace the old global 32/8 behavior.
The profile page offers component-bearing gun models through + Add / Weapon variants.
GunId remains fixed while conventional attachments and magazine/chamber state may vary.
All actions retain current keeper authority and profile revision validation.

A ranged TACZ profile qualifies for a sentry zone even before a gun or ammunition
is collected. The existing runner performs finite profile collection and direct
fire without pursuit. Sessions bind the sentry's live observation permission and
recheck it, hostility, own line of sight, effective range and ally clearance at
every discharge, including asynchronous burst callbacks. Shared spotting confers
no indirect fire. Portable guns register no station; fixed-weapon leases and
spotting ownership remain unchanged. Profile/supply views expose unavailable
weapons and ammunition shortages; existing post cards expose effective duty and
blocked fire, but can still show watching during a stockout.

The independent `rts_design_auditor` granted final static design approval for this
port. Accepted scenarios cover stale/revoked UI authority, edited supply amounts,
calibre separation, attachment identity surviving reload, empty ranged-post
assignment, physical sentry discharge, ally/LOS/range/observation revocation and
burst cancellation on profile change, preemption, death, save, unload and
disenrollment. Existing inputs, modifiers, selection, manual-order precedence,
failed waiting, post capacity and GUARD leashes are unchanged. This static design
audit is separate from engineering tests and live-client gameplay acceptance.

Explicit RTS attack leases remain unsupported by the TACZ adapter. Indirect guns,
concrete fixed gun stations, other resident kinds, model-specific reload animations,
moving fire and the existing RTS roadmap remain deferred. The TACZ E2E scene
exercises sentry fire and skin/Geo/maid holding poses through server setup;
interactive acceptance of the new profile variant dialog remains separate work.
See [TACZ](tacz.md) for installation, configuration and validation commands.

### TACZ staffing integration audit

The TACZ branch was rebased onto the shared group movement and profile staffing base.
Both staffing and firearm translations and architecture contracts are retained. Vanilla still
owns `GroupDeployment`, `GroupRoute` and `GroupMotion`; RTS retains its command adapters.

Composed sentry operation now checks current exact-profile slot eligibility as well as the
reconciled filled member. A profile change, deletion, lost patrol eligibility, unavailable body,
capacity loss or deliberate control cannot authorize duty between staffing ticks. The same
`SentryPosts.valid` gate protects defender selection, sentry work, station operation and the live
observation permission checked by TACZ burst callbacks. Re-pinning immediately revokes the old
operator's discharge permission; normal post maintenance releases its session. Unconfigured
zones keep manual postings and existing firearm supply/fire behavior. Portable guns register
no station, and no separate TACZ staffing ledger is introduced.

The independent `rts_design_auditor` granted final static design approval for this integration.
Accepted contracts preserve keeper/revision authority, visible mismatch and shortage statuses,
input/modifier ownership, RTS selection and command handles, direct-control precedence, failed
waiting, native hostility, GUARD bounds and finite ammunition. New regressions cover a changed
ranged profile before reconciliation, deleted required profiles, visible pinned shortages,
firearm-slot operation, manual-post refusal, physical native shots, re-pin revocation of a
captured discharge callback, session cleanup and ammunition preservation. Legacy posting tests
remain in the suite. This static audit is separate from engineering tests and gameplay validation.

Live-client staffing pages/cards and formation acceptance, indirect and fixed guns, moving fire
and the broader RTS roadmap remain deferred. This integration does not extend that scope.

### TACZ staffing integration engineering results

Linux/JDK 21, with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and
`GRADLE_USER_HOME=/tmp/folkways-sentry-gradle`:

| Command                                                           | Result                                 |
| ----------------------------------------------------------------- | -------------------------------------- |
| `./gradlew build`                                                 | PASS; 50 tasks, 0 failures             |
| `./gradlew runGameTestServer`                                     | PASS; 1,248 required tests, 0 failures |
| `./gradlew -PtaczTests=true runGameTestServer`                    | PASS; 1,290 required tests, 0 failures |
| `./gradlew -PtaczTests=true -PgameTestMod=tacz runGameTestServer` | PASS; 42 required tests, 0 failures    |
| `npm run test:i18n`                                               | PASS; 1 file with 8 cases, 0 failures  |
| `npm run typecheck`                                               | PASS; 7 projects, 0 errors             |
| `npm run format:md:check`                                         | PASS; 0 files needing formatting       |

Final failing test names: none. The new
`composedSentryRevokesQueuedFireWhenPinnedMemberChanges` fixture initially put its replacement
resident in the firing corridor, correctly preventing fire. Moving that resident aside fixed
the fixture; the final native test verifies both physical shots and revoked queued permission.
Large-suite world saves completed separately from the GameTest result reporting.

## Optional colony raids

`mods/raid` registers `folkways_raid` through `FolkwaysRaidMod` and requires Core and Vanilla on both sides. It has no RTS dependency. Raid owns triggering, wave slots, role goals, budgets, plunder, persistent cargo returns, a keeper-authorized Raids page, boss bars and OP2 `/folkways raids` commands. Core adds only general `MobTravel`; Vanilla adds an optional predicate narrowing the existing native resident-target goal. Autonomous defenders use existing equipment profiles, drawn patrol routes, sentry zones and station leases. Actual sightings report raiders into `SpottedTargets`; raid start does not grant hidden observations or control residents.

The independent `rts_design_auditor` granted final static design approval for this scope, including resource-location parsing of namespaced admin definition IDs. Existing input/modifier ownership, manual-order priority, selection reservations, fear, finite ammunition and station cleanup remain unchanged. Acceptance checks natural bow-sentry spotting, projectile/ammunition use, a real wave-member kill and confirmed death accounting, plus the existing native combat, permissions, paused fuses, missing-member, cargo and authority cases. Static design review is separate from server GameTests and live-client gameplay validation.

Saved data from unreleased PR #68 development builds is unsupported and has no migration. See [Colony raids](raids.md) for the supported ledger, configuration, data definitions, safeguards and source history. Boarding/frame raids, FTB protection, third-party raider pools, actual process-restart and client rendering acceptance remain deferred. This port adds no strategic AI or RTS roadmap work.

### Raid engineering results before the staffing rebase

On Linux with JDK 21, using `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and the isolated `GRADLE_USER_HOME=/tmp/folkways-raid-gradle`, the final feature code passed:

| Command                                                           | Result                                                                   |
| ----------------------------------------------------------------- | ------------------------------------------------------------------------ |
| `./gradlew build`                                                 | PASS; 50 tasks, 0 failures                                               |
| `./gradlew runGameTestServer`                                     | PASS; 1,233 required tests, 0 failed                                     |
| `./gradlew -PraidTests=true runGameTestServer`                    | PASS; 1,282 required tests, 0 failed                                     |
| `./gradlew -PraidTests=true -PgameTestMod=raid runGameTestServer` | PASS; 49 required tests, 0 failed                                        |
| `npm run test:i18n`                                               | PASS; 1 reported file, 0 failures; direct execution confirms all 8 cases |
| `npm run typecheck`                                               | PASS; all 7 TypeScript projects, 0 errors                                |
| `npm run format:md:check`                                         | PASS; 0 files needing formatting                                         |

All 21 original safeguard regressions remain in the 49-case Raid suite. The former guard case now uses a real drawn patrol with an assigned equipment profile. New cases cover natural bow-sentry spotting, an owned projectile, finite ammunition, actual wave-member death and victory accounting, and OP2 command parsing of namespaced definition IDs. Initial fixture failures came from registering resident holdings before adding their entities to the level; both local and foreign-resident enrollment now follow the current lifecycle. There are no failing test names in the final runs. Final Java continuation normalization was verified to change whitespace only, with all 23 ported Java files within 120 columns, followed by a successful rebuild. These results do not establish live-client rendering or actual process-restart acceptance.

### Raid integration after the staffing rebase

The rebase retains both shared Vanilla group movement and profile staffing alongside optional raids.
Raid continues to delegate defender execution to Vanilla; it introduces no separate sentry or station
operator selector. Configured sentry zones obtain defenders only from filled exact-profile slots.
Unconfigured zones retain manual postings, and unconfigured routes retain individual patrol laps.
A nearby live resident still prevents undefended store capture regardless of staffing membership.

The independent `rts_design_auditor` granted static design approval for this integration. Accepted
contracts include replacing legacy postings on the first slot, retaining desired pins during profile
mismatch, visible mismatch and shortage, refusing direct posting into a composition, and resuming
native defense when the exact profile returns. Input and modifier ownership, selection reservations,
deliberate-control precedence, server authority, sight, finite ammunition and station/session cleanup
retain the approved base contracts. No new movement or command semantics are introduced.

The raid regression retains legacy bow defense and adds configured-slot defense. Both exercise
natural spotting, real projectile use, finite ammunition, a real wave-member kill and confirmed
victory accounting. The configured case also checks slot mismatch, shortage, posting refusal and
profile recovery. Active-fire invalidation and station leases remain covered by the base regressions.
Static approval and server tests are separate evidence; live-client staffing and raid display review,
actual process restart and arbitrary-terrain/scale validation remain deferred.

### Raid rebase engineering results

Using Linux/JDK 21 and `GRADLE_USER_HOME=/tmp/folkways-raid-gradle`, the rebased branch passed:

| Command                                                           | Result                                    |
| ----------------------------------------------------------------- | ----------------------------------------- |
| `./gradlew build`                                                 | PASS; 50 tasks, 0 failures                |
| `./gradlew runGameTestServer`                                     | PASS; 1,247 required tests, 0 failures    |
| `./gradlew -PraidTests=true runGameTestServer`                    | PASS; 1,297 required tests, 0 failures    |
| `./gradlew -PraidTests=true -PgameTestMod=raid runGameTestServer` | PASS; 50 required tests, 0 failures       |
| `npm run test:i18n`                                               | PASS; 1 reported test file, 0 failures    |
| `npm run typecheck`                                               | PASS; all 7 TypeScript projects, 0 errors |

The Raid suite now contains 50 cases. No GameTest failed in these runs. The first sandboxed build
attempt could not initialize Gradle's local networking; rerunning outside that restriction passed.

## Validation entry points

```sh
./gradlew build
./gradlew runGameTestServer
./gradlew -PrtsTests=true runGameTestServer
./gradlew -PrtsTests=true -PgameTestMod=rts runGameTestServer
./gradlew -PwarfareTests=true runGameTestServer
./gradlew -PwarfareTests=true -PgameTestMod=warfare runGameTestServer
./gradlew -PgameTestMod=vanilla runGameTestServer
./gradlew -PcookeryTests=true -PgameTestMod=vanilla runGameTestServer
./gradlew -PgameTestMod=core runGameTestServer
npm run test:i18n
npm run test:unit
npm run typecheck
npm run test:e2e -- tests/e2e/native-hostility.spec.ts tests/e2e/resident-commands.spec.ts tests/e2e/combat-hostility-policy.spec.ts tests/e2e/formation-patrol.spec.ts tests/e2e/weapon-sessions.spec.ts --workers=1
npm run test:e2e -- tests/e2e/combat-page.spec.ts tests/e2e/combat-supplies.spec.ts tests/e2e/combat-endurance.spec.ts tests/e2e/formation-terrain.spec.ts tests/e2e/combat-golems.spec.ts --workers=1
```

The base GameTest server omits RTS. `-PrtsTests=true` installs it; `-PgameTestMod=rts` selects its moved suites. Tests of Vanilla’s package-private patrol, supplies and living integrations use Vanilla’s optional `rtsTest` source set, following the Cookery pattern; CI runs Vanilla with and without RTS. Command/page E2E specs use `blockwright.toml#rts`.

GameTestServer disables global labour simulation. LaborFixture explicitly advances real labour/travel only for each test's own colony, without changing game runtime behaviour. Client scenarios cover native damage, fear takeover, orders, narrow formations and a saved-world restart.

With `FOLKWAYS_COMBAT_BENCH=1`, opt-in `combat-performance.spec.ts` records 16/32/64 total entities, half residents and half zombies, after 200 warm-up ticks with 100 sampled ticks. Residents receive real swords and GUARD orders, and both sides have 1024 health to retain the population during combat. Artifacts contain paired engine and full Pre-to-Post tick durations plus actual target/navigation counts. They impose no invented performance threshold and do not cover arbitrary terrain or mod combinations.

See [Testing](testing.md) for platform, EULA, dependencies, CI matrix and logs. Local Windows SDK evidence and GitHub Linux CI are recorded separately. Unrun scenarios are not passes.

## Combat integration and focused overhead tools

The integration merges TACZ, Raid and damage control in that order, retaining the
optional Warfare module. Registration, test profiles and translations include all
modules. `PersonBody` participates in both sentry posts and damage control.

The two branches independently introduced incompatible `OverheadTools.Tool` types.
The tactical metadata record and `Handler` API remain intact. The focused plugin
interface is named `FocusedTool`; `DamageControlTool` implements it. Registration
adapts focused tools into the existing toolbar metadata and exclusive selected-ID
registry. `active()` exposes only the selected focused controller, while
`selectedHandler()` keeps tactical pointer and keyboard ownership. Shared Tab
cycling visits book gestures and both tool kinds. Focused input precedes ordinary
book/tactical dispatch; its action row, selection-sensitive layout, status, marks,
box editing and modifier-wheel behavior remain available. Nested tool switches
retain the finish/discard-draft safety rule; top-level mode clicks explicitly discard
unfinished drawings and held placement through their existing discard actions. Focused exit clears the box and calls
`leave()` without changing saved layer preferences; tactical selection retains its
existing overhead-exit lifecycle.

The independent `rts_design_auditor` approved this integration's static RTS design.
Accepted scope preserves tactical press/drag/release ownership, key-repeat latching,
selection and accepted-task suspension; focused editing and layer restoration;
held-book/keeper server authority, refusal feedback and failed-redraw preservation.
Acceptance scenarios cover combat ↔ focused/gesture switching, Tab cycling,
Shift/Ctrl/Alt/MMB conflicts, Backspace/Delete draft discard, V/Esc/book-loss/logout
cleanup, foreign-area rejection and return to an accepted squad. Real-client input,
rendering, layer restoration and network timing remain runtime acceptance work.
Build and server GameTests are separate engineering evidence. This integration
adds no new command rules and does not extend the existing RTS roadmap.

## Combat promotional recording

The combat promo adds no RTS command rules. The independent `rts_design_auditor`
granted static design approval for real LMB box selection, RMB direct movement,
Ctrl+RMB mandatory waypoints and the one-shot Attack Move card followed by RMB.
The take retains held-book/keeper authority, modifier ownership, server
acknowledgements and the existing suspension and failure contracts. Server tasks
arrange the initial cast and observe state; they do not issue RTS orders.

Accepted evidence checks exact selected IDs, intact squad membership and ownership,
route generations, physical displacement for every member, completed assembly,
same-generation engagement and resident-attributed kills after attack-move
acceptance. Failed waiting fails the take and retains phase/result diagnostics.
Static design approval is separate from successful recording, visual composition
review and engineering verification. Runtime results belong to each take's
`tests/out/promo/takes/combat-*/index.json` and the final `combat-edit.json`.

Control-group editing, negative authority and lifecycle regressions, arbitrary
terrain and scale, and the wider RTS roadmap remain deferred. These small staged
recordings establish only the depicted scenarios.

### Extinguisher occlusion found during recording

This records an earlier implementation. The planning-only contract below supersedes
its extra emission prechecks and zero-water geometric refusals.

Portable responders could choose adjacent work positions whose generic block reach
crossed a wall. Native spray correctly refused those rays, so the residents retried
without spending water. The optional extinguisher adapter now filters candidate
positions and current reach through the same unobstructed collider ray required by
native spray, using the actual body's eye height. Final native emission checks,
finite water, protection areas, incident leases and manual-control precedence are
unchanged.

The independent `rts_design_auditor` granted static approval for this narrow fix.
The adjacent-corner regression checks rejected blocked positions, side-effect-free
reach checks and recovery after removing the wall. The optional firefighting suite
passes all 32 required GameTests; recorded client behavior is separate evidence.
No RTS input or lifecycle contract changed.

### Physical extinguisher presentation

The optional firefighting module presents the real packed water extinguisher while
its resident travels to or performs suppression. Previously the native effect
could consume the physical bottle without showing it in the resident's hand.
Presentation requires at least 5 mB of water and yields to active hoses, their
controllers and native weapon sessions. Normal runner completion and preemption
clear it. The independent `rts_design_auditor` granted static approval; tool
visibility is checked separately in the recorded client frames. The firefighting
suite passes all 32 required GameTests.

### Resident FH77 client aim during promo preparation

A real broadside FH77 draft exposed a client presentation bug: native shots and
resident-attributed kills were correct on the server, but the rendered barrel
continued pointing across the truck. Native `ArtilleryEntity.baseTick` overwrote
its synced `SHOOT_VEC` with the passenger's client view vector. That interpolated
resident head pose is not the authoritative station command.

The Warfare client mixin treats only an FH77 resident controller as absent for
that native controller lookup, allowing the existing no-local-controller branch
to follow synced server aim with native slew. It is registered only on the client
and retains explicit client/FH77/resident guards. A second narrow server wrapper
preserves `WarfareStation`'s synced aim instead of replacing it with passenger head
pitch, only while that FH77 resident owns a live station seat. It does not alter
the server's controller lookup or enable additional server steering. Human and
unleased controllers, mortars and other artillery retain native behavior.

There is no cached controller or lease; a handover is reevaluated each tick, and
closing the lease immediately restores native passenger input. Server targeting,
seat ownership, observations, aiming, emission-time validity, finite ammunition,
trajectories, friendly protection and revocation remain unchanged. A last rendered
angle never authorizes another shot.

The independent `rts_design_auditor` granted final static approval for both narrow
source/render corrections. The server regression checks deliberately different
head pitch and station aim with an owned seat, then native input after release and
the normal remount cooldown. Separate real-client recording verifies native
shots/hits/kills, physical shells and a client/server direction dot product of
`0.999999732`, above `cos(1 degree)`. The rendered barrel and impact were reviewed.
Static approval, server regression, real-client recording and build verification
are separate evidence. Human handover, arbitrary target bearings and other
artillery presentation cases remain deferred; this fix adds no RTS input, mode,
command or suppression rules.

### Native area suppression and hose pickup

The optional firefighting bridge targets FireFighting Additions 0.2.5-beta-fix2.
Inspection of the pinned jar confirmed that the portable extinguisher uses
`FireExtinguisherSprayEffects.applyBlockEffects`: a 14-degree half-angle cone,
four-block range, block-radius tolerance, at most 128 ordered samples and native
line-of-sight checks. The hose uses
`HandheldNozzleSprayEffects.applyBlockEffects`: configured cone/fan shapes and
ranges, 8–28 sampled rays and a 0.75-block sample step. The resident bridge invokes
these native block-effect paths, including their incidental block effects and
hydrant owner attribution, instead of restricting effects to one assigned cell.
Only water is supported; equipment, ownership, licence, loaded-chunk and binding
checks remain server-authoritative.

The bridge reads the native consumption constants. One resident extinguisher pulse
costs up to 5 mB and cannot repeat within its two-tick consumption interval; a hose
pulse costs up to 10 mB and cannot repeat in the same tick. Native handlers allow a
last partial charge. Closing and reopening a session does not reset these limits.
Area hits share that pulse's charge. Incidental fires need no extra claim, including
fires outside the initiating protection area. Target selection still starts from
a protection area's bounded survey. After a paid pulse, reconciliation
revisits only known incidents, removes extinguished survey entries, closes affected
responses and releases their exact claims. An already assigned response completes
harmlessly without another pulse; later reignition is discovered as fresh work.

Hose work retains one ordinary response through cabinet approach, a short pickup
interaction, connected travel and suppression. Initial footings are safe cells
adjacent to the cabinet. Binding rechecks availability and authority there, swings
the resident's arm and presents the native controller. The same session then uses
the body's locomotion to reach a safe spray footing, retaining the native maximum
binding distance and ordinary cancellation/cleanup behavior. Spray footings leave
room for the movement abstraction's arrival tolerance. `OnFoot` skips construction
site relocation while heading to or performing extinguishing, so both travel legs
use navigation without teleporting. This narrow movement change is the only
implementation outside firefighting and damage control.

Tracking-client packets reuse the native extinguisher cone mist and handheld
projectile visuals. Connection heartbeats start at pickup; the native hose renderer
uses the moving resident's current anchor before any spray. These packets are
presentation only. Release unbinds immediately while emitted projectile tails may
finish independently.

The independent `rts_design_auditor` granted static design approval for this scope.
Existing input/modifier ownership, manual control priority and incident concurrency
are preserved. Engineering acceptance covers area/outside-cone effects, costs and
cadence, collateral bookkeeping, cabinet approach, connected travel and cleanup.
Static approval and server GameTests do not constitute live-client gameplay
validation. Multi-client visual timing and arbitrary-terrain playtesting remain
separate follow-up validation; broader RTS roadmap work is deferred.

### Resident spray visibility investigation (2026-10-27)

The reported complete loss of resident spray was not reproduced on this branch.
Before any production changes, real Blockwright clients showed native
`SprayParticle` mist for both a water extinguisher and a cone hose. This also held
with Sodium 0.8.13, Iris 1.8.14-beta.1 and Complementary Reimagined 5.8.1. Separate
recordings of ordinary automatic damage control consumed one 5 mB extinguisher
pulse or one 10 mB hose pulse and removed the fire. Particle-triggered screenshots
also captured these brief automatic sprays. A single pulse is easy to miss in a
coarse screenshot sample; that observation alone does not establish the cause of
the reported failure.

Inspection of the pinned native jar confirms player visuals are generated locally
on client ticks. The resident bridge sends paid server pulses to tracking clients
and calls the same native mist/projectile routines directly, without requiring
`Player` or `isUsingItem`. Extinguisher reception checks actor ID and UUID. Hose
connection heartbeats create no projectiles; same-tick close stops the connection
but leaves already emitted projectiles ticking. The native particle-density and
client particle settings still apply. No evidence justified changing packet
registration, recipients, tracking distance, suppression timing or particle counts,
so production spray code remains unchanged.

Accepted scope is a reproducible `tests/visual/firefighting-spray.spec.ts` regression,
its optional GPU/shader profile, and bilingual player/testing descriptions. It
checks idle/spraying/stopped image differences, native particles in the target
corridor, a connected-but-dry hose, exact finite water costs, real fire removal,
no spending after stop and eventual client cleanup. Its sustained scene explicitly
reignites a fixture fire through real paid sessions; a separate automatic pass
uses ordinary scheduling. It does not spawn test-only particles or send visual
packets. The independent `rts_design_auditor` approved this test/documentation scope.
Engineering checks, live-client evidence and static design approval are separate;
this batch changes no RTS inputs, modifiers, selection, authority or response lifecycle.
Final verification passed `./gradlew build`, all 37 required firefighting GameTests,
one complete visual spec in each of the ordinary and shader profiles, i18n,
all seven TypeScript projects and Markdown formatting. Initial fixture attempts
failed Java snippet compilation on the Blockwright `Args` boolean API; correcting
the call to `flag(name, defaultValue)` resolved those script failures.

Evidence is under `tests/out/visual/spray-verified/` and
`tests/out/visual/spray-verified-shaders/` (PNG/MP4/JSON). The original symptom remains
unresolved pending the affected client's installed jar versions, particle/shader
settings and observer distance or another stable reproduction. Multiple observers,
tracking transitions, world changes, arbitrary terrain and moving structures remain
unvalidated here; the wider RTS roadmap is deferred.

## Mode layers: Build, RTS and Damage Control

The former flat gesture/tool row treated Combat and Damage Control as siblings of point,
zone and line drawing. Core now registers Build as a mode; RTS and Vanilla register their
own modes and assign tools to them. The top row switches modes, with Build drawing tools
nested below it. A single-tool mode enters its tool directly. Plugins can register more
modes or tools without adding plugin-specific branches in Core. Existing tool constructors
default to Build. Tab keeps its legacy full cycle and enters each tool's mode. No new
modifier bindings were introduced.

The shared client presentation state eases opacity over 220 ms to 1 for the active mode
and 0.22 for other modes, with muted colors on vertex and projected RTS marks. Build's
zones, paths, member outlines, construction placards/cards and ghost models remain visible
subject to existing manual layer settings. Ghost meshes retain their depth/color passes
and use shader alpha rather than remeshing on mode changes. Damage Control's protected
areas and incident marks refresh even in other modes, but only the active focused tool
can be aimed at or receive actions. Its read-only cache is scoped to the held colony and
dimension, cleared on book loss/logout/context change, and expires after three seconds
without a valid state reply. No background CREATE, RECORD, switch or repair commands are
sent. Existing server held-book/keeper authority still governs POLL and all mutations.

RTS passive drawing includes selected units' health and acknowledged routes, with no
battle cards, bottom inset, waypoint snapping, historic-route modifier or input ownership
outside RTS. Switching away runs the existing cancel/SUSPEND path, resets the movement input preference to Move, cancels pending route additions/replacements and keeps acknowledged local route
presentation until the server snapshot updates it. It never sends RELEASE or substitutes
a new accepted command. Selection revision changes invalidate in-flight pointer ownership;
clicking the already-selected mode is a no-op. Mode clicks discard drafts/held placement;
Tab retains its existing discard behavior and key-repeat latch. Manual layer preferences
are independent, including NONE and MARKS.

The independent `rts_design_auditor` reviewed the hierarchy, modifiers, server authority,
observable failures, selection/gesture ownership, accepted-order suspension and passive
snapshot lifecycle. The final static audit was **approved with no blocking design or lifecycle findings**.
Engineering results are recorded separately below. Static review is not gameplay validation. Acceptance scenarios include the
Build → Damage Control → RTS round trip with the same server command generation; the
legacy Tab loop; active pointer cancellation; no passive hover/hit ownership; layer
preferences; valid/expired snapshots; startup without a player and logout/context reset.

Deferred scope: gameplay balance, formations, queues, control-group semantics and the
remaining combat roadmap are unchanged. Large battles, moving structures and visual
appearance with third-party shaders require their own runtime acceptance; this batch's
client evidence covers the fixed flat-ground regression scene only.

Engineering verification for mode layers (2026-10-26), separate from the static design approval:

- `./gradlew build`: passed, including module checks and GameTest source compilation.
- `./gradlew -PgameTestMod=core runGameTestServer`: 479 required tests passed.
- `./gradlew -PrtsTests=true -PgameTestMod=rts runGameTestServer`: 78 required tests passed.
- `./gradlew -PgameTestMod=vanilla runGameTestServer`: 731 required tests passed.
- `npm run typecheck`: passed across all configured suites.
- `npm run test:e2e -- combat-rts-input.spec.ts combat-camera.spec.ts combat-overhead-suspend.spec.ts damage-control-tool.spec.ts overhead-blackbox.spec.ts --workers=2`: all seven cases passed in one final run.
- `npm run test:unit`: all 141 tests passed, including all 41 LDLib2 adapter/rules tests. The adapter had no hard-coded old tool list to migrate. A pre-existing bench refusal-table omission was reproduced at the base commit and corrected separately: its fixed fixtures create no damage-control areas/incidents.
- `npm run test:i18n`: all eight checks passed. Changed English docs pass Prettier; `git diff --check` passes.

The real-client mode regression uses server-held farm/protection zones, a recorded baseline
with a missing brick and fire, a 12-cell unbuilt blueprint and the existing accepted RTS
squad. Real mode-button input covers Build → RTS → Damage Control → Build → RTS, checks
0.22 / 0.61 / 1 transition weights, unchanged selection revision on current-mode clicks,
passive input/inset exclusion, and the same server command generation on return. The
Damage Control test also switches away from an unfinished box and verifies discard plus
saved manual-layer preferences. Existing RTS pointer/modifier/Tab and suspension tests
remain separate regressions.

Fixed-camera screenshots and state evidence are saved under `tests/out/mode-layers/`:
`build.png`, `rts.png`, `damage_control.png`, and `state.json`. The screenshots were visually
inspected for mode emphasis in the real client. This session did not expose Blockwright
MCP calls; capture used the installed Blockwright real-client driver through Playwright.
These ignored runtime artifacts are reproducible with the command above. The evidence
covers this flat-ground scene, not broader gameplay, shader compatibility or scale.

## Explicit station staffing and physical observation gear

The previous runtime searched every sentry area for SBW emplacements in `SentryNode.advance`,
preferred a station over the carried weapon and sorted stances toward contained stations.
This batch replaces that inclusion rule. Ordinary drawn sentry areas now use only profile
handheld weapons; their stance order does not depend on nearby emplacements.

### Data model and UI decision

`Shape.Body` already describes a point-selected entity delegation, but the old book handler
only dispatched resident admissions. Core now dispatches registered non-resident entity
members through the same book POINT action and `Held.Entity` ledger. Resident admission
stays unchanged. The existing server range is **eight blocks** (squared range 64), not the
client's 64-block aim ray. Held-book/current-keeper authority, loaded/alive same-level
identity, the delegation type predicate and cross-colony exclusion apply on the server.
An already held entity can be withdrawn even when its operating configuration is unsafe.

Warfare registers `folkways:weapon_station` for mortar, MK42, BL132, MLE1934, FH77BW and HPJ11.
Each explicit holding identifies a specific native entity UUID. Vanilla's transient
`StationPost` adapts that loaded entity to the existing sentry runner using nearby safe
footing in the first adjacent ring outside its actual collision box. These are runtime approach cells,
not a saved drawn sentry area and not evidence of station enrollment. `PatrolStaffing`
uses the **holding UUID** as its target key and derives one automatic operator from enrollment.
Any assigned loadout qualifies; real supplies still come from that resident's profile.
The **Sentry posts** overview exposes operator, unloaded weapons and operational failure states,
with withdrawal available while absent. Incumbents follow the aggregate reconciliation contract above.

Only an independent post can acquire a station from `SentryNode`; it filters candidates by
`Station.entity()` against the exact held entity UUID. A co-located replacement cannot
inherit permission. Nodes capture the station anchor and exact slot, and lease checks still
bind position, profile, live duty and delayed native emission. Movement, locking, wrecking,
death, unloading, trade loss, withdrawal, preemption or deliberate control close the old
lease and owned seat. A moved entity needs a fresh approach node; it never substitutes
another weapon. Unavailable station duty waits visibly rather than falling back to a gun.

**Save compatibility:** area slots become allowed-profile unions and total headcounts; pins are dropped.
Existing station holdings remain enrolled and become auto-staffed; their old profile/post configuration
is discarded. Contained weapons are never implicitly enrolled. Leases, observations and prepared shots
remain transient, and orphan-seat/mortar cleanup still applies.

### Observation items and configuration

Observation gear is an ordinary `LoadoutProfile.Kind.SUPPLY` entry, not a profile boolean
or a virtual tool. **Add observation gear** offers `minecraft:spyglass` with target/threshold
1/1. Existing profile encoding, revisions, assignment, `PatrolSupplies` collection/keeps and
`ProfileShortages` missing/stock counts apply unchanged. Optional registered
`superbwarfare:binoculars` and `superbwarfare:spyglass` are also offered; inspecting the pinned
SBW 0.8.9.2 jar found no separate item, so that installation offers only the vanilla spyglass.

`SpottedTargets.sight(observer, target)` is the actual detection-distance calculation,
now composed from eye height, effective target light and physical magnification; see
Dynamic pairwise sight below. The old observer-only sight method and fixed-addition
`observationSightBonus` setting are removed. Supply requirements and shortages are unchanged.
The shared-sighting path serves mortar/howitzer forward observers without integration-specific
sight constants. Existing genuine reports retain their documented TTL/hostility/displacement
lifecycle. Weapon ranges, LOS and loaded-target checks remain separate.

### Independent design audit

The `rts_design_auditor` granted final independent static design approval after reviewing
both the proposal and the implemented diff for the above entity/slot and physical-supply design. Accepted boundaries include existing POINT input and eight-block
server authority, no new modifier ownership, exact station/slot identity, manual-control and
fear precedence, visible failure states, retained desired slots during unload, explicit old-save
opt-in, finite ammunition and ordinary observation expiry. Acceptance scenarios include an
area containing an unused gun while its sentry physically fires a handheld weapon; native
station fire after independent staffing; co-located identity isolation; authority/distance
refusal; real spyglass shortage, collection and removal; and a longer-range forward report
feeding a native mortar. Engineering results are recorded separately below.
Neither static review nor server GameTests establish live-client gameplay validation.

Deferred work remains dedicated observer roles/animations, additional native station types,
expanded entity hover cards and real-client coverage of each emplacement. This batch does
not change RTS command/selection/formation/queue semantics or implement the wider roadmap.

### Engineering verification for this batch

JDK 21 and an isolated `GRADLE_USER_HOME=/tmp/station-staffing-gradle` were used.
The global cache was read-only in the sandbox; local Gradle/JVM execution required
access to loopback networking. No other worktree or `tests/promo` source was changed.

| Check                                                                                                                         | Final result                                     |
| ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| `./gradlew lintScaffold :core:build :vanilla:build :warfare:build :tacz:build --offline`                                      | PASS                                             |
| `npm run test:i18n`                                                                                                           | PASS; all four shipped locales                   |
| `./gradlew -PgameTestMod=vanilla runGameTestServer --offline`                                                                 | PASS; 734 required tests in the initial base run |
| `./gradlew -I /tmp/station-fast-test.gradle -PwarfareTests=true -PgameTestMod=warfare runGameTestServer --offline`            | PASS; 32 required tests; normal shutdown         |
| `./gradlew -I /tmp/station-final-all.gradle -PrtsTests=true -PwarfareTests=true -PtaczTests=true runGameTestServer --offline` | PASS; 1,451 required tests; normal shutdown      |
| Prettier check of the six changed English/Chinese documentation files                                                         | PASS                                             |

The two temporary init scripts only set `gameTestServer.gameDirectory` to fresh
`/tmp/folkways-station-final-war` and `/tmp/folkways-station-final-all` directories.
They do not alter test selection or assertions. The combined run includes the final
physical-spyglass collection/profile-removal and picker validation cases as well as
existing Core, Vanilla combat/patrol, RTS, Warfare and TACZ regressions.

Intermediate failures are retained here rather than counted as a final pass:

- Test-source compilation initially reported `cannot find symbol: method getFirst()`
  on `Set<WorldPos>`; the fixture now uses its iterator.
- The first Warfare run reported `4 required tests failed`:
  `firearmloadoutcollectsstockthenfiresexactlythesuppliedrounds` and
  `unsafephysicalgunvariantscannotreplaceorreservetheusableloadout` both emitted
  `No value present` because their handheld-only fixtures incorrectly requested a station.
  They now use ordinary area posts.
- `containedunenrolledstationleavessentryfiringonlyhandheldweapon` exhausted its handgun
  ammo without damaging the target because the test shot passed through the native cannon.
  The target now stands along an unobstructed handheld firing line.
- `spyglassforwardobserverreportsbeyonddefaultsightfornativemortar` reported `post=blocked`:
  its scout remained within the existing friendly blast exclusion. The scout now makes
  its genuine extended-range report and withdraws before the mortar fires.
- Earlier disk-world reruns reported all 32/1,451 tests passed but lingered during
  `Saving chunks`. Their obsolete test JVMs were terminated after the corresponding
  fresh `/tmp` runs completed normally. This is distinct from test failure and does not
  establish crash/restart acceptance.

There are no failing test names in the final runs. Real-client book rendering,
animations and gameplay acceptance remain unverified.

## Dynamic pairwise sight

`SpottedTargets.sight(observer, target)` returns the detection distance for that
specific pair. With `h = min(max(observer.eyeY - target.eyeY, 0), sightHeightLimit)`,
`v = patrol_alert_range`, effective target brightness `L`, and physical observation
magnification `m`, the server computes:

```text
dark = min(darkSightLightLevel, fullSightLightLevel - 1)
light = darkSightMultiplier + (1 - darkSightMultiplier) * clamp((L - dark) / (fullSightLightLevel - dark), 0, 1)
radius = clamp((defenderSightRadius + v + sightHeightPerBlock * h) * light * m,
               min(minimumSightRadius, maximumSightRadius), maximumSightRadius)
```

Night vision on the observer, a burning/Glowing target, or a type in
`folkways:luminous_targets` sets `light = 1`. The shipped tag contains blaze,
glow squid and magma cube; data packs can extend it. Magnification `m` is 1 unless
an assigned SUPPLY entry matches a recognized observation item actually in the
pack. It then uses `observationSightMultiplier` exactly once. Quantities, missing
stock and profile revisions still use the existing supply system.

The single target-position query is vanilla `getMaxLocalRawBrightness`, which
uses block/sky light and `getSkyDarken()`. The latter includes time, rain and
thunder. There is no duplicate weather penalty, terrain-height sampling or extra
ray cast. Eye height is measured against the target's eyes, so a wall/tower sees
farther down toward ground-level targets. Looking upward adds no bonus and no
extra penalty; this avoids making ordinary uphill encounters unexpectedly blind.
Height gains stop at the configured limit. Distance is still three-dimensional.

All of these server settings are under `sentries`:

| Setting                      | Default | Allowed range | Meaning                                                   |
| ---------------------------- | ------- | ------------- | --------------------------------------------------------- |
| `defenderSightRadius`        | 32      | 1–128         | Baseline before situational factors and the alert perk    |
| `sightHeightPerBlock`        | 1.5     | 0–8           | Added distance per positive eye-height block              |
| `sightHeightLimit`           | 16      | 0–64          | Maximum height difference counted                         |
| `darkSightMultiplier`        | 0.25    | 0–1           | Distance factor at effective light zero                   |
| `darkSightLightLevel`        | 4       | 0–14          | At or below this effective light, use the dark multiplier |
| `fullSightLightLevel`        | 12      | 1–15          | Effective brightness giving full distance                 |
| `observationSightMultiplier` | 2       | 1–4           | Physical observation gear multiplier; 1 disables it       |
| `minimumSightRadius`         | 3       | 1–16          | Close-range floor, always requiring LOS                   |
| `maximumSightRadius`         | 128     | 1–128         | Hard limit for detection and scan bounds                  |

At default settings, same-height bright targets get baseline 32 before perks or
64 with physical optics. Effective light 0–4 gives 8 blocks, including native night
sky brightness 4; light 8 gives 20, and light 12 or above gives 32. Eight blocks of
relative eye height add 12 before light and optics: 44 by day or 88 with optics.
Height adds at most 24. The hard limit wins if an
administrator sets a minimum above it. `hasLineOfSight` and loaded same-level
targets remain mandatory, including inside the near-distance floor. This adds
neither weapon range nor authority to attack nonhostile targets.

The observer-only `sight(observer)` API is removed. `maximumSight(observer)` is
explicitly a conservative scan/UI bound: maximum possible height gain, light
factor 1, current alert perk and actual gear, passed through the identical clamp.
Nonnegative height coefficients and light factors at most 1 prove every pair is
within that bound. An observation scan captures base/perk/magnification once for
all candidates, rejects candidates beyond that spherical upper bound before reading
light, and makes at most one brightness query and the existing LOS check per remaining candidate, then records valid sightings without calling `report` a second time.
The original server-thread, colony-member and holding gates still precede record
writes; public `report` independently validates them. There is no cross-tick cache
to hide changes to lighting, effects, gear or profiles. No query loads chunks.

Sentry circles and all four language catalogs now label the displayed distance
as a **maximum possible** bound. Unstaffed/unloaded fallbacks use the global hard
limit and carry the same warning. Circle membership does not guarantee discovery.
Patrols, sentries, Warfare/TACZ observation sessions and artillery forward scouts
share the same pairwise decision; neither optional gun integration adds a separate
visibility formula. Existing genuine reports still follow their original TTL,
hostility and displacement rules, even if lighting subsequently changes.

The old `observationSightBonus` field, accessor and config specification were
removed. Old fixed-addition values are not converted into a multiplier. Existing
`defenderSightRadius` remains as the baseline; profile/save schemas and physical
spyglass supplies need no migration.

### Dynamic-sight design audit

The independent `rts_design_auditor` granted final static approval after proposal
and implemented-diff reviews. Accepted boundaries are pairwise height/light/gear
composition, a proved conservative scan bound, normalized min/max configuration,
physical supplies, existing server/member authority, explicit UI upper-bound
wording, unchanged LOS and report TTL, and unchanged weapon/seat/control lifecycles.
There are no new inputs or modifier conflicts. Acceptance covers elevation,
height caps, dark caves, effective day/night/weather light, fire/Glowing/luminous
tags/night vision, real gear shortage and collection, near-distance LOS, global
caps, coarse-scan coverage and native artillery forward reports. Static review is
separate from the engineering results below and from live-client gameplay review.

Terrain-relative horizon scans, new observer orders/animations, concealment beyond
light and additional weather multipliers remain deferred. This batch stays within
the requested sight change.

### Dynamic-sight engineering verification

Using JDK 21 and `GRADLE_USER_HOME=/tmp/station-staffing-gradle`:

| Command                                                                                                                       | Result                                            |
| ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| `./gradlew lintScaffold :vanilla:build :warfare:build :tacz:build :vanilla:compileGametestJava --offline`                     | PASS                                              |
| `npm run test:i18n`                                                                                                           | PASS; 8 checks                                    |
| Prettier check of the six changed documentation files                                                                         | PASS                                              |
| `./gradlew -I /tmp/dynamic-sight-vanilla.gradle -PgameTestMod=vanilla runGameTestServer --offline`                            | PASS; 739 required tests                          |
| `./gradlew -I /tmp/dynamic-sight-all.gradle -PrtsTests=true -PwarfareTests=true -PtaczTests=true runGameTestServer --offline` | PASS; final 1,455 required tests, normal shutdown |

The init scripts only select `/tmp/folkways-dynamic-sight-vanilla` and
`/tmp/folkways-dynamic-sight-all` as game directories. No test is filtered out.
Four new `SightGameTests` cover elevation, coarse-scan coverage, height and total
caps, upward viewing, real day/night/rain/thunder brightness, fire/Glowing/night
vision/luminous tags, cave darkness and real block lighting, near-distance LOS,
normalized configuration bounds and retention of genuine reports. Existing gear
shortage/collection/removal tests now assert pairwise multiplicative sight. The
combined suite also exercises real native mortar forward-observer fire and TACZ.

Intermediate failures were corrected and are not reported as successful runs:

- The first combined run reported `18 required tests failed`. A long suite reached
  night; its old distance-only fixtures had no defined illumination. The patrol
  vigilance case emitted `vigilance expands actual discovery range`; native
  station cases emitted `the distant route worker supplies the only genuine
sight report`; handheld cases reported expected shot counts of 5 or 2 but
  `was 0`. The former ordinary 8-block scout reports and 10-block defence were
  correctly outside the reduced night radius. The Warfare enemy fixture and
  the vigilance-specific target now explicitly glow, isolating their original
  ammunition/seat/lease/perk assertions from time-of-day. The dedicated dynamic
  light tests continue to use actual nonexempt targets and lighting.
- The next run reported `1 required tests failed`: the new elevated-LOS case
  emitted `height and Glowing never bypass a blocked sight line`. Its test wall
  occupied local Y 4–5, below the actual eye-to-eye ray; it now occupies Y 6–7.
  The existing near-distance wall test and the corrected elevated wall both pass.

The final suite has no failing test names. Raw iteration logs were written to
`/tmp/dynamic-sight-all.log`, `/tmp/dynamic-sight-all-rerun.log` and
`/tmp/dynamic-sight-all-final.log`; build checks to `/tmp/dynamic-sight-check.log`.
No `tests/promo` files or other worktrees were modified. Live-client gameplay
validation remains separate and has not been performed for this batch.

## 32-block baseline calibration

The new default `defenderSightRadius` is **32**. Minimum 3, maximum 128,
height contribution 1.5 per block for at most 16 blocks, dark multiplier 0.25,
full-light threshold 12 and physical optics multiplier 2 remain unchanged. This
keeps bright flat ground at 32, eight blocks of eye-height advantage at 44, and
optics from that wall at 88 (flat-ground optics: 64), before vigilance.

One additional low-light threshold is necessary: the old interpolation at native
night brightness 4 would give 16 rather than the requested approximately 8.
`darkSightLightLevel=4` makes effective light 0–4 use the existing 0.25 multiplier;
light 8 gives 20 and light 12 or above gives 32. Its allowed range is 0–14, and
its effective value is clamped below `fullSightLightLevel`, keeping the denominator
positive even with overlapping administrator settings. Setting it to 0 restores
the previous brightness interpolation. Existing server configs keep their chosen
baseline, including 12 or 64; no save/config value is forcibly migrated to 32.

### Scan bounds and performance assessment

The same `maximumSight` method serves coarse entity scans and loaded-resident
sentry rings. Without vigilance the bound rises from 36/72 to **56/112** blocks
(without/with optics); three ranks of vigilance give 62/124. Unloaded/unconfigured
UI fallbacks still use the global 128 cap and explicitly describe an upper bound.
The maximum never becomes a promise to detect every target in the ring.

At uniform density, the larger default AABB covers about **2.42 times** the
horizontal area or **3.76 times** the volume. These are geometric estimates,
not measured TPS or a large-battle benchmark. To limit extra work, `sees` now
rejects candidates outside the conservative sphere using squared distance before
querying target light or LOS. This removes AABB-corner candidates without losing
any legal sighting. The existing once-per-scan observer snapshot, at most one
brightness query per remaining pair, no extra rays, loaded-entity-only query and
128-block hard cap remain. Report count limits, TTL, weapon ranges and RTS guard
leashes are unchanged. Large-scale runtime profiling remains deferred.

The independent `rts_design_auditor` granted final static approval for the default,
low-light normalization, conservative prefilter, unchanged input/modifier/server
contracts and preserved save settings. Tests reserve a larger test-only arena for
distances beyond 32 and retain real weapon-range/shared-observation preconditions.
The custom named test bow has a test-provider range of 48; product bow range is
unchanged. The call/resume test now blocks the responder's initial LOS so the
larger sight does not let it independently discover the target before being called.
Static approval remains separate from engineering tests and client gameplay.

### 32-block calibration verification

JDK 21, `GRADLE_USER_HOME=/tmp/station-staffing-gradle`:

| Check                                                                                                                   | Final result                                |
| ----------------------------------------------------------------------------------------------------------------------- | ------------------------------------------- |
| `./gradlew lintScaffold :vanilla:build :warfare:build :tacz:build :vanilla:compileGametestJava --offline`               | PASS                                        |
| `npm run test:i18n` and Prettier check of the six changed documentation files                                           | PASS; 8 i18n checks                         |
| `./gradlew -I /tmp/sight32-vanilla.gradle -PgameTestMod=vanilla runGameTestServer --offline`                            | PASS; 740 required tests; normal shutdown   |
| `./gradlew -I /tmp/sight32-all.gradle -PrtsTests=true -PwarfareTests=true -PtaczTests=true runGameTestServer --offline` | PASS; 1,456 required tests; normal shutdown |

The init scripts only select isolated `/tmp/folkways-sight32-vanilla` and
`/tmp/folkways-sight32-all` game directories. Tests assert default 32/44/88 sight,
56/112 scan bounds, real light 0/4 giving 8 and real light 8 giving 20, threshold
normalization and physical-gear removal. Existing patrol, shared-shot, call/return,
Warfare native firing and TACZ cases remain in the suites.

Intermediate failures were preserved:

- The first Vanilla run reported `outofrangesentrycallspatrolwhichresumesitsunfinishedroute`:
  `a crossbow sentry calls a nearby route patroller for a target beyond its eight-block range`.
  The responder could now discover the target independently; the explicit initial-LOS
  wall restores the call-specific precondition without reducing product sight.
- A later independent run reported `acampfireburnsendstheresidentaway`:
  `a campfire burn must make folkways:resident flee`. This unchanged test depends
  on random escape-point sampling; the fear path does not call sight calculation.
  The final unchanged serial rerun passed it and the other 739 cases.
- Another attempt overlapped a build with a running server and crashed with
  `NoClassDefFoundError: io/github/izakyl/folkways/plugins/build/draft/Lattice`
  (also missing `SettlerQueue`). Rewriting the shared class directory during
  execution was a verification orchestration error. After the build completed,
  serial testing passed; this attempt is not counted as successful validation.

Logs: `/tmp/sight32-vanilla.log`, `/tmp/sight32-vanilla-final.log`,
`/tmp/sight32-vanilla-rerun.log`, final `/tmp/sight32-vanilla-serial.log`,
`/tmp/sight32-all.log` and `/tmp/sight32-check.log`. No failing tests remain in the
final runs. No `tests/promo` or other worktree files were changed. These checks do
not establish live-client gameplay or large-scale runtime performance acceptance.

## External station approach and reachable footing

The filming regression exposed a gap in the original independent-station tests:
`SentryPosts.stances` checked block footing only, `dutyStances` selected one cell
nearest the station's center, and `SentryNode` could not acquire a lease until
ordinary cell arrival. On the 3.4-block-wide MK42 that cell was inside the hull;
collision stopped the resident beyond the 0.9-block horizontal arrival tolerance.
A filled slot and physical ammunition therefore never reached the workload.
Earlier station tests moved the resident to the chosen cell or invoked the
continuous workload directly, bypassing this approach leg.

`StationPost` now derives its bounded candidate neighborhood from the actual
entity bounding box and its first adjacent horizontal ring, including corners.
Station candidates require block footing, full resident clearance from block and
native entity collisions, and explicit exclusion of the held cannon's bounding box. Capacity checks use the registered resident dimensions; live
execution uses the operator's current dimensions and excludes that operator from
collision queries. Ordinary area sentry stances retain their existing rules.

The full external candidate set becomes the station node's `Stances`, rather
than committing to the nearest center cell. Normal `OnFoot` navigation calls
`createPath(Set, 0)`, allowing the pathfinder to choose a reachable alternative
when a nearer candidate is enclosed. The node intent remains tied to the exact
holding, anchor and staffing slot while it walks. An unreachable partial path
cannot start the workload: normal arrival must match one of the offered cells.
The existing travel timeout/refusal and later retry remain authoritative; no
custom teleport, forced mounting or per-model geometric exception is added.

Live resident navigation also snapshots native blocking-entity collision boxes
once per path search and excludes their intersection with the full resident
volume during expansion. This prevents a block-only route from crossing a cannon
or another blocking entity on the way to an otherwise valid goal. The snapshot
is cleared after the search; offline planning does not query live entities.
Frame navigation retains its coordinate-space handling and does not apply
world-space collision boxes to frame-local nodes; mounted-frame cannon approach
is not claimed as validated by this batch.

The current arrival contract supersedes the three-block eye-to-box check and
arrival-offset reserve introduced in `3c9ec6260`. Reaching any valid external
stance is sufficient to enter the station; neither acquisition nor firing adds
another interaction-distance gate. MK42 and FH77 board immediately through the
owned lease's ordinary `startRiding(vehicle)`, even without a target. Mortars
enter duty on foot. The geometry is shared by all accepted emplacements,
including BL132, MLE1934 and HPJ11.

The holding UUID, single profile slot, book inputs, modifiers, server authority,
manual-control priority, physical ammunition and delayed-shot cancellation
contracts remain unchanged. A blocked route keeps the desired slot and pin;
the existing no-route refusal is the observable failure. Unloading, moving,
locking or withdrawing the exact weapon still invalidates the old duty.

The independent RTS design auditor approved the static design and implementation.
Engineering regressions are recorded separately below; this approval does not
claim real-client filming or arbitrary-terrain gameplay validation. Broader RTS
features and dedicated new observer roles remain deferred.

### Approach regression fixtures and verification

Five new Warfare cases run the full `LaborFixture` with the operator spawned at
(20, 18), outside every approach cell of the weapon at (6, 10). They never reposition
that operator or directly advance its station workload. Each requires at least
four recorded walked blocks, no ammunition spent before approach, and native
emission after arrival. MK42 and FH77 must own the exact enrolled passenger seat;
the mortar must emit a physical `MortarShellEntity` while remaining on foot.
The fallback case really places another MK42 over initially valid cells and
walls off a nearer remaining cell, proving it has no reachable path. The sealed
case waits for an actual `NO_WAY_THERE` balk, checks no seat/shot/ammunition loss
and a retained filled slot, then opens the enclosure and waits for native fire.

These scenes wait for newly ticketed residents to become visible before colony
admission. The observer remains outside the conservative blast envelope; the
loaded area covers the high mortar arc's safety margin. Direct-fire targets use
an unobstructed flat range. Indirect targets stand on a raised plateau within
FH77's native forward minimum elevation of ten degrees. Production aiming,
projectile safety and physical ammunition rules were not weakened for the tests.
The old Vanilla lease tests still exercise their transaction/lifecycle contracts
directly, but their stand-in armor-stand operator now starts and returns outside
the marker's collision box.

Intermediate failed runs are retained for diagnosis:

- `/tmp/station-path-war.log` and subsequent `war-2`/`debug` logs initially reported
  `No value present` during admission of not-yet-visible residents, and
  `normal labour walks to the station and emits a native shot` while valid
  projectile safety or native elevation constraints prevented emission.
- `/tmp/station-path-war-3.log` crashed with `NoSuchElementException` when a
  sequence admitted the forward observer before its chunk exposed it. Both
  operator and observer admission now explicitly wait for visibility.
- `/tmp/station-path-war-4.log` reported
  `failed travel reports the real NO_WAY_THERE refusal`: the original single wall
  allowed a route around its ends. The final fixture is a four-sided enclosure
  and asserts the real refusal before opening it.
- `/tmp/station-path-war-5.log` also exposed an incorrect mortar test oracle:
  native mortar shells consumed physical ammunition without the vehicle gun's
  `ShootEvent.Post` counter. The final test requires the actual shell entity.
- `/tmp/station-path-vanilla.log` reported three lease-fixture failures:
  `stationreleasesondisplacementprofilechangeandremoval`,
  `stationisexclusiveandreleasedondeparturedeathandunload` and
  `indirectstationusesonlyanotherresidentsfreshobservation`. Their operator
  overlapped the armor-stand marker, which the new collision rule correctly
  rejects. The fixture's external stand and return position are now explicit.

All temporary diagnostics were removed. These were engineering fixture failures,
not successful gameplay validation; final full-suite results follow.

With JDK 21 and `GRADLE_USER_HOME=/tmp/station-staffing-gradle`, final checks were:

| Check                                                                                     | Result                         | Log                                   |
| ----------------------------------------------------------------------------------------- | ------------------------------ | ------------------------------------- |
| Warfare GameTest (`-PwarfareTests=true -PgameTestMod=warfare`)                            | PASS, all 37 required tests    | `/tmp/station-path-war-7.log`         |
| Vanilla GameTest (`-PgameTestMod=vanilla`)                                                | PASS, all 740 required tests   | `/tmp/station-path-vanilla-final.log` |
| Combined GameTest (`-PrtsTests=true -PwarfareTests=true -PtaczTests=true`)                | PASS, all 1,461 required tests | `/tmp/station-path-all.log`           |
| `lintScaffold :core:build :vanilla:build :rts:build :warfare:build :tacz:build --offline` | PASS                           | `/tmp/station-path-build.log`         |
| `npm run test:i18n`                                                                       | PASS                           | `/tmp/station-path-i18n.log`          |
| Prettier on the six English/Chinese documents; `git diff --check`                         | PASS                           | Local checks                          |

The GameTest init scripts select isolated `/tmp/folkways-station-path-*` game
directories only. Tests and builds ran sequentially against the compiled classes.
The full suites include existing walk/door/ladder/keepout and RTS formation
regressions. Standing cells and navigation snapshots are transient, so existing
holdings, slots and pins use the corrected approach without a save migration.
No real-client or mounted-frame validation is claimed.

## Combat promo: revised capture contract

The independent `rts_design_auditor` reviewed the revised capture plan against the
current input and lifecycle contracts. The accepted scope is one continuous RTS
shot, two mixed TaCZ/SBW handheld shots, one or two staffed battery shots and one
damage-control shot, all around the same settlement. This is static plan approval;
the final implementation review below also grants static approval within that
scope. Engineering checks, source-frame acceptance and final export review remain
separate results.

The RTS shot uses real client input: plain LMB box selection, RMB ground movement,
Ctrl+RMB mandatory waypoints, unmodified G to toggle the persistent Move / Attack
move preference, and ordinary RMB ground to submit that preference. LMB always
selects. Unmodified H stops; Ctrl is released before G/H. The default
G/H/J bindings are rebindable and belong to the active RTS tool. H replaces the
order while retaining selection and control; J would remove the selected
residents from all selection/shortcut references and release their tracked
control. The shot does not claim that stopping releases control. The paper and
wood cards have no old attack-move, stop or release action buttons.

Server tasks arrange the initial scene and observe authoritative acknowledgements,
exact selections, route points, command generations and physical movement. They
do not issue RTS commands or teleport residents during execution. Attack-move
acceptance must preserve the input preference; stop requires a new accepted STOP and
observable holding. Every recorded input and subsequent observation must have no
visible failure message or failed-waiting state. Startup chat/toasts may be cleared
before recording; command failures must not be hidden or cleared to pass a take.

Handheld defenders use drawn sentry areas and exact loadout-profile staffing,
with physical guns and finite ammunition. Each shot mixes different TaCZ and SBW
weapons against the same real wave, with no weapon repeated between handheld
shots. Every battery emplacement is separately admitted as an entity holding,
bound to its exact native UUID, and given its own single profile slot and operator.
An area containing a gun cannot authorize its operation. Operator/holding/slot
identities, native fire, damage, kills and ammunition consumption are recorded per
participant. Physical spyglasses may support observation through the normal
profile system; the current 32-block base, height, light and LOS rules are not
overridden for filming.

Damage control records the intact baseline before commands arrange missing blocks
and fire. Capture begins with that damaged scene; resident suppression and repair
must visibly use native tools, finite water and physical materials, ending at the
recorded baseline. Commands may stage damage but cannot perform the filmed repair.
Disabling fire spread during capture is explicit scene staging so extinguishing
remains attributable to residents.

Each take sets `context.verified` only after its assertions pass. The editor rejects
unverified inputs. Frame review separately checks readable units/routes/weapons,
native SBW raised-gun posture, occupied firing stations, visible suppression and
repair, and absence of chat/toast/failure interference. Server counters and this
static audit do not establish those visual acceptance results.

The old establishing shot, individual weapon/station catalogues, raid attack scene
and draft takes are outside the revised film. Rebinding coverage, modifier-conflict
regressions, lifecycle interruption scenarios, arbitrary terrain, broad weapon
coverage, large-scale performance and the wider RTS roadmap remain deferred; the
promo does not claim those acceptance results. Runtime results and any blocking
product defects must be reported separately without modifying product behavior to
obtain footage.

### Earlier capture audit and bounded acceptance

After the separately merged station-approach fix `3c9ec6260`, the independent
`rts_design_auditor` rechecked the take sources, authoritative evidence and actual
source frames. The five-shot capture implementation receives final static design
approval for that earlier implementation. Its five indices had `context.verified = true` and separate
`visualReview.approved = true`, with reviewed frame paths recorded in each index.
This historical acceptance does not certify replacement footage. The persistent-mode
RTS update and new station/damage captures are tracked separately below. No product
rule, key binding, lifecycle or permission was changed by that approval.

The RTS take uses real box selection, RMB movement, Ctrl+RMB waypoints and
G+ordinary LMB attack move, with no retained modifier before command shortcuts.
H replaces a genuinely moving `LINE` route with a new STOP generation. All three
residents retain selection, ownership and reservation; their measured post-stop
drifts are zero. Six native kills and actual route traversal are recorded. The
feedback monitor covers the whole recording: 306 observations over 56.028 seconds
contain neither a failure message nor an overlay. Reviewed frames show readable
residents, numbered routes and the stopped group in the paper/wood interface.

Both handheld takes use four exact profile/sentry assignments and disjoint weapon
sets. The gate shot records AK47, M870, MP5 and M4A1 firing 21/16/56/32 shots; the
flank records AUG, SVD, M249 and M4 firing 32/45/1/29 shots. Every weapon has native
hit/damage evidence and physical ammunition reduction; each wave has 12 attributed
kills. Reviewed frames show different left/right compositions, real enemies and
native raised SBW arms without chat or toast interference. These observations
establish these shots, not sustained fire or broad weapon-performance coverage.

The battery separately records the MK42, BL132 and MLE1934 entity, holding,
single slot, exact profile and operator identities. External `watching` readiness
and subsequent native mounting of each exact station are observed without forced
seating or operator relocation. Native shot counts are 4/5/4 and attributed kills
4/5/3; the corresponding physical shell counts fall 32→28, 32→27 and 32→28.
Actual reviewed frames show the resident upper bodies and discharge effects in
the same settlement setting. Static interpretation of a native resource flag
does not override the observed frames. The earlier preparation failure is
resolved by the merged product fix; it is not a current filming blocker.

The fixture only stages the damage and enables the existing repair setting.
Four fires become zero, all eight missing baseline blocks are restored, portable
water falls 4000→3990 and carried repair materials 112→104. The front-camera
frames show the extinguisher response, open wall gaps and progressive closure,
without the rejected gate obstruction. No command performs the recorded repairs.

The editor requires verified and visually approved sources, saved video and game
audio. The user's subtitle text remains unchanged. The condensed preview must
retain the moving-to-STOP passage as well as both early suppression and actual
repair; final encoded files, subtitle placement, duration and audio still require
their own export checks. This source-capture audit does not certify an export
that was still rendering when reviewed. The deferred modifier/rebinding,
lifecycle interruption, terrain, scale and wider roadmap work listed above is
unchanged. No additional product implementation is requested by this audit.

## Arrival-authorized station entry

The user-approved rule replaces the post-arrival interaction-distance gate from
`3c9ec6260`: reaching a generated external stance is sufficient to enter and
operate. `StationPost` bounds the candidate grid by expanding the entity's actual
bounding box horizontally by one block. A candidate's full resident box must not
intersect the cannon; expanding that resident box horizontally by one block must
intersect it. This defines the first adjacent ring, including diagonal corners,
without weapon-model exceptions. Footing, block clearance and other native
blocking entities can remove candidates, but never extend that ring outward.
Capacity uses registered resident dimensions and execution uses the actual body.

The complete ring remains the node's stance set. Existing cell-arrival tolerance,
reachable-candidate fallback and live navigation's entity-collision snapshot stay
in effect. There is no additional eye-to-box acquisition or firing-distance
check and no arrival-offset reserve. `availableTo`, both interaction-distance
helpers and the unused `Reach.standingTolerance` helper are removed; this rule
has no separate configuration left behind. Weapon-to-target range and projectile
safety still apply normally.

`Station.enter` runs after the exclusive lease is registered and only after
`station.valid(level)` succeeds. MK42, FH77 and other seated emplacements take
and select their normal native seat immediately, even with no enemy present;
mortars bind the operator on foot. The ordinary non-forced mounting path is
unchanged. A refused or throwing entry closes the lease, releases the integration
and removes its owned passenger and saved seat marker. Delayed shots still
require the exact bound operator and the live duty authorization. Moving,
unloading, locking, withdrawing, profile changes and deliberate control retain
their existing invalidation behavior; being seated never grants a replacement
weapon or staffing slot.

Independent RTS design review accepted this arrival contract, the first ring
including corners, immediate target-free entry and failure rollback. Book inputs,
modifier ownership and server authority are unchanged. This is a static design
approval; real-client filming and frame-mounted emplacement validation remain
separate, deferred checks.

The five real-walking regressions now assert the ring and stance arrival rather
than a three-block threshold. An additional Warfare case walks without enemies,
boards, spends no ammunition and releases the seat on withdrawal. A Vanilla case
checks both false-returning and throwing entries after a real temporary mount,
including exact-once release, passenger/marker cleanup and lease reacquisition.
Its two attempts use separate residents to respect vanilla's dismount cooldown.

Initial verification exposed two old fixture assumptions, both recorded honestly:
`/tmp/station-ring-war.log` failed `ownedhowitzerretainsaiminsteadofresidentheadpitch`
with `use the gunner seat` because entry already selected that seat; the test now
asserts automatic selection. `/tmp/station-ring-vanilla.log` failed
`refusedorthrowingstationentryrollsbacktheleaseandseat` with
`entry takes a normal temporary seat` because the second attempt reused a
resident still under vanilla's 60-tick dismount cooldown. No native cooldown or
mounting rule was changed to make the regression pass.

Final engineering results with JDK 21 and the isolated Gradle cache:

| Check                                                                                     | Result                          | Log                                           |
| ----------------------------------------------------------------------------------------- | ------------------------------- | --------------------------------------------- |
| Warfare GameTest (`-PwarfareTests=true -PgameTestMod=warfare`)                            | All 38 required tests passed    | `/tmp/station-ring-war-final.log`             |
| Vanilla GameTest (`-PgameTestMod=vanilla`)                                                | All 741 required tests passed   | `/tmp/station-ring-vanilla-final.log`         |
| Combined GameTest (`-PrtsTests=true -PwarfareTests=true -PtaczTests=true`)                | All 1,463 required tests passed | `/tmp/station-ring-all.log`                   |
| `lintScaffold :core:build :vanilla:build :rts:build :warfare:build :tacz:build --offline` | PASS                            | `/tmp/station-ring-build.log`                 |
| `npm run test:i18n`; six-document Prettier check; `git diff --check`                      | PASS                            | `/tmp/station-ring-i18n.log` and local checks |

Tests and builds ran sequentially. The existing `/tmp/station-path-*.gradle`
init scripts only select isolated game directories. This runtime-only rule
requires no migration of saved station holdings, slots or pins.

## Promo update for persistent movement mode

The independent `rts_design_auditor` grants final static approval to the updated
RTS take and read-only observer. G selects a persistent local Move / Attack move
preference; ordinary ground RMB submits it and same-mode Ctrl+RMB appends. A mode
change must use ordinary RMB to replace the old route before incompatible Ctrl
additions. No G+LMB confirmation or acknowledgement-driven disarming remains.
The observer reads `attackMoveMode`, the mode label and hint emphasis separately
from authoritative route policy. It does not mutate product state.

The numbered-group sequence box-selects three residents, binds with Ctrl+1,
narrows selection by plain LMB and recalls with unmodified 1. New server
acknowledgements confirm the exact shortcut and selection; the observer checks
nine rendered group hits and only slot 1's existing pressed texture after recall.
Partial selection does not press that slot. Pressing means exact complete-server
membership, not a last-clicked or unique active squad. Digits remain at the lower
left and selected portraits/pages are centered in the compact 60-unit bar.

Ctrl is released before G/H. After direct movement and Ctrl waypoints, G selects
Attack move, RMB submits the route and acceptance retains the preference. A
second G restores Move before the return route. H replaces a genuinely moving
LINE route with STOP while retaining ownership, reservation, selection and the
shortcut. Post-stop drifts are all zero; native engagement records six kills.
No server task issues these commands or relocates the commanded residents during
execution. The monitor covers recording start to stop, recording 363 empty
failure/overlay samples across 66.62 seconds.

Engineering evidence is `/tmp/combat-mode-rts.log` (one real-client test passed)
and `tests/out/promo/takes/combat-rts/index.json` (`context.verified = true`,
64.967-second source, native audio, zero dropped frames). Separate actual review
of `/tmp/new-rts-5.png`, `/tmp/new-rts-18.png`, `/tmp/new-rts-35.png` and
`/tmp/new-rts-48.png` found readable units, current mode emphasis, centered faces,
pressed group 1 and the stopped state, without failure/chat/toast interference.
These limited recorded results are distinct from static approval. The new visual
review must be attached to its index before editing; no old review is inherited
merely because the scope path is unchanged.

Gate/flank footage remains the user-reviewed material. Station reframing and the
damage-control spray capture are still being recorded/reviewed; this RTS approval
does not mark the film or preview complete. Native target-free station entry uses
the arrival-authorized contract above; current frames and exact seat/fire evidence
need separate acceptance. Modifier/rebinding refusal probes, all context-reset
combinations, duplicate/partial shortcut edge cases beyond this sequence,
arbitrary terrain and scale remain outside this promo's acceptance. Existing
contracts remain: selection/recall/H/J and first Esc retain the mode, leaving the
RTS input screen/context resets it to Move, and accepted server tasks follow
existing SUSPEND ownership rules. No additional product or roadmap work is added.

### Historical replacement capture: recovery navigation blocked

The new RTS visual approval is now attached to its generated index. The battery
replacement also passed separate runtime and actual FFmpeg-frame review:
47.900 seconds, three exact native seats, 5/4/4 shots, positive damage and finite
shell use, visible targets/rotation/projectile trails/impacts, and eight kills.
Gate/flank videos and indexes retain the user-reviewed hashes.

That recovery replacement was **not approved**. Its 300-second real-client
run keeps Damage Control mode, area bounds and the information card visible, but
111 fires and 144 baseline differences remain, with no water/material use.
The final `ExtinguishNode` diagnostic shows internal work stances and a stocked
resident outside the courtyard with `interaction=0`. This initial interpretation
is corrected by the complete sample review below. Broad access lanes, normal tool
capacity and letting preparation work retire did not resolve it.

Per the user's explicit stop-on-product-bug instruction, recording stopped; no
product navigation, suppression or repair code was changed or bypassed. The
editor rejected that unverified take; no new film/preview was exported in that batch. Detailed
source paths, native evidence, rejected-probe spray frames and the current status
are in [the combat filming notes](../../tests/promo/film/combat.md). This engineering
capture outcome is separate from the RTS design approval above; its bounded
acceptance and deferred lifecycle scenarios are unchanged.

### Recovery investigation: finish the physical work approach

Historical implementation in `10755cd22`: superseded by the planning-only contract
below. Its investigation and test results remain historical evidence.

The scenery has a real entrance. `combat-damage.take.ts` clears and paves the whole
31-by-22 footprint, builds only the north and two side walls, and leaves the south
side open. `combat-recovery.ts` leaves two-cell fire-free lanes; its crater does not
cut those southern approaches. The reviewed 295-second frame agrees with this
layout. No filming code or output was changed during this investigation.

Reading all 1,582 original samples also corrects the claim that the responder never
entered: 78 samples report `folkways:extinguishing`; the first at 3,192 ms places
the resident at `(-1114.3303, 67, -1092.7217)`, inside the west wall at X = -1116.
The final `interaction=0` snapshot cannot describe the whole 300-second lifecycle.
The take's repair phase waits for zero fires, explaining its zero repairs too.

The minimal GameTest uses a walled courtyard with an open south side, four internal
fires and an outside resident carrying a real finite extinguisher. Before the fix,
the resident enters but repeatedly receives `NO_REACH`, leaving all four fires and
1,000 mB untouched for 2,400 ticks. Temporary diagnostics locate the refusal at
`ExtinguishNode.open`: the planned standing point passes the four-block check, but
the body stops up to 0.9 blocks short, putting its actual eye beyond that range.
`BodyRunner` and `OnFoot` accepted the broad standing tolerance as completed travel;
the runner then stopped navigation. Native navigation itself also advanced the
last node before reaching its center. Repeating the same approach reproduced the
failure without a blocked entrance, blocking entity or partial path.

Accepted implementation separates physical arrival from continued standing.
`Reach.arrivedIn` uses a 0.1-block horizontal tolerance at the native node position;
the existing one-block vertical allowance and 0.9-block standing tolerance remain.
`BodyRunner` uses physical arrival before starting work, including residents already
inside the broader stance. `Locomotion.approaching` and its named forwarding select
this mode for the final walking leg in `Trip`, and for connected suppression travel.
Ordinary `along` travel, intermediate passing legs and boarding retain their existing
completion rules. Precise `OnFoot` travel keeps the last native node until arrival,
continuing ordinary MoveControl and stuck detection; release clears that mode.
Wider walkers use the same width-dependent node offset as
Minecraft; suppression range and extinguisher sight planning use that arrival point
and the actual eye height. Shared-runner commands and station work also finish this final physical alignment
before starting. Their 0.9-block standing/eligibility tolerance, RTS authored-stop
dwell and station authorization without an extra interaction radius remain.

`3c9ec6260`'s collision snapshot is retained: Minecraft's collision predicate excludes
the searching mob and ordinary nonblocking residents, items and displays. Doors are
blocks and still follow doorway navigation. Damage Control installs no Keepout.
Fire cells remain blocked, unreachable partial endpoints cannot authorize work,
and native spray still verifies actual range, sight and finite supply. There is no
coordinate/model exception, direct steering through an obstacle or new relocation.

The independent `rts_design_auditor` granted final static design approval for this
scope. G/right-click/Ctrl, selection, SUSPEND, server authority, preemption and
release semantics are unchanged. Accepted engineering scenarios cover real
suppression and material repair through the opening, initial near-stance alignment,
a wide walker, bounded partial-path failure and existing weapon stations. Static
approval and GameTests do not approve the failed take or establish a successful
client recapture. Arbitrary terrain, moving structures, all edge rays and scale
remain deferred. Verification results are recorded below.

Verification used Java 21 and an isolated Gradle cache:

| Check                                                                                                         | Result                                                                  | Log                                                      |
| ------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- | -------------------------------------------------------- |
| Final courtyard fixture against HEAD product sources                                                          | Expected failure after 2,400 ticks; fixed sources restored afterward    | `/tmp/recovery-fire-before-final-fixture.log`            |
| Vanilla GameTest (`-PgameTestMod=vanilla`)                                                                    | All 744 required tests passed; Gradle succeeded                         | `/tmp/recovery-vanilla-final.log`                        |
| Firefighting GameTest (`-PfirefightingTests=true -PgameTestMod=firefighting`)                                 | All 38 passed, including physical water and repair material consumption | `/tmp/recovery-fire-final2.log`                          |
| Combined GameTest (`-PrtsTests=true -PwarfareTests=true -PtaczTests=true`)                                    | All 1,467 passed; Gradle succeeded                                      | `/tmp/recovery-combined-final.log`                       |
| Isolated RTS with Warfare/TACZ installed                                                                      | All 78 passed                                                           | `/tmp/recovery-rts-diagnostic.log`                       |
| `lintScaffold :core:build :vanilla:build :rts:build :warfare:build :tacz:build :firefighting:build --offline` | Passed                                                                  | `/tmp/recovery-build.log`                                |
| `npm run test:i18n`; four-document Prettier check; `git diff --check`                                         | Passed; no language keys changed                                        | `/tmp/recovery-i18n.log`, `/tmp/recovery-doc-format.log` |

The initial broad-arrival implementation failed the existing platform-edge boarding
case; separating final work approach from ordinary travel fixed it. That initial
combined run also failed `runnerRefusalAfterTargetEscapesPreservesTheSpecificAttackEnd`
(`/tmp/recovery-combined.log`). Its cause was not established: the isolated RTS run
and final combined run pass without changes to attack logic. Its assertion now
reports the actual status/end pair if it fails again. An early passing firefighting
run used an init script that conflicted with the existing log-directory verifier;
the final standard-directory run succeeds. The initial combined server was stopped
after reporting all results when world saving stalled; the final combined run used
`/tmp/recovery-all.gradle` solely to select a fresh temporary game directory and
exited successfully. These harness details are separate from gameplay acceptance.

### Historical recovery promo probes after `10755cd22`

This batch changes filming fixtures and documentation only; product sources and RTS
input/lifecycle rules remain unchanged. The old “never entered the courtyard”
claim is withdrawn: the 78 extinguishing samples and open southern entrance above
are the relevant history, and the physical final-approach fix belongs to the
product investigation, not a filming workaround.

The replacement uses seeded vanilla-style boundary rays for an irregular round
breach and a shallow one-course crater, with joined noisy fire lobes on its intact
rim and inside an oak workshop. A high-blast probe extinguished 117 fires and
repaired 97/110 cells; the remaining 13 cells had no reachable upper work position.
The final fixture puts the blast center half a block below grade so the broken
wall is within ordinary ground-level repair reach. No command repairs the scene.
A separate 240-second probe was simply too short for native baseline scanning and
repair; the take now records to full restoration plus five seconds, capped at 600.

There is no independent native option to fold/hide the left key hints. The camera
keeps the action to their right while retaining the mode bar, area bounds and
region fire/missing card. Tool phases use normal staffing permissions and finite
physical tools; read-only paid-pulse attribution rejects ambiguous simultaneous
water use. Native movement, spray, hose connection and material placement remain
active. A later lower-blast probe restored all 96 cells and extinguished 113 fires,
but its source/export visual approval was not completed before interruption.
The final long-range replacement is tracked in the filming notes.

This finite filming result does not broaden the earlier static RTS audit or the
product investigation's deferred terrain, moving-structure and scale scenarios.

### Current recovery contract: conservative spray planning, ordinary arrival

The user replaced the precise-arrival approach in `10755cd22`. Native spray positions
must reserve range and sight for ordinary arrival; reaching one starts the native
effect without another integration distance, cone or line-of-sight precheck.
`ExtinguisherAdapter.range()` reads `FireExtinguisherSprayEffects.RANGE`; hose ranges
come from the addon's `rangeFor` for the actual cone or flat nozzle.

`SprayFootings` limits the center eye distance to native range minus
`Reach.IN_THE_CELL` (0.9) minus 0.05 blocks. It additionally tests the worst of the
ordinary one-block vertical tolerance before applying that horizontal reserve.
The triangle inequality therefore bounds every actual eye in the allowed arrival
disk and height interval, rather than only its center. Hose binding positions also
reserve this allowance inside the native 32-block connection limit.

For sight, an AABB encloses the entire allowed eye box and the fire center. Every
possible eye-to-target segment lies inside this convex box. Planning rejects any
block collision shape intersecting it, with the same empty collision context as
native collider rays, and rejects missing chunks throughout the volume without
loading them. This is deliberately more conservative than checking several rays:
walls, corners and thin obstacles between samples cannot slip through. For hoses,
the envelope extends downward by the native origin's 0.18-block drop, and the
nearest possible eye must remain at least 0.75 blocks from the target, so the
0.75-block forward offset stays inside the envelope. These offsets already belonged
to the native-origin adapter; they do not change the native effect.

`DamageControlPresence` retains the exact method that produced the response's
footings. `ExtinguishNode` opens only that method, so a hose pickup cell cannot be
reinterpreted as a portable-extinguisher spray cell. Hose pickup still verifies
actual cabinet adjacency, usable equipment, water, authority and a nonempty set of
safe spray positions for that cabinet. Connected travel uses those spray positions.
Every pulse retains the current cabinet identity, binding, nozzle, licence, owner,
actor and 32-block checks. Hand smothering retains its own range/sight guards because
it directly removes a fire rather than invoking a native spray sampler.

All precise-arrival machinery added in `10755cd22` is removed: `approaching` and its
named forwarding, `arrivedIn`/`arrivedAt`/`arrivalAt`, `OnFoot.preciseArrival`, the
navigation mode flag and special last-node handling, and the precise-work-only test.
`BodyRunner`, `Trip`, ordinary movement, connected suppression, station work and
boarding again use their previous 0.9-block standing contract. Existing collision
snapshots, Keepouts, fire avoidance, stuck checks and no-relocation suppression remain.
The shared arrival constants are exposed for planning; no unused precise mode remains.

The integration no longer reproduces the extinguisher's native range/cone/ray test,
or the hose's native target reach precheck. Sessions report the actual target outcome
and paid emission separately. The ordinary fire-work contract below supersedes the
earlier immediate failure on a paid native miss: a performed spray can finish through
its five-tick settle fallback. Unavailable equipment, invalid binding and cadence
refusals retain their resource checks. Known fire incidents reconcile after attempts,
so extinguished collateral fires do not retain stale markers or claims.

The independent `rts_design_auditor` granted final static approval for this contract
and implementation. Input/modifier ownership, selection, SUSPEND, server authority,
preemption and release are unchanged. Acceptance covers the original courtyard,
0.8-block offset pulses, all planned range margins, center-clear/offset-obstructed
sight, both native nozzle ranges, paid misses, fixed method selection, collateral
reconciliation, ordinary boarding and weapon stations. Conservative rejection can
lose otherwise usable edge, narrow or diagonal positions; optimal stance coverage,
zero wasted water under dynamic changes, moving structures and scale remain deferred.
Neither this static approval nor GameTests approve a client recapture of the failed take.

Final verification used Java 21 and the existing isolated Gradle cache:

| Check                                                                                                         | Result                                                      | Log                                                                        |
| ------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- | -------------------------------------------------------------------------- |
| Vanilla GameTest (`-PgameTestMod=vanilla`)                                                                    | All 744 required tests passed                               | `/tmp/recovery-planning-vanilla.log`                                       |
| Firefighting GameTest (`-PfirefightingTests=true -PgameTestMod=firefighting`)                                 | All 41 passed, including the unchanged courtyard regression | `/tmp/recovery-planning-fire-final.log`                                    |
| RTS + Warfare + TACZ GameTest                                                                                 | All 1,466 passed, including weapon stations and boarding    | `/tmp/recovery-planning-combined.log`                                      |
| `lintScaffold :core:build :vanilla:build :rts:build :warfare:build :tacz:build :firefighting:build --offline` | Passed                                                      | `/tmp/recovery-planning-build.log`                                         |
| `npm run test:i18n`, four-document Prettier check, `git diff --check`                                         | Passed; no language keys changed                            | `/tmp/recovery-planning-i18n.log`, `/tmp/recovery-planning-doc-format.log` |

The first compile missed the new test's `Reach` import and failed before starting
Minecraft (`/tmp/recovery-planning-fire-first.log`); it was corrected. Every runtime
suite passed. The old precise-work test was deleted and the movement regressions now
assert ordinary 0.9 arrival and bounded partial-path failure, explaining the combined
test count change. The new response regression also proves that a method with no
planned stance cannot take over and that collateral markers reconcile after a miss.
No `tests/promo` or other worktree files were changed, and no client recapture was run.

### Long-range resident hoses: native server sampling and near-cabinet preference

The follow-up replaces the hose candidate ordering, not the ordinary-arrival or
paid-native-effect contract above. The pinned dependency is
`createfirefightingadd-0.2.5-beta-fix2.jar` from `blockwright.toml`. Inspection of its
actual classes establishes two distinct paths:

- `Config.clampToPhysics` sets each effective range to
  `min(configured, floor(speed / (1 - friction)))` when loading configuration.
  `HandheldNozzleSprayEffects.rangeFor` returns that effective value. Default cone
  and flat ranges are 50 and 35; residents read them through `NativeSprayBridge`,
  with no extra 4-block or 32-block spray cap. The 32-block cabinet binding limit
  remains a separate authority check. The extinguisher still reads its native
  `FireExtinguisherSprayEffects.RANGE` of 4.
- Server-side `HandheldNozzleSprayEffects.applyBlockEffects` calls
  `SprayEffectSampler.traceRays`: normalized straight collider rays, no fluids,
  empty collision context, 0.75-block sample spacing, maximum range 512 and
  maximum 4,096 visited blocks. Both native spray shapes put the aimed center ray
  first before their rotating spread. The speed, gravity and friction trajectory
  belongs to the client's projectile visuals. This version does not use those
  arcs to extinguish blocks on the server. We therefore retain the continuous
  straight sight envelope; inventing a ballistic server predictor would disagree
  with the actual native effect. A wall blocking the native center ray cannot be
  bypassed by the visual water arc.

The range reserve and continuous eye/muzzle envelope described above still cover
all ordinary arrival positions. In the distant acceptance geometry, the muzzle's
0.18-block vertical displacement leaves the center ray through the target cell,
well ahead of the muzzle, with a traversal longer than the native 0.75-block sample
interval. The center ray is first, so rotating peripheral rays and the sampler's
visited-block budget cannot starve that hit. Native-effect tests exercise both
nozzle ranges at the horizontal arrival boundary in eight directions and at all
three vertical tolerance endpoints. These distant tests do not establish a blanket
sampling guarantee for every body size, point-blank muzzle position or terrain.
Arrival still starts the real spray directly, with no extra emission range or ray
gate; only the actual resulting fire state determines success.

Previously `sprayFootings` retained a safe current cell, otherwise generated
three-by-three neighborhoods every two blocks along the cabinet-to-fire axis,
through the connection length. It returned `Set.copyOf` of all accepted positions.
Ordinary `OnFoot` passed that set to Minecraft's multi-target pathfinder, whose
search chooses a target; iteration or generation order was not a near-cabinet
priority. It could also miss usable positions off that axis.

Now a safe current standing cell remains the only destination. Otherwise planning
considers loaded footings throughout the cabinet's connection span and the existing
two-block vertical search allowance. It sorts by distance to the cabinet ascending,
distance to the fire descending, distance from the resident ascending, then stable
coordinates. Cheap binding and native-range bounds discard impossible cells before
the budget of 512 conservative sight checks. After pickup, at most 16 individual
complete-path probes choose the first reachable position; partial paths cannot win.
Only that singleton is sent to ordinary travel. A separate
`ResidentPathNavigation` instance copies floating capability and the configured
resident path-search budget, preserving doors, Keepouts, fire avoidance and entity
collision snapshots without changing the active navigation's target or stuck timers.
Before pickup, candidate discovery only proves geometric serviceability; it does
not require a live path from a potentially distant resident to the later spray
position. Ordinary travel still handles reaching the cabinet, and `open` checks
real pickup adjacency and reachable spray positions there.

The independent RTS design audit accepted this scope: safe current-position
priority, deterministic near-cabinet reachable fallback, bounded isolated probes,
native effective ranges and the actual server sampler, unchanged 32-block binding,
server authority, cancellation, release, selection and modifier ownership. Search
budgets may refuse a usable position beyond the examined candidates; global optimal
coverage, dynamic-obstruction water guarantees, arbitrary detours that stay within
the hose tether, client/server visual arc alignment and cinematic recapture remain
deferred. A complete endpoint path does not prove that every detour stays within
32 blocks; the existing live binding check still disconnects a resident who crosses
that boundary. This is a static design approval, separate
from GameTest results; no promo files or other worktrees are modified.

Final Java 21 verification for this follow-up:

| Check                                                                                                         | Result                                                                   | Log                                                                         |
| ------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ | --------------------------------------------------------------------------- |
| Firefighting GameTest                                                                                         | All 49 required tests passed, including courtyard suppression and repair | `/tmp/recovery-long-hose-fire-final.log`                                    |
| Vanilla GameTest                                                                                              | All 744 required tests passed                                            | `/tmp/recovery-long-hose-vanilla.log`                                       |
| RTS + Warfare + TACZ GameTest                                                                                 | All 1,466 required tests passed                                          | `/tmp/recovery-long-hose-combined.log`                                      |
| `lintScaffold :core:build :vanilla:build :rts:build :warfare:build :tacz:build :firefighting:build --offline` | Passed                                                                   | `/tmp/recovery-long-hose-build.log`                                         |
| `npm run test:i18n`, four-document Prettier check, `git diff --check`                                         | Passed; no language keys changed                                         | `/tmp/recovery-long-hose-i18n.log`, `/tmp/recovery-long-hose-doc-check.log` |

The final complete-work scenarios put fire 30 blocks from each cabinet. Cone and
flat residents each spent one real 10 mB pulse; their recorded minimum fire
distances throughout pickup, travel and suppression were respectively
30.8303 and 31.5734 blocks, also their emission distances. Tests additionally cover
safe current-position reuse, preserving active navigation during probes,
unreachable-first-choice fallback, a wide-floor target requiring a stance about
20 blocks away from the cabinet to enter native range, complete refusal beyond
binding plus nozzle range, remote pickup discovery, native configuration clamping
and 48 distant arrival-boundary effect samples across both nozzle types.

Before the product change, the singleton preference regression failed: planning
returned three candidates instead of one (`/tmp/recovery-long-hose-before.log`).
Intermediate runs also failed while developing the fixtures: simultaneous nearby
cabinets interfered with work selection, and registering per-tick work from a
delayed GameTest callback could duplicate scene setup. The final distant scenarios
own isolated forced chunks, wait for entity ticking, initialize once from an
already registered per-tick callback, and release their chunk tickets afterward.
The binding assertion also accepts pickup/spray/release completing within the same
simulation tick, using the actual paid cabinet pulse as evidence. These fixture
failures are not counted as successful product validation. No client recapture was run.

### Long-range promo client capture: incomplete recovery

The client recapture on merge `f6010c8db` preserves the lower irregular blast and
spreading fire. Its cabinet is about 30 blocks from the main incident. Eleven
paid native pulses consume 110 mB and extinguish 79 cells at eye-to-extinguished-
fire distances of 25.753–34.135 blocks; every pulse is at least 25.753 blocks from
the nearest pre-pulse fire. The extinguisher separately consumes 30 mB and removes
26 cells. This is actual client/server evidence for distant spraying, not a
completed film or additional RTS design approval.

The 600.300-second source fails: eight of 113 fires and all 96 baseline differences
remain. The last paid hose pulse is at 154.129 seconds. Final read-only diagnostics
have no retained response or refusal; the full sample contains actual
extinguishing activity as well as prolonged walking. The cause is not
located. No claim that the resident never entered, that access is sealed, or that
native hose range is insufficient follows from those snapshots. Repair permission
waits for zero fires, so no repair result can be inferred independently.

Per the user's stop-on-product-bug instruction, filming stopped without changing
product sources, forcing travel/work, clearing fire by
command. Runtime verification remains false and the editor refuses the take.
Actual FFmpeg review also rejects roof/card occlusion and a fire-cell tooltip
mistaken for the region count card; the observer now distinguishes the region.
The replacement camera/card changes still need a client run. Detailed frame paths,
remaining cells, paid pulses, log and unchanged prior exports are recorded in
[the filming notes](../../tests/promo/film/combat.md) and generated source index.

## Damage-control zone settings (2026-10-27)

Damage-control marks now open the ordinary zone settings panel: a right-click with no
unfinished box sets `ZoneSelection.openOn(id)` and uses the held book. The shell consumes
`Drawing.selected()` through its existing selection binding and opens `ZoneSettings`.
Creation still uses the focused tool's protect action; its action bar otherwise only
discards drafts. Standard panel release deletes the zone. The former footprint-replacement
workflow is removed; changing a footprint requires release and a new drawing and baseline.

`Delegation.actions()` contributes `ZoneAction(String key, String nameKey, Handler handler)`;
`Handler.run(ServerLevel level, Colony colony, UUID zone)` returns `Attempt`.
`ZoneActions.act(ServerLevel, Colony, UUID, String)` resolves the zone in that colony and
dimension and looks up the registered action. The panel rechecks ordinary live keeper
authority and the displayed zone ID, and reports unavailable actions or handler refusals.
Core has no damage-control dependency. Damage control contributes `record`, labelled
**Reset baseline**, which calls the same server baseline logic as `RECORD`, retaining
loaded-area checks, baseline revisions and response cleanup. The report page only reports
status; it no longer has a second baseline button.

The schema owns only `extinguish`, `repair` and `refill`, with their existing defaults and
persistence. Stored `enabled=false` is ignored on load and by damage-control and optional
firefighting surveys. The existing state mask keeps bit 0 true, and area outlines stay green.

Independent RTS design audit: `rts_design_auditor` approved the final implementation and
migrated specs with no static design blockers. Accepted scope includes the selection handoff,
draft precedence, ordinary panel authority, server action lookup, refusal feedback,
always-watched areas and standard deletion. Acceptance
coverage includes the three persisted flags, first/reset baseline, stale/foreign/deleted or
other-dimension action targets, lost keeper authority, ordinary zones without damage controls,
and standard release. Tab/V/Esc, book loss, dimension/colony transitions, tactical suspension,
and Shift/Ctrl/Alt/MMB retain their existing ownership contracts.

Deferred: real-client input, panel layout, overlap picking and network timing verification;
long e2e runs and promo recordings are intentionally outside this batch. Static design
approval and server/build checks do not establish gameplay validation or complete the RTS roadmap.

Engineering verification, separate from design approval: `./gradlew build`, `npm run typecheck`
and `npm run test:i18n` pass. `./gradlew -PfirefightingTests=true runGameTestServer` passes all
1,343 required tests, including the generic panel authority test, damage control and native
firefighting. An initial new assertion incorrectly required unknown legacy keys to disappear;
it was corrected to verify that a retained `enabled=false` has no effect. Gradle used Java 21
and `GRADLE_USER_HOME=/tmp/station-staffing-gradle`; the default cache and initial sandbox
network setup could not start Gradle. E2e/promo changes were typechecked but not run.

## Isolated TNT repair promo (2026-10-27)

`combat-repair.take.ts` records a separate `combat-repair/01-repair` source and an
intact `layout` still. It retains the bound colony book, Damage Control overhead
mode and native region card. The creative player's real right-click opens the
ordinary area panel and its **Reset baseline** button records the intact structure
before native primed TNT detonates on camera. The area has repair enabled; fire
spread and explosion drops are disabled during staging. Only the resident restores
blocks, at normal game speed, using finite physical colony-store materials.

The same repairer needs both `damage_control` and `hauling` permissions. Nearby
work can draw directly from a reachable store, while distant material collection
creates ordinary hauling work. A probe with only damage-control permission restored
18 of 32 blocks, debited exactly 18 store items and then reported `NO_HAND`; its
duty remained valid. That fixture error does not establish a repair-duty expiry
or a stale-stance product defect.

Independent `rts_design_auditor` static approval accepts this isolated fixture,
unchanged real input/modifier ownership, keeper/server authority and existing
repair/material permission guards. Runtime acceptance separately requires at least
ten exploded baseline blocks, every repair tick fire-free, exact restoration and
matching store depletion, observed repair work and no visible failure/toast.
The native 1920×1080 source must include game audio and actual MP4 frame review.
The other takes and film integration remain outside this batch. Generalized
stance regeneration, suppression-duty renewal and broader lifecycle coverage
remain deferred; this audit is not gameplay validation.

Final finite client verification: the 41.567-second source restores all 22 exploded
baseline blocks, with building-material stock 128→106 and no carried remainder.
The observer records 242 repairing ticks, zero fire ticks and clean HUD/book/feedback
samples. All eight extracted MP4 frames were actually viewed; native explosion,
crater, worker repair and zero-missing ending are visible. The card overlaps part
of the upper-right wall edge, never the worker; its final marker briefly lags the
server placement and clears in the ending. Source audio is stereo 48 kHz, video is
1920×1080 at 30 FPS with zero dropped frames. No product Java was changed. Full
TypeScript checking passes; runtime and visual evidence live in the separate take
index, without changing film source files or existing damage footage.

### Ordinary fire work and performed-spray recovery

The product-owner decision removes the inspection prerequisite and expiring response eligibility.
A bounded survey supplies fire addresses in areas with `extinguish` enabled. Loaded, licensed residents
receive worker-specific `Grown` fire nodes through `Sweep`/`Asking`, without a personal response urge.
Normal planning ranks this work with licence `Keenness`. Damage control declares `FIRST` as its default;
repairs and refills share that trade default. Explicit saved resident and colony-template settings win,
including an explicit `THIRD` or disabled trade. Other vocations retain their existing defaults.

Each node fixes the first available method in hose, portable extinguisher, hand-smother order and its
standing positions. Hose plans also capture the exact cabinet, hydrant identity, nozzle, pickup positions
and spray destination. Planning does not bind equipment, spend water or disturb live navigation.
A speculative long-distance path miss can retain a geometrically valid hose destination; actual travel
from pickup remains authoritative and can fail. A changed or busy cabinet fails instead of substituting
another method or cabinet mid-node.

Pending offers do not reserve fires or consume active-response slots. Multiple workers may have pending
plans for one address; `Incidents` grants only one active claim per fire and worker, bounded by
`damageControlConcurrency`. This permits a later idle resident to respond even while earlier residents
have pending work. Candidate selection retains the shared per-tick search allowance, rotating batches
of at most 128 known addresses, per-worker backoff and bounded dimension-rotating survey.

After arrival at its planned spray stance, the node invokes its selected session once. Only explicit
`performed()` evidence of a paid native emission at the target authorizes recovery. If the target survives,
the node waits five server ticks without another pulse, then rechecks its loaded address and live
protection, licence, resident and colony authority. A surviving fire is removed with `removeBlock(false)`
and level event `1009`, the same operation as hand smothering, and the work completes. Travel failure,
failed opening, missing water, busy equipment and canceled work never authorize this fallback.
Release, preemption and colony closure close sessions and release exact-identity claims; extinguished
collateral incidents reconcile without another spray. The existing zero-fire repair gate remains.

The independent `rts_design_auditor` granted final static approval for this implementation. Input and
modifier ownership, selection, command queues, manual-control and fear precedence remain unchanged.
Acceptance regressions cover immediate response, more than 2,400 idle ticks, timed target-only fallback,
no fallback after failed arrival/opening or canceled authority, zero-fire repair availability, preserved
priority overrides and 100-resident planning budgets/fairness. Engineering results are recorded separately
below. This static audit does not establish real-client gameplay or visual acceptance.

Real-client capture, spray/particle timing, arbitrary terrain, moving structures and larger-scale runtime
validation remain deferred. This batch adds no wider RTS command or roadmap changes.

Engineering verification for this batch used Java 21 and a private Gradle home:

- `./gradlew build`: passed, 74 actionable tasks.
- `./gradlew -PfirefightingTests=true runGameTestServer`: all 1,347 required tests passed.
- `npm run typecheck`: passed all seven TypeScript projects.
- `npm run test:i18n`: one test passed, zero failures.
- `git diff --check` and the added-line 120-character check: passed.

Initial build setup failures (shared-home sandbox access and the missing worktree dependency manifest)
were resolved with a private `/tmp/fire-response-gradle` home and a local manifest copy. A removed import
caused an intermediate compilation failure and was restored before the successful runs. No Minecraft
client, promo or visual test ran, and no commit was created. Raw logs are `/tmp/fire-response-build.log`,
`/tmp/fire-response-gametest.log`, `/tmp/fire-response-typecheck.log` and `/tmp/fire-response-i18n.log`.

### Spray visual fixture lifecycle after ordinary fire work

The reported hose-connection timeout after merge `3ac194a11` was a fixture failure. The unchanged visual
spec also fails at pre-merge `529a0c921`: its server tick listener throws `NoSuchElementException` from
`adapter.open(...).orElseThrow()` before any hose session or connection packet exists. The direct opening
path recomputes reachable spray footings for this fixed no-AI scene. Diagnostics show an available,
unbound cabinet, valid pickup reach and a successful `Plan`, while direct opening returns empty.
`ResidentSprayPacket.connection`, the client `ACTIVE` registration and periodic connection refresh remain
present. No product change or relaxation of opening authority is needed.

The manual visual fixture now captures `SuppressionMethod.Plan`, opens its selected session and places
its no-AI actor at the captured spray destination before suppression. This controlled placement tests
session visuals; the automatic scenes still use ordinary resident work and travel. Pulse accounting
requires both `Outcome.Done` and `performed()`. Connection-only checks retain one hose, no native spray
and zero pulses, and additionally check live water remains unchanged. Stop checks retain disappearing
particles/connections, stopped pulse/water counters and exact native resource costs. An opening refusal
is stored for `READ` to report instead of throwing from the server event bus.

The independent `rts_design_auditor` granted static approval for this test-only adaptation. Server licence,
colony, cabinet identity, binding and position checks remain authoritative. Input, modifiers, selection,
queues, work priorities and paid-pulse recovery are unchanged. Arbitrary terrain, moving structures and
scale validation remain deferred; static design approval is separate from the engineering runs below.

Verification uses Java 21 and `GRADLE_USER_HOME=/tmp/fire-response-gradle`. The temporary baseline worktree
used this worktree's `node_modules` through a symlink and was removed after preserving its server log.

- Pre-merge unchanged visual spec: reproduced the same failure; `/tmp/spray-premerge.log` and
  `/tmp/spray-premerge-server.log`.
- Updated visual spec: passed, including manual connection/spray/stop and both automatic scenarios;
  `/tmp/spray-visual-final.log`. Automatic hose/extinguisher suppression each used one pulse and exactly
  10/5 mB respectively. Captures and resource evidence are under `tests/out/visual/playwright/`.
- `./gradlew build`: passed; `/tmp/spray-build-final.log`.
- `./gradlew -PfirefightingTests=true runGameTestServer`: all 1,347 required tests passed;
  `/tmp/spray-gametest-final.log`.
- `npm run test:e2e -- damage-control-tool.spec.ts`: passed; `/tmp/spray-e2e-final.log`.
