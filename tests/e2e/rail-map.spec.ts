import { expect, test } from "@playwright/test";
import {
  errorSummary,
  errorToString,
} from "@izakyl/blockwright-client";
import { defineTask, input, javaTask, reflect, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { writeFileSync } from "node:fs";
import path from "node:path";
import {
  foundColonyFast,
  tryCommand,
  safeAction,
  safeScreen,
  screenName,
  screenOpen,
  waitForGroundedPlayer,
  type Vec,
} from "./colony-founding";
import { buildRailLine, conductorAboard, seatedAt } from "./rail-fixture";
import { launchPair, type E2EPair } from "../shared/pair";
import { REPO_ROOT, ensureOut, reportPath, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { newRunId } from "../shared/run-id";
import { frames, softly } from "../shared/bw-helpers";
import { runColonyTask } from "./colony-tasks";

const CONFIG_PATH = "blockwright.toml";
const REPORT_PATH = reportPath("e2e", "rail-map-report");

const RAIL_LINE = 24;
const RESIDENT_COUNT = 1;

const KEY_M = 77;

const RAIL_TOOLTIP = "io.github.izakyl.folkways.plugins.rail.domain.RailTooltip";
const TRAIN_MAP_MANAGER = "com.simibubi.create.compat.trainmap.TrainMapManager";
const TRAIN_CLASS = "com.simibubi.create.content.trains.entity.Train";
const TRAIN_RELOCATOR = "com.simibubi.create.content.trains.entity.TrainRelocator";
const CARRIAGE_ENTITY_CLASS = "com.simibubi.create.content.trains.entity.CarriageContraptionEntity";
const MINECRAFT_CLASS = "net.minecraft.client.Minecraft";
const ENTITY_CLASS = "net.minecraft.world.entity.Entity";

test("the train map says who drives a colony's train and how many of its seats are taken", async ({}, testInfo) => {
  test.setTimeout(BUDGET.slow);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const report: any = {
    scope: "folkways' two lines on Create's train map, read out of Create's own tooltip",
    traces: { server: tracePath("e2e", "rail-map-server"), client: tracePath("e2e", "rail-map-client") },
    setup: {},
    observations: {},
  };

  let server: any;
  let client: any;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: report.traces.server, instance: seededServer(`rail-map-server-${runId}`) },
      client: { trace: report.traces.client, instance: `rail-map-client-${runId}` },
    });
    ({ server, client } = pair);

    const player = await waitForGroundedPlayer(server);
    await screen.dismiss(client);
    await tryCommand(server, `op ${player.name}`);
    await tryCommand(server, `gamemode creative ${player.name}`);
    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");
    const playerUuid = (await world.players(server)).find((p: any) => p.name === player.name)?.uuid;
    expect(playerUuid, "could not read the player uuid, so assembly has no one to sign it").toBeTruthy();

    const origin: Vec = {
      x: Math.round(player.pos.x) + 4,
      y: Math.round(player.pos.y),
      z: Math.round(player.pos.z) + 4,
    };
    const founding = await foundColonyFast(server, client, player.name, {
      origin,
      residentCount: RESIDENT_COUNT,
    });
    report.setup.founding = { origin, residents: founding.residents };
    expect(founding.residents, "residents should have settled, or no one can drive").toBeGreaterThanOrEqual(1);
    await tryCommand(server, "time set day");

    const backup = await tryCommand(server, `item replace entity ${player.name} inventory.0 from entity ${player.name} weapon.mainhand`);
    expect(backup?.success, "the bound lord's book should back up into inventory.0").toBe(true);

    const freezeResidents = async (frozen: boolean) => await tryCommand(
      server,
      `execute as @e[type=folkways:resident] run data merge entity @s {NoAI:${frozen ? 1 : 0}b}`,
    );
    // NoAI only stops walking: a resident already standing by the cab still takes the seat by labor, so park them off the line first.
    const railOrigin: Vec = { x: origin.x - 8, y: origin.y, z: origin.z + 4 };
    await tryCommand(server, `tp @e[type=folkways:resident] ${origin.x + 8} ${origin.y} ${origin.z}`);
    expect((await freezeResidents(true))?.success, "residents should freeze, or the 'no driver' moment cannot be caught").toBe(true);
    const rail = await buildRailLine(server, client, player.name, playerUuid, railOrigin, RAIL_LINE, "inventory.0");
    report.setup.rail = {
      ok: rail.ok, assembled: rail.assembled, scheduled: rail.scheduled, handedOver: rail.handedOver,
      carriage: rail.carriageUuid, steps: rail.steps,
    };
    expect(rail.assembled, `the train should assemble: ${JSON.stringify(rail.steps).slice(0, 400)}`).toBe(true);
    expect(rail.handedOver, "the train should belong to this colony, or its crew belongs to no colony").toBe(true);
    const carriageUuid = rail.carriageUuid!;

    await tryCommand(server, `item replace entity ${player.name} weapon.mainhand from entity ${player.name} inventory.0`);
    await tick.sprint(server, 20);

    const trainId = await readTrainId(server, carriageUuid);
    report.setup.train = trainId;
    expect(trainId, "could not read the train id, so every later query would miss").toBeTruthy();

    await input.key(client, { keyCode: KEY_M, action: "tap" });
    await softly(() => screen.waitFor(client, "GuiMap", { timeoutMs: 10_000 }));
    const mapScreen = screenName(await safeScreen(client));
    report.observations.map_screen = mapScreen;
    expect(mapScreen, "M should open the Xaero world map, which Create draws its track map on").toMatch(/GuiMap/);
    await tick.sprint(server, 60);
    await frames(client, 2);

    const beforeBoarding = await pollFolkwaysLines(client, trainId!, (text) => text.includes("train_map"));
    report.observations.before_boarding = beforeBoarding;
    const seatEmpty = !(await conductorAboard(server, carriageUuid));
    report.observations.seat_empty_before_reading = seatEmpty;
    expect(seatEmpty, "the driver seat must really be empty when this line is read, or the read proves nothing").toBe(true);
    expect(JSON.stringify(beforeBoarding.returned), "with no driver it should read 'driver: vacant'")
      .toContain("folkways.train_map.driver_missing");

    expect((await freezeResidents(false))?.success, "residents should unfreeze, or no one will take the driver seat").toBe(true);
    const boarded = await sprintUntilAboard(server, carriageUuid, 6_000);
    report.observations.boarding = boarded;
    expect(boarded.aboard, `a resident should take the driver seat (advanced ${boarded.ticks} ticks)`).toBe(true);

    const resident = await seatedResident(server, carriageUuid);
    report.observations.resident = resident;
    expect(resident?.name, "the seated resident should have a name, which the driver line reports").toBeTruthy();
    await tick.sprint(server, 60);
    await frames(client, 2);

    const lines = await pollFolkwaysLines(client, trainId!, (text) => text.includes(resident!.name));
    report.observations.folkways_lines = lines;
    const linesText = JSON.stringify(lines.returned);
    expect(lines.resolved, "the static call should resolve RailTooltip").toBe(true);
    expect(linesText, "the driver line should report the resident's name").toContain(resident!.name);
    expect(linesText, "the riders line should be present").toContain("folkways.train_map.riders");

    const carriageEntityId = await readCarriageEntityId(server, carriageUuid);
    report.observations.carriage_entity_id = carriageEntityId;
    expect(carriageEntityId, "could not read the carriage network id, so the client cannot find this train").toBeGreaterThan(0);
    const tooltip = await readCreateTooltip(client, carriageEntityId!);
    report.observations.create_tooltip = tooltip;
    const tooltipText = JSON.stringify(tooltip.returned);
    expect(tooltip.resolved, `Create's listTrainDetails should be callable: ${JSON.stringify(tooltip.raw).slice(0, 400)}`).toBe(true);
    expect(tooltipText, "the tooltip the map draws should have the driver line").toContain("folkways.train_map.driver");
    expect(tooltipText, "the tooltip the map draws should have the resident's name").toContain(resident!.name);
    expect(tooltipText, "the tooltip the map draws should have the riders line").toContain("folkways.train_map.riders");
  } catch (error) {
    report.error = errorToString(error);
    report.error_summary = errorSummary(error);
    pair?.failing(error);
    throw error;
  } finally {
    writeFileSync(path.join(REPO_ROOT, REPORT_PATH), JSON.stringify(report, null, 2) + "\n");
    await pair?.teardown(testInfo);
  }
});

