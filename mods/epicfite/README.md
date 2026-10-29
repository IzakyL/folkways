# Folkways × Epic Fight

Optional native melee and resident presentation bridge for Minecraft 1.21.1.
This adaptation follows upstream Folkways combat at `5c4515d3`, including the
combat rework in `67db5095`. It reuses parts of the earlier `71056866` bridge;
the former PR #78 closed when its base branch was deleted. It does not restore
that branch's superseded input, formations, equipment UI or combat policies.

## Installation

Install **Epic Fight 21.17.3.1** on both server and client. Use `folkways_core`,
`folkways_vanilla` and `folkways_epicfight` from the **same Folkways build**.
Do not mix this bridge with older Core/Vanilla jars. `folkways_rts` is optional;
when installed, it must also match the same build. Model appearances retain
their normal client dependencies, including GeckoLib where applicable.

## Ownership and integration

- Upstream `LoadoutProfiles`, `LoadoutRegistry` and `Weapons.select` own equipment
  choice, supply quantities and melee/ranged switching. The EF adapter borrows
  only the exact physical stack selected from the current worker's pack.
- Upstream patrol staffing or the optional RTS command owns the work node.
  The same `BodyRunner` advances the weapon session; the patch installs no
  separate combat AI or navigator. Approach range is conservatively 2.5 blocks;
  actual native animation colliders still decide whether a hit occurs.
- Autonomous fire retains upstream hostility checks. Explicit targets require
  their live authorization callback. Native frames check the captured
  `DefenseControl.Order` identity, including an initially absent order, and
  every collision rechecks authority, sight, range and the current hand owner.
  Replacing the order revokes the old strike before native animation advances.
- Stop and close revoke collisions before cancelling the animation. Close returns
  the actual remaining weapon through Vanilla's idempotent hand lifecycle,
  preserving components and legitimate durability wear. Save, removal and death
  continue through the upstream resident lifecycle.

The small Core `BodyActions` hook is an optional execution gate, allowed by
default. `BodyRunner` processes invalidated work and resource cleanup before
consulting it. External EF stun can then pause execution and stop navigation
without incrementing the runner's per-tick execution counters. An ordinary EF
strike does not close the gate on its own runner. This hook grants neither
target selection nor independent navigation authority; it does not extend
upstream command deadlines.

## Presentation and limits

The bridge supplies real native melee attacks and hurt reactions, together with
idle, walking/running, eating, sleeping and other existing movement/use poses.
Skin rendering and supported canonical humanoid GEO conversion use the native
armature. Client resource reload disposes converted meshes; the pairing guard
waits for Minecraft's actual reload future before replaying original EF pairing
requests on the same live connection/session. The original server validator
remains authoritative; failure or bounded-queue overflow fails closed.

Autonomous dodge/STEP and tactical sprint decisions are outside this adaptation:
upstream has no corresponding runner-owned movement lease. Dodge root motion
is therefore rejected. There is no new active shield AI or player-only dodge
skill. Empty-hand combat stays with upstream's existing policy rather than
creating an EF fists session without a real weapon loan. Ranged weapons retain
their own adapters. Unsupported GEO layouts retain the declared skin fallback.

## Validation

Run from the repository root with Java 21 and the normal project prerequisites:

```powershell
.\gradlew.bat -PepicfightTests=true -PgameTestMod=epicfite runGameTestServer
npm run test:e2e -- epicfight-acceptance.spec.ts epicfight-geo.spec.ts epicfight-lifecycle.spec.ts epicfight-multiplayer.spec.ts
```

GameTest sources cover physical loans, profile selection, native collision,
permission/order revocation, stun gating and GEO conversion. Client specs cover
no-RTS sword/spear patrol combat and rendering; real RTS pointer interruption;
resource synchronization/reload; actual save/stop/restart; and guest reconnect.
`#epicfight` is the no-RTS profile; `#epicfight-acceptance` also installs RTS.

These are validation entry points, **not a claim that the current revision has
passed them**. Compilation, static review, GameTests and real-client results
must be reported separately against the tested revision. Core
`UrgePreemptionGameTests` separately exercise real runner cancellation before
the gate, task-tool ownership, paused busy counters and resumption; the EF
stun test checks the native reaction and registered gate. These separate checks
are not a single end-to-end EF-stun/runner scenario. One skin/model and a small
fixture do not establish the full weapon, model, terrain or multiplayer matrix.
