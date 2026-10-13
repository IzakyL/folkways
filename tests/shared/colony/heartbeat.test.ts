import assert from "node:assert/strict";
import test from "node:test";
import { parseColony, parseNav } from "./heartbeat.ts";

const PLAIN = "  97decf70-3272-4ada-b5dc-0a13db838cbd@minecraft:overworld bodies=3 messages=5 nodes=6"
  + " tours=2 phases={READY=4, WORKING=2} done=1 released=0 refused={NOTHING_FREE=1}"
  + " standing={walking=1, working=2}";

const WITH_WHY = "  97decf70-3272-4ada-b5dc-0a13db838cbd@minecraft:overworld bodies=3 messages=9"
  + " nodes=2 tours=1 phases={PENDING=2} done=0 released=0"
  + " unassigned={SAME_WORKER_SPLIT=1, NO_TOOL=2} shortfall=1 turned=2"
  + " broke={minecraft:wooden_hoe=1} standing={stalled=3} skipped=4 solving";

const STUCK = "  97decf70-3272-4ada-b5dc-0a13db838cbd@minecraft:overworld bodies=0 messages=33"
  + " nodes=18 tours=0 phases={} done=0 released=0 standing={idle=500} solving=634s";

const BARE = "  aaaaaaaa-0000-4000-8000-000000000000@minecraft:overworld bodies=0 messages=0 nodes=0"
  + " tours=0 phases={} done=0 released=0";

const NAV = "labor heartbeat colonies=1 searches=0 nodes=0 peakSearches/tick=0 peakNodes/tick=0"
  + " slices=109steps/495wall graph=812pts/1940edges/6shut/2parts disproved=17pts asksPending=3"
  + " quests=41found/2denied/1gaveup denied=1farEnd/0nearEnd/1frontier/0noGoals"
  + " renumbered=4 straightened=37pts publish=1200usstraighten/3400usfreeze/95usmax"
  + " rambles=6/58edges hops=2 attached=9laid/1closed/3far segments=2struck/40walked"
  + " budget=450us(floor200us/ceil800us) aimd=+3/-1 spent=310usavg/790usmax"
  + " work=120usavg/460usmax late=2 over=1/40";

const NAV_IDLE = "labor heartbeat colonies=0 searches=0 nodes=0 peakSearches/tick=0 peakNodes/tick=0"
  + " slices=0steps/0wall graph=0pts/0edges/0shut/0parts disproved=0pts asksPending=0"
  + " quests=0found/0denied/0gaveup denied=0farEnd/0nearEnd/0frontier/0noGoals"
  + " renumbered=0 straightened=0pts rambles=0/0edges hops=0 budget=-";

test("a plain colony heartbeat line", () => {
  const beat = parseColony(PLAIN);
  assert.ok(beat);
  assert.equal(beat.colony, "97decf70-3272-4ada-b5dc-0a13db838cbd");
  assert.equal(beat.dimension, "minecraft:overworld");
  assert.equal(beat.bodies, 3);
  assert.equal(beat.messages, 5);
  assert.equal(beat.nodes, 6);
  assert.equal(beat.tours, 2);
  assert.deepEqual(beat.phases, { READY: 4, WORKING: 2 });
  assert.equal(beat.done, 1);
  assert.deepEqual(beat.refused, { NOTHING_FREE: 1 });
  assert.deepEqual(beat.standing, { walking: 1, working: 2 });
  assert.deepEqual(beat.why, {});
  assert.equal(beat.solving, false);
});

test("the kind with unassigned / shortfall / turned - exactly the line the old regex dropped", () => {
  const beat = parseColony(WITH_WHY);
  assert.ok(beat);
  assert.deepEqual(beat.why, { SAME_WORKER_SPLIT: 1, NO_TOOL: 2 });
  assert.equal(beat.unassigned, 3, "unassigned is the sum of that table, not a separate field");
  assert.equal(beat.shortfall, 1);
  assert.equal(beat.turned, 2);
  assert.deepEqual(beat.broke, { "minecraft:wooden_hoe": 1 });
  assert.deepEqual(beat.standing, { stalled: 3 });
  assert.equal(beat.skipped, 4);
  assert.equal(beat.solving, true);
});

test("solving with seconds is still recognised, and the seconds are read", () => {
  const beat = parseColony(STUCK);
  assert.ok(beat);
  assert.equal(beat.solving, true, "solving=634s is still solving, don't read it as false because of the equals sign");
  assert.equal(beat.solving_seconds, 634);
  assert.equal(beat.bodies, 0, "stale readings are still read; judging staleness is solving_seconds' job");
});

test("solving without seconds reads 0 seconds, and so does not solving", () => {
  assert.equal(parseColony(WITH_WHY)?.solving_seconds, 0);
  assert.equal(parseColony(PLAIN)?.solving_seconds, 0);
});