async function readTrainId(server: MinecraftServer, carriageUuid: string): Promise<string | undefined> {
  const held = await reflect
    .invoke(server, {
      target: { kind: "entity", uuid: carriageUuid },
      path: "getCarriage().train.id",
      method: "toString",
      returnType: "java.lang.String",
    })
    .catch(() => null);
  const value = held?.returned;
  return typeof value === "string" ? value : undefined;
}

async function readCarriageEntityId(server: MinecraftServer, carriageUuid: string): Promise<number | undefined> {
  const held = await reflect
    .invoke(server, { target: { kind: "entity", uuid: carriageUuid }, method: "getId", returnType: "int" })
    .catch(() => null);
  return held ? reflect.number(held) : undefined;
}

async function pollFolkwaysLines(
  client: MinecraftClient, trainId: string, arrived: (text: string) => boolean, timeoutMs = 15_000,
) {
  const deadline = Date.now() + timeoutMs;
  let last = await readFolkwaysLines(client, trainId);
  while (Date.now() < deadline && !arrived(JSON.stringify(last.returned ?? ""))) {
    await frames(client, 10);
    last = await readFolkwaysLines(client, trainId);
  }
  return last;
}

async function readFolkwaysLines(client: MinecraftClient, trainId: string) {
  const held: any = await reflect
    .invoke(client, {
      target: { kind: "static", className: RAIL_TOOLTIP },
      method: "lines",
      args: [trainId],
      argTypes: ["java.util.UUID"],
      returnType: "java.util.List",
      limits: { maxDepth: 8, maxItems: 40, maxNodes: 4000 },
    })
    .catch((error: any) => ({ error: String(error) }));
  return { resolved: held?.found === true, returned: held?.returned ?? null, raw: held };
}

