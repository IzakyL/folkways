import { expect, test } from "@playwright/test";
import { camera, defineTask, javaTask, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import type { Vec } from "../shared/colony/colony-founding";
import { commandPos } from "../shared/harness/bw-helpers";
import { shaderEvidence } from "../shared/capture/shaders";
import { licenseCrewFast, setMaintainDemandsFast } from "./fast-setup";
import {
  buildPaddock, dressLand, DRAW_PASTURE, ENEMIES, markMembers, OBSERVE_MOUNTED_HITS, openMountedScene, OUTFIT_RIDERS, road,
  ROSTER, riders, standShot, summonHorses, WAVE, type MountedScene, type Pose,
} from "./mounted-stage";
import { mountedMotion, settledMotion } from "./mounted-motion";
import { followCamera } from "./smooth-camera";
import { promoClip, sleepMs, take, type PromoPair } from "./promo-kit";

type Row = { uuid: string; x: number; y: number; z: number; riding: boolean; passenger: boolean; doing: string;
  hand: string; locomotion: string; horse: null | { x: number; y: number; z: number; saddled: boolean; parked: boolean;
    armor: string }; hits: number; mountedHits: number; mountedMelee: number; mountedShots: number; kills: number };
type Sample = { atMs: number; time: number; riders: Row[] };

// Every resident's trip in these takes is planned by the colony: the riders walk to their own horse in the pasture,
// mount, ride, and get down to walk the last steps. Commands only stage the set, the cast and their kit.
const PADDOCK = { x0: -18, z0: -7, x1: -6, z1: 7 };

async function stageHerd(scene: MountedScene, count: number) {
  const paddock = await buildPaddock(scene, PADDOCK.x0, PADDOCK.z0, PADDOCK.x1, PADDOCK.z1);
  const pasture = await scene.server.run(DRAW_PASTURE, { colony: scene.colony, min: paddock.min, max: paddock.max });
  // Cast positions are set before work starts; each resident finds its assigned horse through normal travel.
  const rows = Array.from({ length: count }, (_, i) => Math.round((i - (count - 1) / 2) * 3));
  const horses = await summonHorses(scene, rows.map((z) => scene.at(-10, z)));
  return { paddock, pasture, horses, rows, homes: rows.map((z) => scene.at(-2, z)) };
}

async function sampleRiders(scene: MountedScene, uuids: string[], started: number): Promise<Sample> {
  const read = await riders(scene.server, uuids);
  return { atMs: Date.now() - started, time: read.time, riders: read.riders };
}

function ridden(samples: Sample[], uuid: string) {
  const rows = samples.map((s) => s.riders.find((r) => r.uuid === uuid)!).filter(Boolean);
  const mounted = rows.filter((r) => r.riding && r.horse);
  const first = mounted[0];
  const last = mounted[mounted.length - 1];
  const travel = first && last ? Math.hypot(last.horse!.x - first.horse!.x, last.horse!.z - first.horse!.z) : 0;
  return { rows, mounted: mounted.length, travel };
}

test("mounted: residents ride their horses to work", async ({}, testInfo) => {
  const run = take("mounted-work");
  let pair: PromoPair | undefined;
  try {
    const scene = await openMountedScene(run.scope, run, 3);
    pair = scene.pair;
    const { server, at } = scene;
    const fill = (a: Vec, b: Vec, block: string) => world.command(server, `fill ${commandPos(a)} ${commandPos(b)} ${block}`);
    const set = (a: Vec, block: string) => world.command(server, `setblock ${commandPos(a)} ${block}`);
    const herd = await stageHerd(scene, 3);
    run.note("herd", herd);
    await road(scene, -5, 45, 0);
    // The workshop: a timber-framed hut whose only way in is one ordinary oak door, too low and narrow for a horse.
    await fill(at(46, -4, -1), at(52, 4, -1), "minecraft:spruce_planks");
    await fill(at(46, -4), at(52, 4, 3), "minecraft:stripped_spruce_log");
    await fill(at(47, -3), at(51, 3, 3), "minecraft:air");
    await fill(at(46, -3, 0), at(46, 3, 2), "minecraft:white_terracotta");
    await fill(at(52, -3, 0), at(52, 3, 2), "minecraft:white_terracotta");
    await fill(at(47, -4, 0), at(51, -4, 2), "minecraft:white_terracotta");
    await fill(at(47, 4, 0), at(51, 4, 2), "minecraft:white_terracotta");
    await fill(at(45, -5, 4), at(53, 5, 4), "minecraft:spruce_slab[type=bottom]");
    await fill(at(46, -4, 4), at(52, 4, 4), "minecraft:spruce_planks");
    await fill(at(47, -3, 5), at(51, 3, 5), "minecraft:spruce_slab[type=bottom]");
    for (const z of [-2, 2]) await fill(at(46, z, 1), at(46, z, 1), "minecraft:glass_pane[east=false,west=false,north=true,south=true]");
    for (const x of [48, 50]) {
      await set(at(x, -4, 1), "minecraft:glass_pane[east=true,west=true]");
      await set(at(x, 4, 1), "minecraft:glass_pane[east=true,west=true]");
    }
    await set(at(46, 0), "minecraft:oak_door[facing=east,half=lower,hinge=left]");
    await set(at(46, 0, 1), "minecraft:oak_door[facing=east,half=upper,hinge=left]");
    await set(at(49, 0, 3), "minecraft:lantern[hanging=true]");
    await set(at(45, -2), "minecraft:lantern");
    const store = at(51, -3);
    const bench = at(51, 0);
    const planks = at(51, 3);
    await set(store, "minecraft:barrel[facing=up]");
    await set(bench, "minecraft:crafting_table");
    await set(planks, "minecraft:chest[facing=west]");
    await world.command(server, `item replace block ${commandPos(store)} container.0 with minecraft:oak_log 64`);
    await world.command(server, `item replace block ${commandPos(store)} container.1 with minecraft:oak_log 64`);
    // Personal kit collection gives each resident its own trip to this workshop.
    await world.command(server, `item replace block ${commandPos(store)} container.2 with minecraft:torch 64`);
    // The wheat field north of the road, watered down its middle and ripe, with a chest at its corner.
    const field = { x0: 18, z0: -17, x1: 30, z1: -7 };
    await fill(at(field.x0, field.z0, -1), at(field.x1, field.z1, -1), "minecraft:farmland[moisture=7]");
    await fill(at(field.x0, -12, -1), at(field.x1, -12, -1), "minecraft:water");
    await fill(at(field.x0, field.z0), at(field.x1, -13), "minecraft:wheat[age=7]");
    await fill(at(field.x0, -11), at(field.x1, field.z1), "minecraft:wheat[age=7]");
    await road(scene, 24, 24, -4, 0);
    await fill(at(24, -6, -1), at(24, -4, -1), "minecraft:dirt_path");
    const yard = at(31, -6);
    await set(yard, "minecraft:chest[facing=south]");
    run.note("dressed", await dressLand(scene, { x0: -40, z0: -60, x1: 80, z1: 40 }, [
      { x0: -18, z0: -7, x1: -2, z1: 7 }, { x0: -5, z0: -2, x1: 45, z1: 2 }, { x0: 45, z0: -5, x1: 53, z1: 5 },
      { x0: 18, z0: -17, x1: 31, z1: -4 }], [
      { x: -26, z: -18, kind: "fancy_oak" }, { x: -8, z: -26, kind: "oak" }, { x: 6, z: -34, kind: "birch" },
      { x: 12, z: -24, kind: "fancy_oak" }, { x: 38, z: -28, kind: "oak" }, { x: 44, z: -16, kind: "birch" },
      { x: 58, z: -20, kind: "fancy_oak" }, { x: 66, z: -6, kind: "oak" }, { x: 62, z: 8, kind: "birch" },
      { x: -30, z: 4, kind: "oak" }, { x: -24, z: 16, kind: "birch" }, { x: 30, z: -40, kind: "fancy_oak" },
      { x: 52, z: -36, kind: "birch" }, { x: -14, z: -40, kind: "fancy_oak" }, { x: 74, z: -30, kind: "oak" }]));
    await world.command(server, "gamerule randomTickSpeed 30");
    await tick.sprint(server, 5);
    run.note("members", await markMembers(scene, [store, bench, planks, yard]));
    const roster = await server.run(ROSTER, { colony: scene.colony });
    expect(roster.length).toBe(3);
    const [crafter, ...helpers] = roster;
    const homes = herd.homes;
    const outfits = await server.run(OUTFIT_RIDERS, {
      colony: scene.colony,
      riders: [
        { uuid: helpers[0], profile: "Workshop rider", home: homes[0], yaw: 90, tools: [] },
        { uuid: crafter, profile: "Workshop rider", home: homes[1], yaw: 90, tools: [] },
        { uuid: helpers[1], profile: "Workshop rider", home: homes[2], yaw: 90, tools: [] },
      ],
    });
    run.note("outfits", outfits);
    await tick.sprint(server, 60);
    const ready = await riders(server, roster);
    run.note("ready", ready);
    for (const row of ready.riders as Row[]) {
      expect(row.horse?.saddled, `${row.uuid} has its own saddled pasture horse`).toBe(true);
      expect(row.locomotion).toBe("folkways:riding");
      expect(row.passenger).toBe(false);
    }
    run.note("staging", {
      world: "Flat promo plains; fixed morning, clear weather, no spawning",
      set: "Paddock (horse pasture zone), dirt road, a one-door workshop and a ripe wheat field built by commands",
      cast: "Three founded residents; each given a loadout profile with a mount entry and a real saddle in its pack",
      honesty: "Licences and orders are set at the clip's start; every trip, mount and dismount is the colony's own",
    });

    const uuids = roster;
    const samples: Sample[] = [];
    const eye0 = at(9, 24, 6);
    let pose: Pose = await standShot(scene, eye0, at(-7, 3, 1), 62).then((p) => ({ position: p.eye, yaw: p.yaw, pitch: p.pitch }));
    const door = at(46, 0);
    let phase = "paddock";
    const phases: Array<{ phase: string; atMs: number }> = [];
    await promoClip(run, scene.client, server, "01-ride", {
      seconds: 44,
      fps: 30,
      subject: "Three residents walk into the paddock to their own saddled horses, mount, ride out along the road; " +
        "they park apart outside the workshop and walk in through its oak door to collect their own kits",
      worldState: { crafter, helpers, door, store, bench, planks, yard },
      during: async () => {
        const started = Date.now();
        await licenseCrewFast(server, [
          { uuid: crafter, trades: ["crafting", "hauling"] },
          ...helpers.map((uuid) => ({ uuid, trades: ["crafting", "hauling"] })),
        ]);
        run.note("kitOrders", await server.run(WORKSHOP_KITS,
          { colony: scene.colony, residents: roster, configure: true }));
        run.note("demands", await setMaintainDemandsFast(server, scene.name, [
          { at: planks, item: "minecraft:oak_planks", count: 64, low: 64 },
        ]));
        run.note("followCamera", await followCamera(scene, {
          from: pose, until: () => Date.now() - started >= 42_500,
          sample: async () => {
            const sample = await sampleRiders(scene, uuids, started);
            samples.push(sample);
            // The camera rides along with whoever is in the saddle, then waits at the workshop door.
            const mounted = sample.riders.filter((r) => r.riding);
            const ahead = sample.riders.reduce((a, b) => (b.x > a.x ? b : a));
            let eye: Vec | null = null;
            let target: Vec | null = null;
            if (phase === "paddock" && mounted.some((r) => r.x > at(-8, 0).x)) {
              phase = "road";
            }
            if (phase === "road" && ahead.x > at(36, 0).x) {
              phase = "door";
            }
            if (phase !== phases[phases.length - 1]?.phase) {
              phases.push({ phase, atMs: sample.atMs });
            }
            if (phase === "road") {
              const group = mounted.length ? mounted : [ahead];
              const lead = { x: group.reduce((n, r) => n + r.x, 0) / group.length, y: group[0].y,
                z: group.reduce((n, r) => n + r.z, 0) / group.length };
              eye = { x: lead.x - 3, y: lead.y + 4.5, z: lead.z + 14 };
              target = { x: lead.x + 2, y: lead.y + 1.4, z: lead.z };
            } else if (phase === "door") {
              eye = at(37, 11, 4);
              target = { x: door.x - 1, y: door.y + 1.2, z: door.z };
            }
            return eye && target ? { eye, target } : null;
          },
        }));
      },
    });
    run.note("phases", phases);
    run.note("samples", samples);
    const rides = uuids.map((uuid) => ({ uuid, ...ridden(samples, uuid) }));
    run.note("rides", rides.map((r) => ({ uuid: r.uuid, mounted: r.mounted, travel: r.travel })));
    const dismounts = rides.flatMap((ride) => ride.rows.filter((row, i) => i > 0
      && ride.rows[i - 1].riding && !row.riding));
    const interruptions = dismounts.filter((row) => {
      if (!row.horse) {
        return true;
      }
      const h = row.horse;
      const workshop = Math.hypot(h.x - door.x, h.z - door.z) <= 12;
      return !workshop;
    });
    run.note("travelDismounts", dismounts);
    run.note("roadDismounts", interruptions);
    expect(interruptions, "riders stay mounted between road waypoints").toEqual([]);
    for (const ride of rides) {
      expect(ride.mounted, `${ride.uuid} is seen in the saddle of its own horse`).toBeGreaterThan(5);
    }
    expect(rides.filter((r) => r.travel > 30).length, "two riders ride the length of the road").toBeGreaterThanOrEqual(2);
    const inside = rides.flatMap((r) => r.rows).filter((r) => !r.passenger && r.x > at(46.6, 0).x
      && r.x < at(52, 0).x && Math.abs(r.z - at(0, 0).z) < 3.5);
    run.note("inside", inside.length);
    expect(inside.length, "a rider walks in through the workshop door on foot").toBeGreaterThan(0);
    expect(inside.every((r) => r.horse && r.horse.parked
      && (r.horse.x < at(46, 0).x || r.horse.x >= at(53, 0).x
        || r.horse.z < at(0, -4).z || r.horse.z >= at(0, 5).z)),
      "its horse stands parked outside the workshop").toBe(true);

    const parking = samples.map((sample) => {
      const parked = sample.riders.filter((r) => !r.passenger && r.horse?.parked
        && Math.hypot(r.horse.x - door.x, r.horse.z - door.z) < 14);
      const gaps = parked.flatMap((r, i) => parked.slice(0, i).map((other) =>
        Math.hypot(r.horse!.x - other.horse!.x, r.horse!.z - other.horse!.z)));
      return { time: sample.time, count: parked.length, gaps };
    });
    run.note("parking", parking);
    run.note("parkingVerdict", { threshold: 2.0, maximumParked: Math.max(...parking.map((s) => s.count)),
      minimumGap: Math.min(...parking.flatMap((s) => s.gaps)) });
    expect(Math.max(...parking.map((s) => s.count)), "all three riders leave horses outside the same workshop")
      .toBe(3);
    expect(parking.flatMap((s) => s.gaps).every((gap) => gap >= 2.0),
      "horses parked outside the workshop remain at least 2 blocks apart").toBe(true);

    let kits = await server.run(WORKSHOP_KITS, { colony: scene.colony, residents: roster, configure: false });
    let kitWaitTicks = 0;
    // Arrival now brakes before parking. Keep the physical kit assertion, allowing the last native store job
    // to finish after the timed shot rather than treating the clip's wall-clock cut as a planner deadline.
    while (kits.some((kit: { torches: number }) => kit.torches < 8) && kitWaitTicks < 200) {
      await tick.sprint(server, 10);
      kitWaitTicks += 10;
      kits = await server.run(WORKSHOP_KITS, { colony: scene.colony, residents: roster, configure: false });
    }
    run.note("kitWaitTicks", kitWaitTicks);
    run.note("kits", kits);
    expect(kits.every((kit: { torches: number }) => kit.torches >= 8),
      "each rider collected its own physical workshop kit").toBe(true);

    // The crafter's own work is real: planks reach the order chest.
    const made = await server.run(COUNT, { at: planks, item: "minecraft:oak_planks" });
    run.note("planks", made);
    run.note("shaders", shaderEvidence(scene.pair.clientInstance));
    run.expectTaken(["01-ride"]);
    run.note("verified", true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

test("mounted: residents fight from the saddle", async ({}, testInfo) => {
  const run = take("mounted-combat");
  let pair: PromoPair | undefined;
  try {
    const scene = await openMountedScene(run.scope, run, 4);
    pair = scene.pair;
    const { server, at } = scene;
    const herd = await stageHerd(scene, 4);
    run.note("herd", herd);
    await road(scene, -5, 30, 0);
    run.note("dressed", await dressLand(scene, { x0: -40, z0: -60, x1: 90, z1: 40 }, [
      { x0: -18, z0: -9, x1: -1, z1: 9 }, { x0: -5, z0: -2, x1: 30, z1: 2 }, { x0: 14, z0: -10, x1: 70, z1: 10 }], [
      { x: -26, z: -18, kind: "fancy_oak" }, { x: -8, z: -26, kind: "oak" }, { x: 6, z: -30, kind: "birch" },
      { x: 24, z: -24, kind: "fancy_oak" }, { x: 44, z: -22, kind: "oak" }, { x: 58, z: -26, kind: "birch" },
      { x: 76, z: -14, kind: "fancy_oak" }, { x: 80, z: 6, kind: "oak" }, { x: 70, z: 20, kind: "birch" },
      { x: -30, z: 4, kind: "oak" }, { x: 36, z: -36, kind: "fancy_oak" }, { x: 88, z: -30, kind: "birch" }]));
    // The wave needs hostile mobs: the cinematic world is peaceful.
    await world.command(server, "difficulty normal");
    await scene.server.run(OBSERVE_MOUNTED_HITS, {});
    const roster = await server.run(ROSTER, { colony: scene.colony });
    const [lancerA, lancerB, archer, crossbow] = roster;
    const homes = herd.homes;
    run.note("outfits", await server.run(OUTFIT_RIDERS, {
      colony: scene.colony,
      riders: [
        { uuid: lancerA, profile: "Cavalry", home: homes[1], yaw: 90, weapon: "minecraft:iron_sword",
          armor: "minecraft:diamond_horse_armor", tools: [] },
        { uuid: lancerB, profile: "Cavalry", home: homes[2], yaw: 90, weapon: "minecraft:iron_sword",
          armor: "minecraft:diamond_horse_armor", tools: [] },
        { uuid: archer, profile: "Horse archer", home: homes[0], yaw: 90, weapon: "minecraft:bow",
          armor: "minecraft:iron_horse_armor", tools: [] },
        { uuid: crossbow, profile: "Mounted crossbow", home: homes[3], yaw: 90, weapon: "minecraft:crossbow",
          armor: "minecraft:golden_horse_armor", tools: [] },
      ],
    }));
    await tick.sprint(server, 60);
    const ready = await riders(server, roster);
    run.note("ready", ready);
    for (const row of ready.riders as Row[]) {
      expect(row.horse?.saddled, `${row.uuid} has its own saddled, armoured horse`).toBe(true);
      expect(row.horse?.armor).not.toContain("air");
    }
    // Archers take sentry posts drawn east of the road; the cavalry guard a line before them on the commander's order.
    const posts = await server.run(POSTS, { colony: scene.colony, posts: [
      { uuid: archer, at: at(21, -6, -1) }, { uuid: crossbow, at: at(21, 6, -1) }] });
    run.note("posts", posts);
    await licenseCrewFast(server, roster.map((uuid: string) => ({ uuid, trades: ["patrolling"] })));
    // The commander gives RTS orders with the colony book in hand, so it stays a creative player, out of shot below ground.
    const commander = at(10, 34, 30);
    run.note("holds", await server.run(ORDERS, { colony: scene.colony, player: scene.name, holds: [
      { uuid: lancerA, at: at(17, -2) }, { uuid: lancerB, at: at(17, 2) }] }));
    const samples: Sample[] = [];
    // Muster: everyone walks to its horse, mounts and rides out to its place.
    let pose: Pose = await standShot(scene, at(8, 20, 7), at(4, 2, 0), 64, commander)
      .then((p) => ({ position: p.eye, yaw: p.yaw, pitch: p.pitch }));
    await mountedMotion(server, "start", scene.colony, roster);
    await promoClip(run, scene.client, server, "01-muster", {
      seconds: 26,
      fps: 30,
      subject: "Four armoured riders mount their armoured horses in the paddock and ride out to the line east of it",
      worldState: { roster },
      during: async () => {
        const started = Date.now();
        pose = await flyTo(scene, pose, at(10, 16, 6), at(16, 1.5, 0), 64, 18_000);
        while (Date.now() - started < 25_000) {
          samples.push(await sampleRiders(scene, roster, started));
          await sleepMs(400);
        }
      },
    });
    run.note("musterSamples", samples.splice(0));
    const musterMotion = await mountedMotion(server, "stop", scene.colony, roster);
    run.note("musterMotion", musterMotion);
    const settling = settledMotion(musterMotion, roster);
    run.note("musterSettling", { minimumTicks: 100, settleTicks: 20, maxTravel: .2, maxTurn: 15, riders: settling });
    for (const rider of settling) {
      expect(rider.ticks, "each rider has 100 individual settling ticks").toBeGreaterThanOrEqual(100);
      expect(rider.mounted && rider.arrived, "each rider holds its post in the saddle").toBe(true);
      expect(rider.travel, "a settled horse does not walk circles at its post").toBeLessThan(.2);
      expect(rider.turn, "a settled horse does not keep turning at its post").toBeLessThan(15);
      expect(rider.paths, "holding a post does not repeatedly issue paths").toBe(0);
    }
    // Let the line settle before the wave comes in.
    await tick.sprint(server, 300);
    const settled = await riders(server, roster);
    run.note("settled", settled);
    const battle: Sample[] = [];
    const orders: unknown[] = [];
    await standShot(scene, at(17, 13, 4), at(29, 1.5, 0), 58, commander);
    await promoClip(run, scene.client, server, "02-battle", {
      seconds: 42,
      fps: 30,
      subject: "Zombies come in from the east: the horse archer and crossbowman shoot from the saddle at their posts, " +
        "and the two cavalry guarding the line ride out at them and cut them down with swords",
      worldState: { roster },
      during: async () => {
        const started = Date.now();
        run.note("wave", await server.run(WAVE, { speed: .12, health: 40, at: [
          at(36, -4), at(38, 0), at(37, 4), at(42, 6), at(44, -2), at(46, 2), at(32, 8), at(34, 10)] }));
        const assigned = new Map<string, string>();
        while (Date.now() - started < 41_000) {
          const sample = await sampleRiders(scene, roster, started);
          battle.push(sample);
          const enemies: Array<{ uuid: string; x: number; z: number }> = await server.run(ENEMIES, {});
          // The commander assigns targets; the cavalry approach and fight through their normal work nodes.
          for (const lancer of [lancerA, lancerB]) {
            const current = assigned.get(lancer);
            if (current && enemies.some((e) => e.uuid === current)) {
              continue;
            }
            const me = sample.riders.find((r) => r.uuid === lancer)!;
            const pick = enemies.filter((e) => ![...assigned.entries()]
              .some(([rider, target]) => rider !== lancer && target === e.uuid))
              .sort((a, b) => Math.hypot(a.x - me.x, a.z - me.z) - Math.hypot(b.x - me.x, b.z - me.z))[0];
            if (!pick) {
              continue;
            }
            assigned.set(lancer, pick.uuid);
            orders.push({ atMs: sample.atMs, lancer, target: pick.uuid,
              result: await server.run(ORDERS, { colony: scene.colony, player: scene.name,
                attacks: [{ uuid: lancer, target: pick.uuid }] }) });
          }
          await sleepMs(350);
        }
      },
    });
    run.note("orders", orders);
    run.note("battle", battle);
    const last = battle[battle.length - 1].riders;
    const summary = Object.fromEntries(last.map((r) => [r.uuid, {
      hits: r.hits, mountedHits: r.mountedHits, mountedMelee: r.mountedMelee, mountedShots: r.mountedShots, kills: r.kills }]));
    run.note("summary", summary);
    run.note("enemiesLeft", await server.run(ENEMIES, {}));
    expect(summary[archer].mountedShots, "the bow lands arrows from the saddle").toBeGreaterThan(0);
    expect(summary[crossbow].mountedShots, "the crossbow lands bolts from the saddle").toBeGreaterThan(0);
    const charging = [lancerA, lancerB].map((uuid) => {
      const start = (settled.riders as Row[]).find((row) => row.uuid === uuid)!;
      return battle.filter((sample) => sample.riders.some((row) => row.uuid === uuid
        && row.riding && Math.hypot(row.x - start.x, row.z - start.z) > 2)).length;
    });
    run.note("cavalry", { charging, mountedMelee: [summary[lancerA].mountedMelee, summary[lancerB].mountedMelee],
      swordHits: [summary[lancerA].hits, summary[lancerB].hits] });
    expect(charging[0] + charging[1], "the cavalry ride out to the zombies in the saddle").toBeGreaterThan(0);
    expect(summary[lancerA].mountedMelee + summary[lancerB].mountedMelee, "the cavalry's swords land from the saddle").toBeGreaterThan(0);
    run.note("shaders", shaderEvidence(scene.pair.clientInstance));
    run.expectTaken(["01-muster", "02-battle"]);
    run.note("verified", true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function flyTo(scene: MountedScene, from: Pose, eye: Vec, target: Vec, fov: number, ms: number) {
  const look = cameraLookingAt(eye, target);
  const next = { position: eye, yaw: look.yaw, pitch: look.pitch };
  await camera.fly(scene.client, { poses: [from, next], durationMs: ms, fov });
  return next;
}

const COUNT = defineTask<Record<string, any>, any>({
  name: "folkways.promo.mounted-count",
  side: "server",
  source: javaTask({
    body: `
var a = Args.of(ctx);
var p = (Map<?, ?>) a.value("at");
var pos = new net.minecraft.core.BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
var container = (net.minecraft.world.Container) ctx.server().overworld().getBlockEntity(pos);
var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(a.string("item")));
int n = 0;
for (int i = 0; i < container.getContainerSize(); i++) if (container.getItem(i).is(item)) n += container.getItem(i).getCount();
return Map.of("count", n);`,
  }),
});

/** Sentry posts drawn as 3x3 zones, each staffed by the one archer whose profile it allows. */
const POSTS = defineTask<Record<string, any>, any>({
  name: "folkways.promo.mounted-posts",
  side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
      "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles",
      "io.github.izakyl.folkways.plugins.patrol.PatrolStaffing",
      "io.github.izakyl.folkways.plugins.patrol.SentryPosts",
      "net.minecraft.core.BlockPos",
    ],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var out = new ArrayList<Object>();
for (var raw : a.list("posts")) {
  var row = (Map<?, ?>) raw;
  var p = (Map<?, ?>) row.get("at");
  var c = new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
  var cells = new LinkedHashSet<BlockPos>();
  for (var q : BlockPos.betweenClosed(c.offset(-1, 0, -1), c.offset(1, 0, 1))) cells.add(q.immutable());
  var zone = ColonyFront.of(colony).addZone(level, SentryPosts.ZONE, cells, ColonySettings.empty()).orElseThrow();
  var resident = UUID.fromString(String.valueOf(row.get("uuid")));
  var profile = LoadoutProfiles.of(colony).assigned(resident).orElseThrow();
  PatrolStaffing.of(colony).configure(level, zone.id(), Set.of(profile.id()), 1);
  out.add(Map.of("zone", zone.id().toString(), "positions", PatrolStaffing.of(colony).positions(zone.id()).toString()));
}
return out;`,
  }),
});

/** The commander's RTS orders, given as the command book gives them: hold a place, or attack one named foe. */
const ORDERS = defineTask<Record<string, any>, any>({
  name: "folkways.promo.mounted-orders",
  side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.terms.WorldPos",
      "io.github.izakyl.folkways.rts.combat.command.ResidentCommands",
      "net.minecraft.core.BlockPos",
      "net.minecraft.world.entity.Mob",
    ],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var commands = ResidentCommands.of(colony);
var player = ctx.server().getPlayerList().getPlayerByName(a.string("player"));
var out = new ArrayList<Object>();
if (a.value("holds") != null) for (var raw : a.list("holds")) {
  var row = (Map<?, ?>) raw;
  var p = (Map<?, ?>) row.get("at");
  var body = Bodies.of(level.getEntity(UUID.fromString(String.valueOf(row.get("uuid"))))).orElseThrow();
  var order = commands.guard(body, WorldPos.of(level, new BlockPos(((Number) p.get("x")).intValue(),
      ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue())), new net.minecraft.world.phys.Vec3(1, 0, 0));
  out.add(Map.of("hold", order.id().toString(), "status", order.status().toString()));
}
if (a.value("attacks") != null) for (var raw : a.list("attacks")) {
  var row = (Map<?, ?>) raw;
  var body = Bodies.of(level.getEntity(UUID.fromString(String.valueOf(row.get("uuid"))))).orElseThrow();
  var target = (Mob) level.getEntity(UUID.fromString(String.valueOf(row.get("target"))));
  if (target == null || !target.isAlive()) { out.add(Map.of("skipped", "gone")); continue; }
  int was = player.getInventory().selected;
  try {
    // The commander raises the colony book (kept in the last hotbar slot) to give the order, and lowers it again.
    player.getInventory().selected = 8;
    var orders = commands.attack(List.of(body), target, player);
    out.add(Map.of("attack", orders.getFirst().id().toString(), "status", orders.getFirst().status().toString()));
  } catch (IllegalArgumentException refused) {
    out.add(Map.of("refused", String.valueOf(refused.getMessage())));
  } finally {
    player.getInventory().selected = was;
  }
}
return out;`,
  }),
});

const WORKSHOP_KITS = defineTask<Record<string, any>, Array<{ uuid: string; torches: number }>>({
  name: "folkways.promo.mounted-workshop-kits",
  side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.core.api.terms.ItemSpec",
      "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile",
      "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles",
      "io.github.izakyl.folkways.plugins.person.ResidentEntity",
      "net.minecraft.world.item.Items",
    ],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var profiles = LoadoutProfiles.of(colony);
var out = new ArrayList<Object>();
for (var raw : a.list("residents")) {
  var body = (ResidentEntity) level.getEntity(UUID.fromString(raw.toString()));
  if (Boolean.TRUE.equals(a.value("configure"))) {
    var profile = profiles.assigned(body.id()).orElseThrow();
    var entries = new ArrayList<>(profile.entries());
    entries.add(LoadoutProfile.Entry.supply(ItemSpec.of(Items.TORCH), 8, 8));
    profiles.replace(new LoadoutProfile(profile.id(), profile.name(), entries));
  }
  out.add(Map.of("uuid", body.id().toString(), "torches", body.pack().count(Items.TORCH)));
}
return out;`,
  }),
});