test("nodes= appears in both line kinds and must not bleed across", () => {
  assert.equal(parseColony(NAV), null);
  assert.equal(parseNav(PLAIN), null);
  const nav = parseNav(NAV);
  assert.ok(nav);
  assert.equal(nav.nodes, 0);
  assert.equal(nav.stepSlices, 109);
  assert.equal(nav.wallSlices, 495);
});

test("a beat with no optional sections still parses; absent means 0 / empty, not undefined", () => {
  const beat = parseColony(BARE);
  assert.ok(beat, "the seven fixed fields should be enough to recognise a colony heartbeat");
  assert.deepEqual(beat.phases, {});
  assert.deepEqual(beat.why, {});
  assert.equal(beat.unassigned, 0);
  assert.equal(beat.shortfall, 0);
  assert.equal(beat.turned, 0);
  assert.deepEqual(beat.refused, {});
  assert.deepEqual(beat.broke, {});
  assert.deepEqual(beat.standing, {});
  assert.equal(beat.skipped, 0);
  assert.equal(beat.solving, false);
});

test("every field of the navigation line is read", () => {
  const nav = parseNav(NAV);
  assert.ok(nav);
  assert.deepEqual(nav.graph, { points: 812, edges: 1940, shut: 6, parts: 2 });
  assert.equal(nav.disproved, 17);
  assert.equal(nav.asksPending, 3);
  assert.deepEqual(nav.quests, { found: 41, denied: 2, gaveUp: 1 });
  assert.deepEqual(nav.denied, { farEnd: 1, nearEnd: 0, frontier: 1, noGoals: 0 });
  assert.equal(nav.renumbered, 4);
  assert.equal(nav.straightened, 37);
  assert.deepEqual(nav.rambles, { ran: 6, edges: 58 });
  assert.deepEqual(nav.publish, { straightenMicros: 1200, freezeMicros: 3400, maxMicros: 95 },
    "graph publish main-thread cost is summed per window, plus the per-beat max");
  assert.equal(nav.hops, 2, "PathfindingLoad writes hops=, not the old rides=");
  assert.deepEqual(nav.attached, { laid: 9, closed: 1, far: 3 }, "attach searches by outcome");
  assert.deepEqual(nav.segments, { struck: 2, walked: 40 }, "walked graph segments, failed and done");
  assert.equal(parseNav(NAV_IDLE)?.attached, null, "old logs without attached= read as null");
});

test("the budget line is what the config prose says to watch for starvation", () => {
  const budget = parseNav(NAV)?.budget;
  assert.ok(budget);
  assert.equal(budget.ceilingMicros, 450);
  assert.equal(budget.floorMicros, 200);
  assert.equal(budget.ceilingCapMicros, 800);
  assert.equal(budget.climbs, 3);
  assert.equal(budget.backOffs, 1);
  assert.equal(budget.spentAvgMicros, 310);
  assert.equal(budget.spentMaxMicros, 790);
  assert.equal(budget.workAvgMicros, 120);
  assert.equal(budget.workMaxMicros, 460);
  assert.equal(budget.late, 2);
  assert.equal(budget.over, 1);
  assert.equal(budget.ticks, 40);
});

test("old logs without publish= read as null, not a fake 0", () => {
  const old = NAV.replace(" publish=1200usstraighten/3400usfreeze/95usmax", "");
  assert.equal(parseNav(old)?.publish, null);
  assert.equal(parseNav(NAV)?.publish?.freezeMicros, 3400);
});

test("an empty window writes only budget=-, parsed as null rather than a row of zeros", () => {
  const nav = parseNav(NAV_IDLE);
  assert.ok(nav);
  assert.equal(nav.budget, null);
  assert.deepEqual(nav.graph, { points: 0, edges: 0, shut: 0, parts: 0 });
});

test("reads full task identity, telling no waiting tasks apart from old logs without records", () => {
  const id = "aaaaaaaa-0000-4000-8000-000000000000";
  assert.deepEqual(parseColony(PLAIN + ` unassignedNodes={${id}=WAY_PENDING}`)?.unassignedNodes, { [id]: "WAY_PENDING" });
  assert.deepEqual(parseColony(PLAIN + " unassignedNodes={}")?.unassignedNodes, {});
  assert.equal(parseColony(PLAIN)?.unassignedNodes, undefined);
});

test("completed plugin actions are parsed separately from active-node phases", () => {
  const real = parseColony(PLAIN + " completed={folkways:wares/CraftNode=5, folkways:pasture/MilkNode=2}");
  assert.deepEqual(real?.completed, { "folkways:wares/CraftNode": 5, "folkways:pasture/MilkNode": 2 });
});