async function readCreateTooltip(client: MinecraftClient, carriageEntityId: number) {
  const mc: any = await reflect
    .invoke(client, {
      target: { kind: "static", className: MINECRAFT_CLASS },
      method: "getInstance",
      returnType: MINECRAFT_CLASS,
      returnHandle: true,
      limits: { maxDepth: 1 },
    })
    .catch((error: any) => ({ error: String(error) }));
  if (!mc?.handle) {
    return { resolved: false, returned: null, raw: { mc } };
  }
  const carriage: any = await reflect
    .invoke(client, {
      target: { kind: "handle", handle: mc.handle },
      path: "level",
      method: "getEntity",
      args: [carriageEntityId],
      argTypes: ["int"],
      returnType: ENTITY_CLASS,
      returnHandle: true,
      limits: { maxDepth: 1 },
    })
    .catch((error: any) => ({ error: String(error) }));
  if (!carriage?.handle) {
    return { resolved: false, returned: null, raw: { mc, carriage } };
  }
  const train: any = await reflect
    .invoke(client, {
      target: { kind: "static", className: TRAIN_RELOCATOR },
      method: "getTrainFromEntity",
      args: [{ $handle: carriage.handle }],
      argTypes: [CARRIAGE_ENTITY_CLASS],
      returnHandle: true,
      limits: { maxDepth: 1 },
    })
    .catch((error: any) => ({ error: String(error) }));
  if (!train?.handle) {
    return { resolved: false, returned: null, raw: { mc, carriage, train } };
  }
  const tooltip: any = await reflect
    .invoke(client, {
      target: { kind: "static", className: TRAIN_MAP_MANAGER },
      method: "listTrainDetails",
      args: [{ $handle: train.handle }],
      argTypes: [TRAIN_CLASS],
      limits: { maxDepth: 8, maxItems: 40, maxNodes: 6000 },
    })
    .catch((error: any) => ({ error: String(error) }));
  return { resolved: tooltip?.found === true, returned: tooltip?.returned ?? null,
    raw: { mc, carriage, train, tooltip } };
}

async function sprintUntilAboard(server: MinecraftServer, carriageUuid: string, maxTicks: number) {
  let ticks = 0;
  while (ticks < maxTicks) {
    if (await conductorAboard(server, carriageUuid)) {
      return { aboard: true, ticks };
    }
    await tick.sprint(server, 100);
    ticks += 100;
  }
  return { aboard: await conductorAboard(server, carriageUuid), ticks };
}

const RESIDENT_NAME = defineTask<{ uuid: string }, Record<string, any>>({
  name: "folkways.rail.resident-name",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    body: `
UUID uuid = UUID.fromString(Args.of(ctx).string("uuid"));
for (net.minecraft.server.level.ServerLevel level : ctx.server().getAllLevels()) {
    net.minecraft.world.entity.Entity entity = level.getEntity(uuid);
    if (entity != null) {
        return Map.of("ok", Boolean.TRUE, "name", entity.getName().getString());
    }
}
throw Fail.notFound("no entity " + uuid);
`,
  }),
});

async function seatedResident(server: MinecraftServer, carriageUuid: string) {
  const residents = (await world
    .entities(server, { typePatterns: ["*resident*"], limit: 100 })
    .catch(() => ({ entities: [] })))?.entities ?? [];
  for (const resident of residents) {
    const seat = await seatedAt(server, carriageUuid, resident.uuid);
    if (seat === undefined) {
      continue;
    }
    // getString is a default method on Component's interfaces, which reflect.invoke does not look up.
    const named = await runColonyTask(server, RESIDENT_NAME, { uuid: resident.uuid })
      .catch((error: any) => ({ error: String(error) }));
    const name = typeof (named as any)?.name === "string" ? (named as any).name : undefined;
    return { uuid: resident.uuid, seat, name, raw: JSON.stringify(named).slice(0, 200) };
  }
  return undefined;
}
