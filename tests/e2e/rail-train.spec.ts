import { expect, test } from "@playwright/test";
import {
  commandPos,
  entityTypeMatches,
  foundColonyFast,
  optInViaBook,
  tryCommand,
  setVocationPriorityViaPanel,
  toolChestItems,
  TRADE_TOOLS,
  waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import { readDoing } from "../shared/colony/resident-probe";
import { sampleWhileSprinting } from "../shared/harness/tick-tools";
import { buildRailLine, conductorAboard, seatedAt } from "../shared/rail/rail-fixture";
import { handOffSchematic, schematicSpec } from "../shared/ui/schematic-tools";
import { screen, tick, world } from "@izakyl/blockwright-minecraft";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { introspect } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const RESIDENT_TYPE = "folkways:resident";
const CONFIG_PATH = "blockwright.toml";

type Vec = { x: number; y: number; z: number };

const BLUEPRINT_ASSET = "tests/shared/assets/oak-post.nbt";

const BESIDE_TRAIN = 8;
const ALPHA_STOP_RADIUS = 8;

const STUCK_SAMPLES = 3;

// Beta lies far enough down the line that walking there takes about twice as long as waiting out Alpha's dwell and
// riding: the walk stays open, and a planner that weighs both must still pick the train.
const LINE_LENGTH = 600;

// Long enough for the builder, handed the job once the line runs, to fetch the materials and walk to the platform
// before the first train leaves.
const DWELL_ALPHA_SECONDS = 60;
const DWELL_ALPHA_TICKS = DWELL_ALPHA_SECONDS * 20;

const RIDE_TICK_BUDGET = 6_000;

const DEPART_TICK_BUDGET = DWELL_ALPHA_TICKS + 2_000;

const STATION_B = "Beta";
const RESIDENT_COUNT = 4;

test("a real Create train, assembled on real track, between two named stations", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;

  let server: any;
  let client: any;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("e2e", "rail-train-server"), instance: seededServer(`rail-train-server-${runId}`) },
      client: { trace: tracePath("e2e", "rail-train-client"), instance: `rail-train-client-${runId}` },
    });
    ({ server, client } = pair);

    const player = await waitForGroundedPlayer(server);
    await screen.dismiss(client);

    const playerUuid = (await world.players(server)).find((p: any) => p.name === player.name)?.uuid;
    expect(playerUuid, "need the player UUID - assemble uses it as the train owner").toBeTruthy();

    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");
    await tryCommand(server, `gamemode creative ${player.name}`);

    const origin: Vec = {
      x: Math.round(player.pos.x) + 4,
      y: Math.round(player.pos.y),
      z: Math.round(player.pos.z) + 4,
    };
    const founding = await foundColonyFast(server, client, player.name, {
      origin,
      residentCount: RESIDENT_COUNT,
    });
    expect(founding.residents, "residents should have settled, or no one can be conductor").toBeGreaterThanOrEqual(1);
    await tryCommand(server, "time set day");

    const crew = (await world.entities(server, { dimension: "minecraft:overworld", limit: 1000 }))
      .entities.filter((e: any) => entityTypeMatches(e.type, "folkways:resident")) ?? [];
    expect(crew.length, `expected ${RESIDENT_COUNT} residents to split into driver and builder, saw ${crew.length}`)
      .toBeGreaterThanOrEqual(2);
    const driverRole = await setVocationPriorityViaPanel(
      server, client, player.name, founding.standBlock, crew[0].uuid, "folkways:building", 0);
    const builderRole = await setVocationPriorityViaPanel(
      server, client, player.name, founding.standBlock, crew[1].uuid, "folkways:conducting", 0);
    expect(driverRole.ok, `the driver's folkways:building should be off, got ${driverRole.after}`).toBe(true);
    expect(builderRole.ok, `the builder's folkways:conducting should be off, got ${builderRole.after}`).toBe(true);

    await tryCommand(server, `item replace entity ${player.name} inventory.0 from entity ${player.name} weapon.mainhand`);

    // The line runs west of the colony, so the colony and the platforms share its east side and nobody has to
    // walk around the track to reach the train. Alpha faces out past the car's far end: the double-ended train backs
    // out of it to Beta and drives forward back into it.
    const x = origin.x - 8;
    const gy = origin.y;
    const z0 = origin.z + 10;
    const rail = await buildRailLine(server, client, player.name, playerUuid, { x, y: gy, z: z0 }, LINE_LENGTH, "inventory.0", {
      cyclic: true,
      dwell: { Alpha: DWELL_ALPHA_SECONDS },
      // The car is walled and roofed, with one doorway on the platform side by the driver seat, so a resident on foot
      // inside it is one boarding or alighting left there.
      decorate: async (srv, _origin, frame) => {
        await tryCommand(srv, `fill ${commandPos(frame.at(-2, 1, 2))} ${commandPos(frame.at(2, 6, 4))} minecraft:oak_planks replace minecraft:air`);
        await tryCommand(srv, `fill ${commandPos(frame.at(-1, 2, 2))} ${commandPos(frame.at(1, 5, 3))} minecraft:air replace minecraft:oak_planks`);
        await tryCommand(srv, `fill ${commandPos(frame.at(2, 5, 2))} ${commandPos(frame.at(2, 5, 3))} minecraft:air`);
      },
      clickThrough: true,
    });
    expect(rail.ok, `the line should run under the colony: ${rail.error} ${JSON.stringify(rail.steps).slice(0, 400)}`).toBe(true);
    const carriageUuid = rail.carriageUuid!;

    const supplyChest: Vec = { x: origin.x + 4, y: origin.y, z: origin.z + 6 };
    const worksite: Vec = { x: x + 6, y: gy, z: z0 - LINE_LENGTH + 2 };

    await tryCommand(server, `item replace entity ${player.name} weapon.mainhand from entity ${player.name} inventory.0`);
    const heldBook = await tryCommand(server, `data get entity ${player.name} SelectedItem`);
    expect(heldBook?.output?.[0], "the bound lord's book should return to the main hand (hand-over is authed by it)").toContain("folkways");

    await tryCommand(server, `setblock ${commandPos(supplyChest)} minecraft:chest{Items:[${toolChestItems(TRADE_TOOLS.building, RESIDENT_COUNT, 1)}]}`);
    await tick.sprint(server, 5);
    await optInViaBook(server, client, player.name, supplyChest, "chest");
    // A fence filled in where a resident stands walls them into the block; move anyone on the line back to the colony
    // first.
    await tryCommand(server,
      `tp @e[type=${RESIDENT_TYPE},x=${x - 4},y=${gy - 1},z=${z0 - LINE_LENGTH - 12},dx=8,dy=6,dz=${LINE_LENGTH + 24}] ${origin.x + 2.5} ${origin.y} ${origin.z + 3.5}`);
    for (const side of [x - 3, x + 3]) {
      const fenced = await tryCommand(server,
        `fill ${side} ${gy} ${z0 - LINE_LENGTH - 10} ${side} ${gy + 3} ${z0 + 10} minecraft:oak_fence`);
      expect(fenced.success, "there should be a solid fence between the waiting area and the running track").toBe(true);
    }
    await tryCommand(server, `setblock ${commandPos(worksite)} minecraft:air`);
    await tick.sprint(server, 10);
    await tryCommand(server, `tp ${player.name} ${origin.x + 12} ${origin.y} ${origin.z} 0 0`);
    await tick.sprint(server, 20);

    const carriagePos = async (): Promise<Vec | undefined> =>
      (await world.entities(server)).entities.find((e: any) => e.uuid === carriageUuid)?.position;

    const posBeforeDeparture = (await carriagePos())!;

    const uuidFromInts = (v: number[]) => {
      const hex = v.map((n) => (n >>> 0).toString(16).padStart(8, "0")).join("");
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    };

    // Who rides the carriage, in the order they sat down: the conductor first.
    const seatedUuids = async (): Promise<string[]> => {
      const out: string[] = [];
      for (let i = 0; i < 4; i++) {
        const r = await tryCommand(
          server,
          `data get entity @e[type=create:carriage_contraption,limit=1] Passengers[${i}].UUID`,
        );
        if (!r?.success) break;
        const m = /\[I;\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\]/.exec(r.output?.[0] ?? "");
        if (m) out.push(uuidFromInts(m.slice(1, 5).map(Number)));
      }
      return out;
    };

    const sampleResidents = async () => {
      const seated = await seatedUuids();
      return (await world.entities(server)).entities
        .filter((e: any) => entityTypeMatches(e.type, RESIDENT_TYPE))
        .map((e: any) => ({
          x: Math.round(e.position.x * 10) / 10,
          y: Math.round(e.position.y * 10) / 10,
          z: Math.round(e.position.z * 10) / 10,
          uuid: e.uuid,
          riding: seated.includes(e.uuid),
        }));
    };

    const insideCarriageLocal = (c: Vec, cp: Vec | undefined) => {
      if (!cp) return false;
      const lx = c.x - (cp.x - 0.5);
      const ly = c.y - cp.y;
      const lz = c.z - (cp.z - 0.5);
      return lx >= -1.5 && lx < 3 && lz >= 0.5 && lz < 5.5 && ly >= 0.7 && ly <= 2.5;
    };

    const besideCarriage = (c: Vec, cp: Vec | undefined) =>
      cp ? Math.hypot(c.x - cp.x, c.z - cp.z) : Infinity;

    const walkerInside = async (residents: any[]) => {
      const cp = await carriagePos();
      const walking = residents.filter((c) => !c.riding && insideCarriageLocal(c, cp));
      return { carriage: cp, walking: walking.length > 0 ? walking : null };
    };

    let strandedInside: any = null;
    let insideRun = 0;
    const noteInside = (phase: string, atTick: number, w: any) => {
      if (!w.walking) {
        insideRun = 0;
        return;
      }
      insideRun++;
      if (insideRun >= STUCK_SAMPLES && !strandedInside) {
        strandedInside = { tick: atTick, phase, samples: insideRun, carriage: w.carriage, walking: w.walking };
      }
    };

    let staffed = false;
    let ticksWaited = 0;
    for (let i = 0; i < 150 && !staffed; i++) {
      await tick.sprint(server, 20);
      ticksWaited += 20;
      noteInside("conductor", ticksWaited, await walkerInside(await sampleResidents()));
      staffed = await conductorAboard(server, carriageUuid);
    }
    expect(staffed, `a resident should take the driver seat as conductor within ${ticksWaited} ticks`).toBe(true);

    const conductorUuid = (await seatedUuids())[0];
    expect(conductorUuid, "with the conductor in place Passengers[0] should carry the forward conductor's UUID").toBeTruthy();
    const passengers = await tryCommand(server, `data get entity @e[type=create:carriage_contraption,limit=1] Passengers`);
    expect(passengers?.output?.[0], "the one seated on the train should be a resident").toContain("folkways:resident");

    const driverSeatIndex = await seatedAt(server, carriageUuid, conductorUuid);
    expect(driverSeatIndex, "the conductor must still sit in the confirmed driver seat").toBeDefined();
    const driving = await sampleWhileSprinting(server, {
      maxTicks: 100,
      stepTicks: 2,
      read: async (ticks) => ({
        ticks,
        doing: await readDoing(server, conductorUuid),
        seat: await seatedAt(server, carriageUuid, conductorUuid),
      }),
      done: (sample) => sample.doing === "folkways:driving" && sample.seat === driverSeatIndex,
    });
    expect(driving.matched, "the same conductor should pick up the driving task within 100 ticks and stay in the driver seat").toBe(true);
    expect(
      driving.sample.doing,
      "the resident in the driver seat must be running the ongoing driving task; another resident on the ground must not hold it for them",
    ).toBe("folkways:driving");

    const material = Object.entries(schematicSpec(BLUEPRINT_ASSET).materials)[0];
    const supplied = await tryCommand(
      server,
      `item replace block ${commandPos(supplyChest)} container.0 with ${material[0]} ${material[1] + 8}`,
    );
    expect(supplied?.success, "materials should go into the supply chest").toBe(true);

    await tick.sprint(server, 100);

    await handOffSchematic(server, client, player.name, {
      asset: BLUEPRINT_ASSET,
      anchor: worksite,
    });

    const trainAt = async () =>
      await introspect(server, { target: { kind: "entity", uuid: carriageUuid }, limits: { maxDepth: 5, maxFields: 60, maxNodes: 8000 } })
        .then((r: any) => {
          const t = r?.tree?.$fields?.carriage?.$fields?.train?.$fields ?? {};
          return { speed: t.speed, station: t.currentStation?.$string ?? t.currentStation };
        })
        .catch((e: any) => ({ error: String(e) }) as any);

    let rode = 0;
    let rideTicks = 0;
    const approach: Record<string, { closest: number; samples: number }> = {};
    insideRun = 0;
    let step = 5;
    let atStation = true;
    let calls = 1;
    let missed = 0;
    while (rideTicks < RIDE_TICK_BUDGET && rode < 2) {
      await tick.sprint(server, step);
      rideTicks += step;
      const residents = await sampleResidents();
      rode = residents.filter((c: any) => c.riding).length;
      const w = await walkerInside(residents);
      noteInside("passenger", rideTicks, w);
      for (const c of residents.filter((one: any) => !one.riding)) {
        const was = approach[c.uuid];
        approach[c.uuid] = {
          closest: Math.min(was?.closest ?? Infinity, besideCarriage(c, w.carriage)),
          samples: (was?.samples ?? 0) + 1,
        };
      }
      const drift = Math.hypot(
        (w.carriage?.x ?? 0) - posBeforeDeparture.x,
        (w.carriage?.z ?? 0) - posBeforeDeparture.z,
      );
      const here = drift <= ALPHA_STOP_RADIUS;
      if (atStation && !here) missed++;
      else if (!atStation && here) calls++;
      atStation = here;
      step = residents.some((c: any) => !c.riding && w.carriage
        && Math.hypot(c.x - w.carriage.x, c.z - w.carriage.z) < 9) ? 1 : 5;
    }
    expect(
      rode,
      `within ${rideTicks} ticks a second resident should board as a **passenger** (2 residents should be aboard): `
        + `in this window the train called ${calls} times and left empty ${missed} times. The train does not wait for passengers, so missing a call or two is normal; `
        + `if no one boards any of ${calls} calls, timing is not the problem. Closest approaches: ${JSON.stringify(approach)}`,
    ).toBeGreaterThanOrEqual(2);

    expect(
      strandedInside,
      `no one should stand on foot inside the enclosed carriage for ${STUCK_SAMPLES} samples in a row: boarding means sitting down from the platform, `
        + "alighting means being set down on the platform, and the carriage interior is no one's footing anymore (see RailConveyance / Boarding). "
        + "If this reads true, boarding put someone in the carriage without seating them, or alighting left them inside instead of on the platform.",
    ).toBeNull();

    const passengerUuid: string = ((await sampleResidents()).find(
      (c: any) => c.riding && c.uuid !== conductorUuid,
    ) ?? {}).uuid;
    expect(passengerUuid, "the second person aboard (not the conductor) should be identifiable").toBeTruthy();

    const walkedUp = approach[passengerUuid];
    expect(
      walkedUp,
      "the passenger should be sampled on foot at least once before sitting - never means they did not walk to the train, "
        + "they appeared in the seat out of nowhere",
    ).toBeTruthy();
    expect(
      walkedUp?.closest ?? Infinity,
      `before sitting the passenger must be seen standing **beside the train** (within ${BESIDE_TRAIN} blocks of the carriage center, outside it): `
        + `they only got within ${(walkedUp?.closest ?? Infinity).toFixed(1)} blocks. Boarding no longer means walking into the carriage, `
        + "but reaching the platform still takes walking; sitting down from half a colony away is something else.",
    ).toBeLessThan(BESIDE_TRAIN);

    const boardingPosition = (await carriagePos())!;
    const destination = await tryCommand(server,
      `data get entity ${passengerUuid} NeoForgeData."folkways:ride_destination"`);
    expect(Math.hypot(boardingPosition.x - posBeforeDeparture.x, boardingPosition.z - posBeforeDeparture.z),
      "the builder passenger must board at Alpha's outbound or return stop").toBeLessThan(ALPHA_STOP_RADIUS);
    expect(destination.output?.[0], "the builder passenger's destination must be Beta").toContain(`"${STATION_B}"`);

    let moved = 0;
    let departTicks = 0;
    while (departTicks < DEPART_TICK_BUDGET && moved < 2) {
      await tick.sprint(server, 1);
      departTicks += 1;
      const now = await carriagePos();
      moved = Math.hypot((now?.x ?? 0) - boardingPosition.x, (now?.z ?? 0) - boardingPosition.z);
    }
    expect(
      moved,
      `the train should actually depart (the carriage entity's world position must change): waited ${departTicks} ticks, `
        + `and the Alpha dwell is ${DWELL_ALPHA_TICKS} ticks`,
    ).toBeGreaterThan(2);

    let built: string | undefined;
    let buildTicks = 0;

    const at = (cs: any[], uuid: string) => cs.find((c: any) => c.uuid === uuid);
    const distTo = (c: any, p: Vec) => Math.hypot(c.x - (p.x + 0.5), c.y - p.y, c.z - (p.z + 0.5));

    let passengerAlightedOn: any = null;
    let conductorSeatedWhenPassengerAlighted: boolean | null = null;
    const closestOnFootToChest: Record<string, number> = {};
    let lastSample: any[] = await sampleResidents();

    // Tick by tick at first, so the moment the passenger leaves the seat is caught where it happens.
    const FINE_TICKS = 400;
    const BUILD_TICK_BUDGET = 8000;
    while (buildTicks < BUILD_TICK_BUDGET && built !== "minecraft:oak_planks") {
      const step = buildTicks < FINE_TICKS ? 1 : 20;
      await tick.sprint(server, step);
      buildTicks += step;
      built = (await world.block(server, worksite))?.id;
      const residents = await sampleResidents();
      lastSample = residents;

      for (const c of residents.filter((one: any) => !one.riding)) {
        closestOnFootToChest[c.uuid] = Math.min(closestOnFootToChest[c.uuid] ?? Infinity, distTo(c, supplyChest));
      }
      const passenger = at(residents, passengerUuid);
      if (passengerAlightedOn === null && passenger && !passenger.riding) {
        conductorSeatedWhenPassengerAlighted = at(residents, conductorUuid)?.riding ?? false;
        const where = await carriagePos();
        passengerAlightedOn = {
          tick: buildTicks,
          at: { x: passenger.x, y: passenger.y, z: passenger.z },
          carriage: where,
          insideCarriage: insideCarriageLocal(passenger, where),
        };
      }
    }
    const passengerAtBuild = at(lastSample, passengerUuid);

    expect(built, `the block should appear at the worksite within ${buildTicks} ticks`).toBe("minecraft:oak_planks");

    expect(
      passengerAlightedOn,
      "the passenger must actually leave the seat (no longer in the carriage's Passengers list). "
        + "if they are stuck in the seat, any 'the house got built' is not their doing",
    ).not.toBeNull();

    expect(
      conductorSeatedWhenPassengerAlighted,
      "when the passenger alights the conductor should still be in the driver seat (driving, not working for the passenger)",
    ).toBe(true);

    expect(
      passengerAlightedOn?.insideCarriage,
      "alighting should set the passenger down on the platform, not leave them standing in the carriage - no one plans the walk out of the carriage anymore "
        + `(\`RailConveyance.setDownOnThePlatform\`): ${JSON.stringify(passengerAlightedOn)}`,
    ).toBe(false);

    expect(passengerAtBuild?.riding, "the passenger should not still be riding when the block lands").toBe(false);
    expect(
      distTo(passengerAtBuild ?? { x: 0, y: 0, z: 0 }, worksite),
      "when the block lands the **passenger** must be standing at the worksite (within reach) - otherwise they did not place it",
    ).toBeLessThan(6);

    expect(
      closestOnFootToChest[passengerUuid] ?? Infinity,
      "the builder passenger must not go back to the supply chest between departure and completion; chest totals also change from residents left in the colony, so they cannot prove a trip back",
    ).toBeGreaterThan(8);
    expect(
      closestOnFootToChest[conductorUuid] ?? Infinity,
      "before the block is built the conductor should not walk to the colony supply chest (that ~380-block fetch trip is exactly what a false green looks like)",
    ).toBeGreaterThan(8);

    let returnTicks = 0;
    let returned = false;
    let returnState: any;
    while (returnTicks < DEPART_TICK_BUDGET && !returned) {
      await tick.sprint(server, 20);
      returnTicks += 20;
      const position = (await carriagePos())!;
      if (Math.hypot(position.x - posBeforeDeparture.x, position.z - posBeforeDeparture.z) < ALPHA_STOP_RADIUS) {
        returnState = await trainAt();
        returned = returnState.speed === 0 && returnState.station != null;
      }
    }
    expect(returned, `the two-way train must actually return to Alpha after the Beta stop; a looping schedule alone does not prove a return: ${JSON.stringify(returnState)}`).toBe(true);
    expect((await sampleResidents()).find((c: any) => c.uuid === conductorUuid)?.riding,
      "the same conductor should drive the return leg").toBe(true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
