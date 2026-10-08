import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import path from "node:path";
import {
  commandPos,
  entityTypeMatches,
  foundColonyFast,
  optInViaBook,
  tryCommand,
  safeScreen,
  screenName,
  setVocationPriorityViaPanel,
  toolChestItems,
  TRADE_TOOLS,
  waitForGroundedPlayer,
} from "./colony-founding";
import { readDoing } from "./resident-probe";
import { sampleWhileSprinting } from "./tick-tools";
import { drainActionBar, readActionBar } from "./zone-receipt";
import { seatedAt, TAKEN_ON } from "./rail-fixture";
import { handOffSchematic, schematicSpec } from "./schematic-tools";
import { input, media, player as playerActions, reflect, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { launchPair, type E2EPair } from "../shared/pair";
import { REPO_ROOT, ensureOut, reportPath, shotPath, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { reflectVec3, introspect } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const RESIDENT_TYPE = "folkways:resident";
const CONFIG_PATH = "blockwright.toml";
const REPORT_PATH = reportPath("e2e", "rail-train-report");

type Vec = { x: number; y: number; z: number };

const BLUEPRINT_ASSET = "tests/e2e/assets/oak-post.nbt";

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

const STATION_A = "Alpha";
const STATION_B = "Beta";
const RESIDENT_COUNT = 4;

test("a real Create train, assembled on real track, between two named stations", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const report: any = {
    generated_at: new Date().toISOString(),
    scope: "real assembled Create train + two stations",
    traces: { server: tracePath("e2e", "rail-train-server"), client: tracePath("e2e", "rail-train-client") },
    setup: {},
    observations: {},
    result: {},
  };

  let server: any;
  let client: any;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: report.traces.server, instance: seededServer(`rail-train-server-${runId}`) },
      client: { trace: report.traces.client, instance: `rail-train-client-${runId}` },
    });
    ({ server, client } = pair);

    const player = await waitForGroundedPlayer(server);
    report.setup.player = player;
    await screen.dismiss(client);

    const playerUuid = (await world.players(server)).find((p: any) => p.name === player.name)?.uuid;
    expect(playerUuid, "need the player UUID - assemble uses it as the train owner").toBeTruthy();
    report.setup.player_uuid = playerUuid;

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
    report.setup.founding = { origin, residents: founding.residents, bedFeet: founding.bedFeet };
    expect(founding.residents, "residents should have settled, or no one can be conductor").toBeGreaterThanOrEqual(1);
    await tryCommand(server, "time set day");

    const crew = (await world.entities(server, { dimension: "minecraft:overworld", limit: 1000 }))
      .entities.filter((e: any) => entityTypeMatches(e.type, "folkways:resident")) ?? [];
    expect(crew.length, `expected ${RESIDENT_COUNT} residents to split into driver and builder, saw ${crew.length}`)
      .toBeGreaterThanOrEqual(2);
    const driverUuid: string = crew[0].uuid;
    const builderUuid: string = crew[1].uuid;
    report.setup.crew_roles = {
      driver: await setVocationPriorityViaPanel(
        server, client, player.name, founding.standBlock, driverUuid, "folkways:building", 0),
      builder: await setVocationPriorityViaPanel(
        server, client, player.name, founding.standBlock, builderUuid, "folkways:conducting", 0),
    };
    expect(report.setup.crew_roles.driver.ok,
      `the driver's folkways:building should be off, got ${report.setup.crew_roles.driver.after}`).toBe(true);
    expect(report.setup.crew_roles.builder.ok,
      `the builder's folkways:conducting should be off, got ${report.setup.crew_roles.builder.after}`).toBe(true);

    await tryCommand(server, `item replace entity ${player.name} inventory.0 from entity ${player.name} weapon.mainhand`);
    await tryCommand(server, `op ${player.name}`);

    // The line runs west of the colony, so the colony and the platforms share its east side and nobody has to
    // walk around the track to reach the train.
    const x = origin.x - 8;
    const gy = origin.y;
    const z0 = origin.z + 10;

    const track = (z: number): Vec => ({ x, y: gy, z });
    // Both ends face out along the line, so the double-ended train backs out of Alpha to Beta and drives forward back
    // into it: two stations, each name one station's, as a colony timetable needs.
    const stationA: Vec = { x: x + 1, y: gy, z: z0 + 9 };
    const stationB: Vec = { x: x + 1, y: gy, z: z0 - LINE_LENGTH };

    const car = {
      xMin: x - 2, xMax: x + 2,
      zMin: z0 + 3, zMax: z0 + 8,
      floorY: gy + 1, lowY: gy + 2, highY: gy + 3, roofY: gy + 4,
    };
    const doorZ = z0 + 7;
    const bogey: Vec = { x, y: car.floorY, z: z0 + 3 };
    const casing: Vec = { x, y: car.lowY, z: z0 + 3 };
    const controls: Vec = { x, y: car.lowY, z: z0 + 6 };
    const driverSeat: Vec = { x, y: car.lowY, z: z0 + 7 };
    const reverseControls: Vec = { x, y: car.lowY, z: driverSeat.z + 1 };
    const riderSeat: Vec = { x: x - 1, y: car.lowY, z: z0 + 4 };
    const schedulerStand = { x: x + 3.5, y: car.lowY, z: driverSeat.z + 0.5 };
    report.setup.layout = { x, gy, z0, stationA, stationB, car, doorZ, bogey, casing, driverSeat, controls, riderSeat };

    const platformCommands = (zFrom: number, zTo: number) => [
      `fill ${x + 3} ${gy} ${zFrom} ${x + 3} ${gy + 1} ${zTo} minecraft:stone_bricks`,
      `fill ${x + 4} ${gy} ${zFrom} ${x + 4} ${gy} ${zTo} minecraft:stone_bricks`,
    ];

    await tryCommand(server, `forceload add ${x - 16} ${z0 - LINE_LENGTH - 12} ${x + 24} ${z0 + 10}`);
    await tryCommand(server, `fill ${x - 3} ${gy} ${origin.z - 20} ${x + 6} ${gy + 4} ${origin.z - 20} minecraft:air`);
    await tryCommand(server, `fill ${commandPos(track(z0 - LINE_LENGTH - 8))} ${commandPos(track(z0 + 11))} create:track[shape=zo,turn=false,waterlogged=false]`);
    await tick.sprint(server, 20);

    const trackProbe = await world.block(server, track(z0 + 3));
    report.observations.track_block = trackProbe;
    expect(trackProbe?.id, "the track should be laid").toBe("create:track");

    for (const [name, station, yaw] of [[STATION_A, stationA, 0], [STATION_B, stationB, 180]] as const) {
      const target = track(station.z);
      await tryCommand(server, `item replace entity ${player.name} weapon.mainhand with create:track_station`);
      await tryCommand(server, `tp ${player.name} ${x + 2.5} ${gy} ${station.z + 0.5} ${yaw} 0`);
      await tick.sprint(server, 10);

      const aim = await playerActions.useBlock(client, target, { face: "up" });
      await tick.sprint(server, 5);
      const place = await playerActions.useBlock(client, { ...station, y: station.y - 1 }, { face: "up" });
      await tick.sprint(server, 10);
      report.observations[`station_${name}_place`] = { aim: { result: aim.result, block: aim.block }, place: { result: place.result, block: place.block } };
      report.observations[`station_${name}_block`] = await world.block(server, station);
    }
    await tryCommand(server, `clear ${player.name} create:track_station`);
    await tick.sprint(server, 20);

    for (const [station, name] of [[stationA, STATION_A], [stationB, STATION_B]] as const) {
      const renamed = await reflect.setField(server, {
        target: { kind: "blockentity", position: station },
        path: "edgePoint.getEdgePoint()",
        field: "name",
        value: name,
      });
      report.observations[`rename_${name}`] = { previous: renamed?.previous, ownerType: renamed?.ownerType };
      expect(renamed?.found, `station ${name}'s BlockEntity should resolve`).toBe(true);
      expect(renamed?.ownerType, `station ${name}'s GlobalStation should already exist`).toContain("GlobalStation");
    }

    await tryCommand(server, `fill ${x - 2} ${car.floorY} ${car.zMin} ${x + 2} ${car.floorY} ${car.zMax} minecraft:oak_planks`);
    await tryCommand(server, `setblock ${commandPos(bogey)} create:small_bogey[axis=z]`);
    await tryCommand(server, `setblock ${commandPos(casing)} create:railway_casing`);
    // Create assembles from the bogey at the station, and Alpha stands past the car's far end.
    await tryCommand(server, `setblock ${x} ${car.floorY} ${car.zMax} create:small_bogey[axis=z]`);

    await tryCommand(server, `fill ${x - 2} ${car.lowY} ${car.zMin} ${x + 2} ${car.roofY} ${car.zMax} minecraft:oak_planks`);
    await tryCommand(server, `fill ${x - 1} ${car.lowY} ${car.zMin + 1} ${x + 1} ${car.highY} ${car.zMax - 1} minecraft:air`);
    await tryCommand(server, `setblock ${commandPos(casing)} create:railway_casing`);
    await tryCommand(server, `fill ${x + 2} ${car.lowY} ${doorZ} ${x + 2} ${car.highY} ${doorZ} minecraft:air`);

    await tryCommand(server, `setblock ${commandPos(riderSeat)} create:red_seat`);
    await tryCommand(server, `setblock ${commandPos(driverSeat)} create:red_seat`);
    await tryCommand(server, `setblock ${commandPos(controls)} create:controls[facing=south]`);
    await tryCommand(server, `setblock ${commandPos(reverseControls)} create:controls[facing=north]`);
    await tick.sprint(server, 5);

    const placed: Record<string, string | undefined> = {};
    for (const [name, pos] of [
      ["bogey", bogey], ["casing", casing], ["riderSeat", riderSeat],
      ["driverSeat", driverSeat], ["controls", controls], ["reverseControls", reverseControls],
    ] as const) {
      placed[name] = (await world.block(server, pos))?.id;
    }
    report.observations.placed_blocks = placed;
    expect(placed.controls, "create:controls should actually be placed").toBe("create:controls");
    expect(placed.driverSeat, "the create:red_seat in the conductor cell should actually be placed").toBe("create:red_seat");

    report.observations.glue = (await tryCommand(
      server,
      `create glue ${x - 2} ${car.floorY} ${car.zMin} ${x + 2} ${car.roofY} ${car.zMax}`,
    ))?.success;
    await tick.sprint(server, 20);

    const assemblyMode = await reflect.invoke(server, {
      target: { kind: "blockentity", position: stationA },
      method: "tryEnterAssemblyMode",
      returnType: "boolean",
    });
    report.observations.enter_assembly_mode = { signature: assemblyMode?.signature, returned: assemblyMode?.returned };
    expect(assemblyMode?.returned, "the station should enter assembly mode").toBe(true);
    await tick.sprint(server, 20);

    await reflect.invoke(server, {
      target: { kind: "blockentity", position: stationA },
      method: "refreshAssemblyInfo",
      returnType: "void",
    });

    const beforeAssembly = await introspect(server, {
      target: { kind: "blockentity", position: stationA },
      limits: { maxDepth: 2, maxFields: 40 },
    });
    const beforeFields = (beforeAssembly as any)?.tree?.$fields ?? {};
    report.observations.assembly_direction = beforeFields.assemblyDirection;
    report.observations.bogey_count_before = beforeFields.bogeyCount;
    expect(report.observations.assembly_direction, "assemblyDirection should point along -Z, back from Alpha over the car")
      .toBe("NORTH");
    expect(report.observations.bogey_count_before, "the assembly area should count that one bogey").toBeGreaterThan(0);

    const assembled = await reflect.invoke(server, {
      target: { kind: "blockentity", position: stationA },
      method: "assemble",
      args: [playerUuid],
      argTypes: ["java.util.UUID"],
      returnType: "void",
    });
    report.observations.assemble = { found: assembled?.found, signature: assembled?.signature };
    expect(assembled?.found, "the station BlockEntity should resolve").toBe(true);

    const exited = await reflect.invoke(server, {
      target: { kind: "blockentity", position: stationA },
      method: "exitAssemblyMode",
      returnType: "boolean",
    });
    report.observations.exit_assembly_mode = exited?.returned;
    expect(assembled?.signature, "should hit assemble(UUID)").toContain("assemble(UUID)void");
    await tick.sprint(server, 40);

    const afterAssembly = await introspect(server, {
      target: { kind: "blockentity", position: stationA },
      limits: { maxDepth: 3, maxFields: 40 },
    });
    const afterFields = (afterAssembly as any)?.tree?.$fields ?? {};
    report.observations.last_exception = JSON.stringify(afterFields.lastException)?.slice(0, 400);
    report.observations.train_present = afterFields.trainPresent;
    report.observations.failed_carriage_index = afterFields.failedCarriageIndex;

    const carriages = (await world.entities(server)).entities
      ?.filter((e: any) => entityTypeMatches(e.type, "create:carriage_contraption")) ?? [];
    report.observations.carriage_entities = carriages;

    report.result.status = "assembled";

    expect(
      afterFields.lastException,
      `assembly failed (Create reports silent failures via lastException): ${report.observations.last_exception}`,
    ).toBeFalsy();
    expect(carriages.length, "a carriage_contraption entity should appear in the world").toBeGreaterThan(0);
    expect(afterFields.trainPresent, "the station should see a train parked here").toBe(true);

    const carriageUuid = carriages[0].uuid;
    report.setup.carriage_uuid = carriageUuid;

    const conductorSeats = await world.entity(server, { uuid: carriageUuid }, {
      nbtPath: "Contraption.ConductorSeats",
    });
    report.observations.conductor_seats = conductorSeats?.nbt;
    const forwardSeat = conductorSeatEntry(conductorSeats?.nbt);
    expect(forwardSeat, `Create should record the driver seat as a forward conductor seat: ${JSON.stringify(conductorSeats?.nbt)}`)
      .toBeTruthy();
    expect(forwardSeat!.forward, "the driver seat should be a forward conductor seat").toBeTruthy();
    expect(forwardSeat!.backward, "the same driver seat must also drive the return leg").toBeTruthy();

    const alignmentProbe = await introspect(server, {
      target: { kind: "entity", uuid: carriageUuid }, limits: { maxDepth: 1, maxFields: 20 },
    });
    const carriagePosNow = (await world.entities(server)).entities
      ?.find((e: any) => e.uuid === carriageUuid)?.position;
    const frac = (v: number) => Math.abs(v - Math.floor(v) - 0.5);
    report.observations.grid_alignment = {
      carriagePos: carriagePosNow,
      fracX: frac(carriagePosNow?.x ?? 0),
      fracZ: frac(carriagePosNow?.z ?? 0),
      y: carriagePosNow?.y,
      probe: (alignmentProbe as any)?.tree?.$fields?.blockPosition,
    };
    expect(frac(carriagePosNow?.x ?? 0), "the parked carriage x must sit at a block center (fraction .5)").toBeLessThan(1e-3);
    expect(frac(carriagePosNow?.z ?? 0), "the parked carriage z must sit at a block center (fraction .5)").toBeLessThan(1e-3);
    expect(
      Math.abs((carriagePosNow?.y ?? 0) - Math.round(carriagePosNow?.y ?? 0)),
      "the parked carriage y must be an integer",
    ).toBeLessThan(1e-3);

    for (const cmd of platformCommands(z0 + 4, z0 + 8)) {
      await tryCommand(server, cmd);
    }
    for (const cmd of platformCommands(stationB.z - 2, stationB.z + 10)) {
      await tryCommand(server, cmd);
    }
    await tick.sprint(server, 10);
    report.observations.platforms = {
      alphaTop: (await world.block(server, { x: x + 3, y: gy + 1, z: doorZ }))?.id,
      betaTop: (await world.block(server, { x: x + 3, y: gy + 1, z: stationB.z + 2 }))?.id,
    };
    expect(report.observations.platforms.alphaTop, "the Alpha platform should be built").toBe("minecraft:stone_bricks");

    const conductorProbe = async (method: string) =>
      (await reflect
        .invoke(server, {
          target: { kind: "entity", uuid: carriageUuid },
          path: "getCarriage().train",
          method,
          returnType: "boolean",
        })
        .catch((e: any) => ({ error: String(e) }))) as any;
    const forwardConductor = await conductorProbe("hasForwardConductor");
    report.observations.conductor_before_anyone_boards = {
      forward: forwardConductor?.returned,
      backward: (await conductorProbe("hasBackwardConductor"))?.returned,
      contraption: await introspect(server, { target: { kind: "entity", uuid: carriageUuid }, limits: { maxDepth: 3, maxFields: 60 } })
        .then((r: any) => {
          const c = r?.tree?.$fields?.contraption?.$fields ?? {};
          return {
            blockConductors: c.blockConductors?.$string,
            conductorSeats: Object.keys(c.conductorSeats ?? {}).filter((key) => key !== "$more"),
          };
        })
        .catch((e: any) => ({ error: String(e) })),
    };
    expect(forwardConductor?.returned, "with no resident in the driver seat the train should have no conductor").toBe(false);

    const dwell = (seconds: number) => `Conditions:[[{Id:"create:delay",Data:{Value:${seconds},TimeUnit:1}}]]`;
    const scheduleTag = [
      "Entries:[",
      `{Instruction:{Id:"create:destination",Data:{Text:"${STATION_A}"}},${dwell(DWELL_ALPHA_SECONDS)}},`,
      `{Instruction:{Id:"create:destination",Data:{Text:"${STATION_B}"}},${dwell(10)}}`,
      "],Cyclic:1b,Progress:0",
    ].join("");
    const gave = await tryCommand(
      server,
      `give ${player.name} create:schedule[create:train_schedule={${scheduleTag}}] 1`,
    );
    report.observations.give_schedule = gave?.success;
    expect(gave?.success, "a create:schedule item with an Alpha->Beta schedule should be given").toBe(true);

    expect(forwardSeat!.pos, `the driver seat's local position should be readable from ConductorSeats: ${JSON.stringify(conductorSeats?.nbt)}`)
      .toBeTruthy();
    const seatLocal = {
      x: forwardSeat!.pos!.x + 0.5,
      y: forwardSeat!.pos!.y + 0.5,
      z: forwardSeat!.pos!.z + 0.5,
    };
    const seatGlobal = await reflect.invoke(server, {
      target: { kind: "entity", uuid: carriageUuid },
      method: "toGlobalVector",
      args: [seatLocal, 1.0],
      argTypes: ["net.minecraft.world.phys.Vec3", "float"],
      returnType: "net.minecraft.world.phys.Vec3",
      limits: { maxDepth: 1 },
    });
    const seat: Vec = reflectVec3(seatGlobal);
    report.observations.seat_world = { local: seatLocal, global: seat, preAssemblyBlock: driverSeat };
    expect(
      seat && Number.isFinite(seat.x) && Number.isFinite(seat.y) && Number.isFinite(seat.z),
      "toGlobalVector should compute the conductor seat's current world position",
    ).toBe(true);
    expect(seat!.x, "the converted driver seat should be exactly the center of its pre-assembly cell (x)").toBeCloseTo(driverSeat.x + 0.5, 2);
    expect(seat!.y, "the converted driver seat should be exactly the center of its pre-assembly cell (y)").toBeCloseTo(driverSeat.y + 0.5, 2);
    expect(seat!.z, "the converted driver seat should be exactly the center of its pre-assembly cell (z)").toBeCloseTo(driverSeat.z + 0.5, 2);

    await tryCommand(server, `tp ${player.name} ${schedulerStand.x} ${schedulerStand.y} ${schedulerStand.z}`);
    await tick.sprint(server, 10);
    report.observations.player_on_platform =
      (await world.players(server)).find((q: any) => q.name === player.name)?.position;

    const aimAt = async (target: Vec) => {
      const p: any = (await world.players(server)).find((q: any) => q.name === player.name);
      const eye = { x: p.position.x, y: p.position.y + 1.62, z: p.position.z };
      const dx = target.x - eye.x;
      const dy = target.y - eye.y;
      const dz = target.z - eye.z;
      const yaw = (Math.atan2(-dx, dz) * 180) / Math.PI;
      const pitch = (-Math.atan2(dy, Math.hypot(dx, dz)) * 180) / Math.PI;
      await playerActions.place(client, { yaw, pitch });
      await tick.sprint(server, 5);
      const cursor = await input.cursor(client).catch(() => null);
      const after = (await world.players(server)).find((q: any) => q.name === player.name);
      return {
        eye,
        target,
        yaw,
        pitch,
        distance: Math.hypot(dx, dy, dz),
        hit: cursor?.hitResult ?? null,
        posBefore: p?.position,
        posAfter: after?.position,
      };
    };
    report.observations.aim = await aimAt(seat!);

    const scheduleOnTrain = async () => {
      const r = await reflect
        .invoke(server, {
          target: { kind: "entity", uuid: carriageUuid },
          path: "getCarriage().train.runtime",
          method: "getSchedule",
          returnType: "com.simibubi.create.content.trains.schedule.Schedule",
          limits: { maxDepth: 2 },
        })
        .catch((e: any) => ({ error: String(e) }));
      return r as any;
    };

    await playerActions.place(client, { yaw: -90, pitch: 0 });
    await tick.sprint(server, 3);
    await input.button(client, { button: "right", action: "press" });
    await tick.sprint(server, 3);
    await input.button(client, { button: "right", action: "release" });
    await tick.sprint(server, 10);
    const editor = await safeScreen(client);
    await media.screenshot(client, { path: shotPath("e2e", "rail-schedule-editor") }).catch(() => {});
    report.observations.schedule_editor = {
      screen: screenName(editor),
      widgetCount: (await screen.widgets(client).catch(() => [])).length,
      shot: shotPath("e2e", "rail-schedule-editor"),
    };
    await screen.dismiss(client);
    await tick.sprint(server, 5);

    const offhand = await tryCommand(
      server, `item replace entity ${player.name} weapon.offhand from entity ${player.name} inventory.0`);
    report.observations.book_offhand = { success: offhand?.success };
    expect(offhand?.success, "the bound lord's book should go into the offhand (hand-over auth)").toBe(true);

    let installed: any = null;
    let takenOn = false;
    const attempts: any[] = [];
    for (let attempt = 0; attempt < 2 && !takenOn; attempt++) {
      await screen.dismiss(client);
      await tryCommand(server, `tp ${player.name} ${schedulerStand.x} ${schedulerStand.y} ${schedulerStand.z}`);
      await tick.sprint(server, 10);
      const aim = await aimAt(seat!);

      await drainActionBar(client).catch(() => null);

      await input.button(client, { button: "right", action: "press" });
      await tick.sprint(server, 3);
      await input.button(client, { button: "right", action: "release" });
      await tick.sprint(server, 10);

      const bar = await readActionBar(client).catch(() => ({ text: "", remaining_ticks: 0 }));
      takenOn = takenOn || (bar.remaining_ticks > 0 && bar.text.includes(TAKEN_ON));
      installed = await scheduleOnTrain();
      attempts.push({
        attempt,
        action_bar: bar,
        takenOn,
        aimedAt: aim.hit,
        aim: { yaw: aim.yaw, pitch: aim.pitch, eye: aim.eye, posBefore: aim.posBefore, posAfter: aim.posAfter },
        screen: screenName(await safeScreen(client)),
        hasSchedule: Boolean(installed?.returned),
        held: (await tryCommand(server, `data get entity ${player.name} SelectedItem`))?.output?.[0]?.slice(0, 160),
      });
    }
    await screen.dismiss(client);
    report.observations.schedule_attempts = attempts;
    report.observations.schedule_install = {
      returnedType: installed?.returnedType,
      hasSchedule: Boolean(installed?.returned),
      error: installed?.error,
    };

    const playerSeat = await seatedAt(server, carriageUuid, playerUuid);
    report.observations.player_seat = playerSeat;
    expect(playerSeat, "right-clicking the conductor seat with the book in the offhand should hand over, not sit").toBeUndefined();

    expect(installed?.returned, "after the player right-clicks the conductor seat the train runtime should hold a schedule").toBeTruthy();
    expect(takenOn, `the action bar should report the hand-over receipt "${TAKEN_ON}": ${JSON.stringify(attempts.map((a) => a.action_bar))}`)
      .toBe(true);

    report.result.status = "scheduled";

    const supplyChest: Vec = { x: origin.x + 4, y: origin.y, z: origin.z + 6 };
    const worksite: Vec = { x: x + 6, y: gy, z: z0 - LINE_LENGTH + 2 };
    report.setup.passenger = { supplyChest, blueprint: BLUEPRINT_ASSET, worksite, stationB };

    await tryCommand(server, `item replace entity ${player.name} weapon.mainhand from entity ${player.name} inventory.0`);
    const heldBook = await tryCommand(server, `data get entity ${player.name} SelectedItem`);
    report.observations.book_restored = heldBook?.output?.[0]?.slice(0, 160);
    expect(heldBook?.output?.[0], "the bound lord's book should return to the main hand (hand-over is authed by it)").toContain("folkways");

    await tryCommand(server, `setblock ${commandPos(supplyChest)} minecraft:chest{Items:[${toolChestItems(TRADE_TOOLS.building, RESIDENT_COUNT, 1)}]}`);
    await tick.sprint(server, 5);
    report.observations.supply_opt_in = await optInViaBook(server, client, player.name, supplyChest, "chest");
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

    const seatTruth = async () => {
      const ent = (await world.entities(server)).entities.find((e: any) => e.uuid === carriageUuid);
      return {
        carriageNetId: ent?.id,
        seatMapping: (await tryCommand(
          server, `data get entity @e[type=create:carriage_contraption,limit=1] Contraption.Passengers`,
        ))?.output?.[0]?.slice(0, 200),
        vanillaPassengers: [...(await seatedUuids())].map((u) => u.slice(0, 8)),
      };
    };

    const posBeforeDeparture = await carriagePos();
    const residentTrail: any[] = [];

    const uuidFromInts = (v: number[]) => {
      const hex = v.map((n) => (n >>> 0).toString(16).padStart(8, "0")).join("");
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    };

    const seatedUuids = async (): Promise<Set<string>> => {
      const out = new Set<string>();
      for (let i = 0; i < 4; i++) {
        const r = await tryCommand(
          server,
          `data get entity @e[type=create:carriage_contraption,limit=1] Passengers[${i}].UUID`,
        );
        if (!r?.success) break;
        const m = /\[I;\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\]/.exec(r.output?.[0] ?? "");
        if (m) out.add(uuidFromInts(m.slice(1, 5).map(Number)));
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
          riding: seated.has(e.uuid),
        }));
    };

    const probePlans = async () => {
      const out: any[] = [];
      for (const c of await sampleResidents()) {
        const p: any = { pos: { x: c.x, y: c.y, z: c.z }, riding: c.riding };
        try {
          p.current = await readDoing(server, c.uuid);
          p.vehicle = await introspect(server, { target: { kind: "entity", uuid: c.uuid }, limits: { maxDepth: 1, maxFields: 200 } })
            .then((r: any) => {
              const v = r?.tree?.$fields?.vehicle;
              return v == null ? null : (v.$string ?? v.$summary ?? v.$type ?? JSON.stringify(v).slice(0, 60));
            })
            .catch((e: any) => String(e).slice(0, 60));
        } catch (error) {
          const text = String(error);
          p.idle = text.includes("hit null");
          if (!p.idle) p.probe_error = text.slice(0, 160);
        }
        out.push(p);
      }
      return out;
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

    let staffed = false;
    let ticksWaited = 0;
    let strandedInside: any = null;
    let insideSeen: any = null;
    let insideRun = 0;
    const noteInside = (phase: string, atTick: number, w: any) => {
      if (!w.walking) {
        insideRun = 0;
        return;
      }
      insideRun++;
      const one = { tick: atTick, phase, samples: insideRun, carriage: w.carriage, walking: w.walking };
      if (!insideSeen) insideSeen = one;
      if (insideRun >= STUCK_SAMPLES && !strandedInside) strandedInside = one;
    };
    for (let i = 0; i < 150 && !staffed; i++) {
      await tick.sprint(server, 20);
      ticksWaited += 20;
      const residents = await sampleResidents();
      const w = await walkerInside(residents);
      noteInside("conductor", ticksWaited, w);
      staffed = reflect.boolean(await conductorProbe("hasForwardConductor"));
      if (i % 3 === 0 || staffed || w.walking) {
        residentTrail.push({ tick: ticksWaited, residents, carriage: w.carriage, insideOnFoot: w.walking });
      }
    }
    report.observations.conductor_arrival = {
      staffed, ticksWaited, seatWorld: seat, strandedInside, insideSeen, residentTrail,
    };

    const seatedNow = (await sampleResidents()).filter((c: any) => c.riding);
    const p0 = await tryCommand(server, `data get entity @e[type=create:carriage_contraption,limit=1] Passengers[0].UUID`);
    const p0m = /\[I;\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\]/.exec(p0?.output?.[0] ?? "");
    const conductorUuid: string = p0m
      ? uuidFromInts([Number(p0m[1]), Number(p0m[2]), Number(p0m[3]), Number(p0m[4])])
      : seatedNow[0]?.uuid;
    expect(conductorUuid, "with the conductor in place Passengers[0] should carry the forward conductor's UUID").toBeTruthy();
    report.observations.conductor_identity = conductorUuid;
    report.observations.seated_at_conductor_arrival = seatedNow.length;

    const passengers = await tryCommand(
      server,
      `data get entity @e[type=create:carriage_contraption,limit=1] Passengers`,
    );
    report.observations.passengers = passengers?.output?.[0]?.slice(0, 300);
    if (!staffed) {
      const nearest = (await world.entities(server)).entities
        .filter((e: any) => entityTypeMatches(e.type, RESIDENT_TYPE))
        .sort((a: any, b: any) =>
          Math.hypot(a.position.x - seat!.x, a.position.z - seat!.z)
          - Math.hypot(b.position.x - seat!.x, b.position.z - seat!.z))?.[0];
      report.observations.stuck_resident = {
        entity: nearest,
        tree: await introspect(server, { target: { kind: "entity", uuid: nearest?.uuid }, limits: { maxDepth: 4, maxFields: 30 } })
          .then((r: any) => JSON.stringify(r?.tree).slice(0, 4000))
          .catch((e: any) => String(e)),
      };
    }
    report.observations.seat_table = {
      seatsNbt: (await tryCommand(
        server, `data get entity @e[type=create:carriage_contraption,limit=1] Contraption.Seats`,
      ))?.output?.[0]?.slice(0, 300),
      seatMappingNbt: (await tryCommand(
        server, `data get entity @e[type=create:carriage_contraption,limit=1] Contraption.Passengers`,
      ))?.output?.[0]?.slice(0, 300),
      live: await introspect(server, { target: { kind: "entity", uuid: carriageUuid }, limits: { maxDepth: 4, maxFields: 60 } })
        .then((r: any) => {
          const c = r?.tree?.$fields?.contraption?.$fields ?? {};
          return { seats: JSON.stringify(c.seats)?.slice(0, 400), seatMapping: JSON.stringify(c.seatMapping)?.slice(0, 300) };
        })
        .catch((e: any) => ({ error: String(e) })),
    };

    expect(staffed, `a resident should take the driver seat as conductor within ${ticksWaited} ticks`).toBe(true);
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
    report.observations.conductor_handover = driving;
    expect(driving.matched, "the same conductor should pick up the driving task within 100 ticks and stay in the driver seat").toBe(true);
    report.observations.conductor_doing = driving.sample.doing;
    expect(
      report.observations.conductor_doing,
      "the resident in the driver seat must be running the ongoing driving task; another resident on the ground must not hold it for them",
    ).toBe("folkways:driving");

    report.result.status = "conducted";

    const material = Object.entries(schematicSpec(BLUEPRINT_ASSET).materials)[0];
    const supplied = await tryCommand(
      server,
      `item replace block ${commandPos(supplyChest)} container.0 with ${material[0]} ${material[1] + 8}`,
    );
    report.observations.supplied = supplied?.success;
    expect(supplied?.success, "materials should go into the supply chest").toBe(true);

    await tick.sprint(server, 100);
    report.observations.rail_settled_ticks = 100;

    report.observations.blueprint = (await handOffSchematic(server, client, player.name, {
      asset: BLUEPRINT_ASSET,
      anchor: worksite,
    })).receipt;
    report.observations.worksite_after_place = (await world.block(server, worksite))?.id;

    const passengerProbe = async () => {
      const at = async (i: number) =>
        await tryCommand(server, `data get entity @e[type=create:carriage_contraption,limit=1] Passengers[${i}].id`);
      const first = await at(0);
      const second = await at(1);
      return {
        count: (first?.success ? 1 : 0) + (second?.success ? 1 : 0),
        firstId: first?.output?.[0]?.slice(0, 80),
        secondId: second?.output?.[0]?.slice(0, 80),
      };
    };

    const trainSnapshot = async () =>
      await introspect(server, { target: { kind: "entity", uuid: carriageUuid }, limits: { maxDepth: 5, maxFields: 60, maxNodes: 8000 } })
        .then((r: any) => {
          const t = r?.tree?.$fields?.carriage?.$fields?.train?.$fields ?? {};
          const rt = t.runtime?.$fields ?? {};
          return {
            speed: typeof t.speed === "number" ? Math.round(t.speed * 100) / 100 : t.speed,
            station: t.currentStation?.$string ?? t.currentStation,
            entry: rt.currentEntry,
            state: rt.state?.$string ?? rt.state?.$enum ?? rt.state,
            title: rt.currentTitle,
            conditions: JSON.stringify(rt.conditionProgress ?? rt.conditionContext ?? null)?.slice(0, 120),
          };
        })
        .catch((e: any) => ({ error: String(e) }));

    let rode: any = { count: 0 };
    let rideTicks = 0;
    const rideTrail: any[] = [];
    const approach: Record<string, { closest: number; samples: number; spots: Set<string>; last: any }> = {};
    insideRun = 0;
    let step = 5;
    let atStation = true;
    let calls = 1;
    let missed = 0;
    while (rideTicks < RIDE_TICK_BUDGET && rode.count < 2) {
      await tick.sprint(server, step);
      rideTicks += step;
      rode = await passengerProbe();
      const residents = await sampleResidents();
      const w = await walkerInside(residents);
      noteInside("passenger", rideTicks, w);
      for (const c of residents.filter((one: any) => !one.riding)) {
        const apart = besideCarriage(c, w.carriage);
        const was = approach[c.uuid];
        const spots = was?.spots ?? new Set<string>();
        spots.add(`${Math.floor(c.x)},${Math.floor(c.y)},${Math.floor(c.z)}`);
        approach[c.uuid] = {
          closest: Math.min(was?.closest ?? Infinity, apart),
          samples: (was?.samples ?? 0) + 1,
          spots,
          last: { tick: rideTicks, x: c.x, y: c.y, z: c.z, apart: Math.round(apart * 10) / 10 },
        };
      }
      const drift = Math.hypot(
        (w.carriage?.x ?? 0) - (posBeforeDeparture?.x ?? 0),
        (w.carriage?.z ?? 0) - (posBeforeDeparture?.z ?? 0),
      );
      const here = drift <= ALPHA_STOP_RADIUS;
      if (atStation && !here) {
        missed++;
        rideTrail.push({ tick: rideTicks, note: `call ${calls} departed before filling up`, drift });
      } else if (!atStation && here) {
        calls++;
        rideTrail.push({ tick: rideTicks, note: `call ${calls} arrived` });
      }
      atStation = here;
      if (here || rideTicks % 100 < step) {
        rideTrail.push({
          tick: rideTicks,
          passengers: rode.count,
          residents,
          carriage: w.carriage,
          insideOnFoot: w.walking,
        });
      }
      step = residents.some((c: any) => !c.riding && w.carriage
        && Math.hypot(c.x - w.carriage.x, c.z - w.carriage.z) < 9) ? 1 : 5;
    }
    report.observations.passenger_ride = {
      rode,
      ticksWaited: rideTicks,
      calls,
      missed,
      trail: rideTrail,
      strandedInside,
      insideSeen,
      approach: Object.fromEntries(Object.entries(approach).map(([who, one]) => [who, {
        closest: Math.round(one.closest * 10) / 10,
        samples: one.samples,
        spots: one.spots.size,
        last: one.last,
      }])),
    };
    expect(
      rode.count,
      `within ${rideTicks} ticks a second resident should board as a **passenger** (2 residents should be aboard): `
        + `in this window the train called ${calls} times and left empty ${missed} times. The train does not wait for passengers, so missing a call or two is normal; `
        + `if no one boards any of ${calls} calls, timing is not the problem. See report.observations.passenger_ride.approach`,
    ).toBeGreaterThanOrEqual(2);
    expect(rode.secondId, "the second passenger should be a resident").toContain("folkways:resident");

    expect(
      strandedInside,
      `no one should stand on foot inside the enclosed carriage for ${STUCK_SAMPLES} samples in a row: boarding means sitting down from the platform, `
        + "alighting means being set down on the platform, and the carriage interior is no one's footing anymore (see RailConveyance / Boarding). "
        + "If this reads true, boarding put someone in the carriage without seating them, or alighting left them inside instead of on the platform. "
        + "Trail in report.observations.passenger_ride.trail",
    ).toBeNull();

    const passengerUuid: string = ((await sampleResidents()).find(
      (c: any) => c.riding && c.uuid !== conductorUuid,
    ) ?? {}).uuid;
    expect(passengerUuid, "the second person aboard (not the conductor) should be identifiable").toBeTruthy();
    report.observations.passenger_identity = passengerUuid;

    const walkedUp = approach[passengerUuid];
    expect(
      walkedUp,
      "the passenger should be sampled on foot at least once before sitting - never means they did not walk to the train, "
        + "they appeared in the seat out of nowhere. See report.observations.passenger_ride.approach",
    ).toBeTruthy();
    expect(
      walkedUp?.closest ?? Infinity,
      `before sitting the passenger must be seen standing **beside the train** (within ${BESIDE_TRAIN} blocks of the carriage center, outside it): `
        + `they only got within ${(walkedUp?.closest ?? Infinity).toFixed(1)} blocks. Boarding no longer means walking into the carriage, `
        + "but reaching the platform still takes walking; sitting down from half a colony away is something else. "
        + "See report.observations.passenger_ride.approach",
    ).toBeLessThan(BESIDE_TRAIN);

    report.result.status = "boarded";

    const boardingPosition = await carriagePos();
    const destination = await tryCommand(server,
      `data get entity ${passengerUuid} NeoForgeData."folkways:ride_destination"`);
    report.observations.boarding = { position: boardingPosition, destination };
    expect(Math.hypot(boardingPosition.x - posBeforeDeparture.x, boardingPosition.z - posBeforeDeparture.z),
      "the builder passenger must board at Alpha's outbound or return stop").toBeLessThan(ALPHA_STOP_RADIUS);
    expect(destination.output?.[0], "the builder passenger's destination must be Beta").toContain(`"${STATION_B}"`);

    let posAfterDeparture = boardingPosition;
    let moved = 0;
    let departTicks = 0;
    while (departTicks < DEPART_TICK_BUDGET && moved < 2) {
      await tick.sprint(server, 1);
      departTicks += 1;
      posAfterDeparture = await carriagePos();
      moved = Math.hypot(
        (posAfterDeparture?.x ?? 0) - boardingPosition.x,
        (posAfterDeparture?.z ?? 0) - boardingPosition.z,
      );
    }
    report.observations.train_state = await trainSnapshot();
    report.observations.departure = { posBeforeDeparture, posAfterDeparture, moved, departTicks };
    expect(
      moved,
      `the train should actually depart (the carriage entity's world position must change): waited ${departTicks} ticks, `
        + `and the Alpha dwell is ${DWELL_ALPHA_TICKS} ticks`,
    ).toBeGreaterThan(2);

    report.result.status = "departed";

    let built: string | undefined;
    let buildTicks = 0;
    const buildTrail: any[] = [];
    const fineTrail: any[] = [];

    const planksInChest = async (): Promise<number> => {
      const nbt = (await tryCommand(server, `data get block ${commandPos(supplyChest)} Items`))?.output?.[0] ?? "";
      let total = 0;
      for (const m of nbt.matchAll(/\{[^}]*?count:\s*(\d+)[^}]*?oak_planks[^}]*?\}/g)) total += Number(m[1]);
      return total;
    };
    const planksAtDeparture = await planksInChest();

    const at = (cs: any[], uuid: string) => cs.find((c: any) => c.uuid === uuid);
    const distTo = (c: any, p: Vec) => Math.hypot(c.x - (p.x + 0.5), c.y - p.y, c.z - (p.z + 0.5));

    let passengerAlightedAt: number | null = null;
    let passengerAlightedOn: any = null;
    let conductorSeatedWhenPassengerAlighted: boolean | null = null;
    const closestOnFootToChest: Record<string, number> = {};
    let lastSample: any[] = await sampleResidents();

    const FINE_TICKS = 400;
    const BUILD_TICK_BUDGET = 8000;
    const MAX_ITERATIONS = FINE_TICKS + (BUILD_TICK_BUDGET - FINE_TICKS) / 20;
    for (let i = 0; i < MAX_ITERATIONS && buildTicks < BUILD_TICK_BUDGET
      && built !== "minecraft:oak_planks"; i++) {
      const fine = buildTicks < FINE_TICKS;
      const step = fine ? 1 : 20;
      await tick.sprint(server, step);
      buildTicks += step;
      built = (await world.block(server, worksite))?.id;
      const residents = await sampleResidents();
      lastSample = residents;

      for (const c of residents.filter((x: any) => !x.riding)) {
        closestOnFootToChest[c.uuid] = Math.min(closestOnFootToChest[c.uuid] ?? Infinity, distTo(c, supplyChest));
      }
      const passenger = at(residents, passengerUuid);
      if (passengerAlightedAt === null && passenger && !passenger.riding) {
        passengerAlightedAt = buildTicks;
        conductorSeatedWhenPassengerAlighted = at(residents, conductorUuid)?.riding ?? false;
        const where = await carriagePos();
        passengerAlightedOn = {
          tick: buildTicks,
          at: { x: passenger.x, y: passenger.y, z: passenger.z },
          carriage: where,
          apart: Math.round(besideCarriage(passenger, where) * 10) / 10,
          insideCarriage: insideCarriageLocal(passenger, where),
        };
      }

      if (fine) {
        fineTrail.push({
          tick: buildTicks,
          seats: await seatTruth(),
          residents: residents.map((c: any) => ({ u: c.uuid.slice(0, 8), r: c.riding, y: c.y })),
          cur: (await probePlans()).map((p: any) => String(p.current).slice(0, 40)),
        });
        continue;
      }
      if (i % 25 === 0 || built === "minecraft:oak_planks") {
        buildTrail.push({
          tick: buildTicks,
          block: built,
          passengers: (await passengerProbe()).count,
          residents,
          plans: await probePlans(),
          carriage: await carriagePos(),
          seats: await seatTruth(),
          chest: await planksInChest(),
        });
      }
    }
    const betaPos = await carriagePos();
    const planksWhenBuilt = await planksInChest();
    const passengerAtBuild = at(lastSample, passengerUuid);
    const conductorAtBuild = at(lastSample, conductorUuid);
    report.observations.alignment_at_beta = {
      carriagePos: betaPos,
      fracX: frac(betaPos?.x ?? 0),
      fracZ: frac(betaPos?.z ?? 0),
      fracY: Math.abs((betaPos?.y ?? 0) - Math.round(betaPos?.y ?? 0)),
    };
    report.observations.worksite_built = {
      block: built, ticksWaited: buildTicks, residents: await sampleResidents(), trail: buildTrail, fineTrail,
      passengerAlightedAt, passengerAlightedOn, conductorSeatedWhenPassengerAlighted, closestOnFootToChest,
      planksAtDeparture, planksWhenBuilt,
      atBuild: { passenger: passengerAtBuild, conductor: conductorAtBuild, worksite },
    };

    expect(built, `the block should appear at the worksite within ${buildTicks} ticks`).toBe("minecraft:oak_planks");

    expect(
      passengerAlightedAt,
      "the passenger must actually leave the seat (no longer in the carriage's Passengers list). "
        + "if they are stuck in the seat, any 'the house got built' is not their doing - see report.observations.worksite_built.fineTrail",
    ).not.toBeNull();

    expect(
      conductorSeatedWhenPassengerAlighted,
      "when the passenger alights the conductor should still be in the driver seat (driving, not working for the passenger)",
    ).toBe(true);

    expect(
      passengerAlightedOn?.insideCarriage,
      "alighting should set the passenger down on the platform, not leave them standing in the carriage - no one plans the walk out of the carriage anymore "
        + "(`RailConveyance.setDownOnThePlatform`). See report.observations.worksite_built.passengerAlightedOn",
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
      const position = await carriagePos();
      if (Math.hypot(position.x - posBeforeDeparture.x, position.z - posBeforeDeparture.z) < ALPHA_STOP_RADIUS) {
        returnState = await trainSnapshot();
        returned = returnState.speed === 0 && returnState.station != null;
      }
    }
    report.observations.return_to_alpha = { returned, ticks: returnTicks, position: await carriagePos(), state: returnState };
    expect(returned, "the two-way train must actually return to Alpha after the Beta stop; a looping schedule alone does not prove a return").toBe(true);
    expect((await sampleResidents()).find((c: any) => c.uuid === conductorUuid)?.riding,
      "the same conductor should drive the return leg").toBe(true);
    report.result.status = "commuted";
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    writeFileSync(path.join(REPO_ROOT, REPORT_PATH), JSON.stringify(report, null, 2));
    writeFileSync(testInfo.outputPath("rail-train-report.json"), JSON.stringify(report, null, 2));
    await pair?.teardown(testInfo);
  }
});

function conductorSeatEntry(nbt: any): { pos?: Vec; forward?: boolean; backward?: boolean } | null {
  const stack: any[] = [nbt];
  while (stack.length > 0) {
    const node = stack.pop();
    if (node == null || typeof node !== "object") continue;
    if (Array.isArray(node)) {
      if (node.length === 3 && node.every((n) => typeof n === "number")) {
        return { pos: { x: node[0], y: node[1], z: node[2] } };
      }
      stack.push(...node);
      continue;
    }
    const pos = Array.isArray(node.Pos) && node.Pos.length === 3
      ? { x: node.Pos[0], y: node.Pos[1], z: node.Pos[2] }
      : undefined;
    const forward = node.Forward === 1 || node.Forward === true;
    if (pos || "Forward" in node) {
      return { pos, forward, backward: node.Backward === 1 || node.Backward === true };
    }
    stack.push(...Object.values(node));
  }
  return null;
}
