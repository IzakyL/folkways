import { expect, test } from "@playwright/test";
import {
  errorSummary,
  errorToString,
} from "@izakyl/blockwright-client";
import {
  input,
  media,
  screen,
  tick,
  world,
  type BlockAtJson,
} from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { clickElement, panelElements } from "../ldlib2";
import { existsSync, readFileSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { optInContainerViaUI } from "./membership-optin";
import { stockToolsForResidents, foundColonyFast, openColonyPanelViaBook, TRADE_TOOLS,
  waitForGroundedPlayer, entityTypeMatches,
} from "./colony-founding";
import { blockHasItem, countInResidentPacks, packHasItem, summarizeResidentMovement } from "./world-ui";
import { drainActionBar } from "./zone-receipt";
import {
  colonyRegistry,
  configureZone,
  createMarkedZone,
  setBookGesture,
  zoneCellsAt,
  zoneChoiceSettingAt,
  zoneKindAt,
  ZONE_KIND_ID,
} from "./zone-tools";
import { launchPair, type E2EPair } from "../shared/pair";
import { REPO_ROOT, ensureOut, shotPath, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { commandPos, isSafeFailure, safeAction, tryCommand, waitForElement, softly, waitMatched } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";

const RESIDENT_TYPE = "folkways:resident";
const RESIDENT_COUNT = 6;
const COLONY_SCREENSHOT_RAW = shotPath("e2e", "farm-colony-overview-raw");
const COLONY_SCREENSHOT_ANNOTATED = shotPath("e2e", "farm-colony-overview");
const PANEL_FIDELITY_SHOT = shotPath("e2e", "farm-panel-fidelity");
const CROP_PICKER_SHOT = shotPath("e2e", "farm-crop-picker");
const FARM_ZONE_CROP_ID = "minecraft:wheat_seeds";
const FARM_CROP_BLOCK = "minecraft:wheat";
const FARM_SEED_ITEM = "minecraft:wheat_seeds";

const FORESTRY_CROP_ID = "minecraft:oak_sapling";
const FORESTRY_LOG_BLOCK = "minecraft:oak_log";
const TREE_BODY_RADIUS = 2;
const TREE_BODY_HEIGHT = 9;
const MIN_SAPLING_SPACING = TREE_BODY_RADIUS + 1;
const WOODLOT_SIZE = 7;
const UNREACHABLE_FROM_FOOT = 6.5;

async function taggedCommand(server: any, command: string, required: boolean) {
  return { ...(await tryCommand(server, command)), required };
}

test("farm end-to-end: till → crop → harvest via panel", async ({}, testInfo) => {
  test.setTimeout(BUDGET.slow);
  ensureOut("e2e");
  const runId = folkwaysRunId();

  const serverTrace = tracePath("e2e", "farm-server");
  const clientTrace = tracePath("e2e", "farm-client");
  const serverInstance = `farm-server-${runId}`;
  const clientInstance = `farm-client-${runId}`;

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  let colonyEvidence: any = {};
  const snapshotContainers: Array<{ x: number; y: number; z: number }> = [];

  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: serverTrace, instance: seededServer(serverInstance) },
      client: { trace: clientTrace, instance: clientInstance },
      snapshot: () => ({ containers: snapshotContainers, entityTypes: [RESIDENT_TYPE, "minecraft:item"] }),
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await taggedCommand(server, `gamemode survival ${playerName}`, false);
    const origin = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    const founding = await foundColonyFast(server, client, playerName, {
      origin,
      residentCount: RESIDENT_COUNT,
    });
    colonyEvidence = await deriveColonyEvidence(server, founding, playerName);

    expect(
      colonyEvidence.pass,
      [
        `residents=${colonyEvidence.resident_count ?? "unknown"} (expected ${RESIDENT_COUNT})`,
        `bed_foot_blocks=${colonyEvidence.block_counts?.bed_foot_blocks ?? "unknown"}`,
        `member_food_chest=${colonyEvidence.block_counts?.member_food_chest ?? "unknown"}`,
      ].join("; "),
    ).toBe(true);
    await captureColonyScreenshot(client, colonyEvidence);

    const farm = await observeFarmGameplay(server, client, playerName, colonyEvidence);
    expect(farm.status, (farm.reasons ?? []).join("; ")).toBe("PASS");

    const forestry = await observeForestryGameplay(server, client, playerName, colonyEvidence, snapshotContainers);
    expect(forestry.status, forestryFailureMessage(forestry)).toBe("PASS");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

function folkwaysRunId() {
  return newRunId();
}

async function deriveColonyEvidence(server: any, founding: any, playerName: string) {
  const origin = founding.origin;
  const entitiesResult = await world.entities(server, { dimension: "minecraft:overworld", limit: 1000 });
  const residents = entitiesResult.entities
    .filter((entity: any) => entityTypeMatches(entity.type, RESIDENT_TYPE))
    .map((entity: any) => ({ id: entity.id, uuid: entity.uuid, type: entity.type, position: entity.position }));
  const centroid = { x: origin.x, y: origin.y, z: origin.z };
  const blockHits = [
    ...founding.bedFeet.map((foot: any) => ({ position: foot, id: "minecraft:red_bed", state: "minecraft:red_bed[part=foot]" })),
    { position: founding.foodChest, id: "minecraft:chest", state: "" },
  ];
  const foodChestMember = await isChestColonyMember(server, founding.foodChest);
  const pass = residents.length >= RESIDENT_COUNT && founding.bedFeet.length >= RESIDENT_COUNT && foodChestMember;
  return {
    pass,
    player: playerName,
    resident_type: RESIDENT_TYPE,
    resident_count: residents.length,
    residents,
    origin,
    centroid,
    bed_feet: founding.bedFeet,
    food_chest: founding.foodChest,
    stand_block: founding.standBlock,
    founding_evidence: founding.evidence,
    block_counts: { bed_foot_blocks: founding.bedFeet.length, member_food_chest: foodChestMember ? 1 : 0 },
    block_hits: blockHits,
  };
}

async function isChestColonyMember(server: any, chest: any) {
  return world.probe(server, `if data block ${commandPos(chest)} "neoforge:attachments"."folkways:colony_member"`);
}

async function captureColonyScreenshot(client: any, colonyEvidence: any) {
  const camera = {
    dimension: "minecraft:overworld",
    position: { x: colonyEvidence.centroid.x, y: colonyEvidence.centroid.y + 24, z: colonyEvidence.centroid.z + 0.1 },
    yaw: 0,
    pitch: 90,
    fov: 70,
  };
  const result = await safeScreenshot(client, {
    path: COLONY_SCREENSHOT_RAW,
    camera,
    context: { phase: "runtime", worldReady: true, stage: "founding" },
    requireCapture: false,
  });
  const annotation = annotateColonyScreenshot(COLONY_SCREENSHOT_RAW, COLONY_SCREENSHOT_ANNOTATED, camera, colonyEvidence.block_hits ?? []);
  const preferredPath = annotation.exists ? COLONY_SCREENSHOT_ANNOTATED : COLONY_SCREENSHOT_RAW;
  return {
    ...result,
    path: preferredPath,
    exists: existsSync(path.join(REPO_ROOT, preferredPath)),
    bytes: fileSize(path.join(REPO_ROOT, preferredPath)),
    annotation,
  };
}

async function observeFarmGameplay(server: any, client: any, playerName: string, colonyEvidence: any) {
  const blocked = blockedAutonomyReason(colonyEvidence);
  if (blocked.length > 0) {
    return { status: "BLOCKED", reasons: blocked, evidence: { blocked } };
  }

  let panelFidelity: any = null;
  let farmResult: any;
  try {
    panelFidelity = await checkFarmPanelFidelity(server, client, playerName, colonyEvidence.stand_block);
    const setup = await setupAutonomousFarmScenario(server, client, playerName, colonyEvidence);
    const observation = await sampleAutonomousFarmScenario(server, setup);
    const analysis = analyzeAutonomousFarmSamples(observation.samples, observation.maturity_forced_order);
    const setupFailures = setup.command_results.filter((result: any) => result.required && !commandSucceeded(result));
    const requiredSetupOk = setupFailures.length === 0 && setup.farm_zone_armed;
    farmResult = { requiredSetupOk, panelFidelity, setupFailures, setup, observation, analysis };
  } catch (error) {
    return {
      status: "FAIL",
      reasons: [`farm setup/observation threw: ${errorSummary(error)}`],
      evidence: { error: errorToString(error), panelFidelity },
    };
  }

  const progress = farmResult.analysis.world_progress;
  const reasons: string[] = [];
  if (!panelFidelity.pass) {
    reasons.push(`Citizens-tab panel fidelity check failed (rows/vocation-detail did not render): ${(panelFidelity.reasons ?? ["unknown"]).join("; ")}`);
  }
  if (!farmResult.requiredSetupOk) {
    reasons.push(`required setup step(s) failed: ${farmResult.setupFailures.map((r: any) => r.command).join("; ") || "farm zone not armed/created"}`);
  }
  const supplyChestMember = Boolean(farmResult.setup?.supply_chest_member);
  const farmingEnabled = await countFarmingEnabledResidents(server, colonyEvidence);
  if (!supplyChestMember) {
    reasons.push("the supply chest is still not a colony member after opt-in: its contents are not in colony stock, so sowing cannot get seeds");
  }
  const mats = farmResult.setup?.supply_materials ?? {};
  if (!(mats.oak_planks && mats.stick)) {
    reasons.push(`the supply chest is missing items (planks=${mats.oak_planks}, sticks=${mats.stick}, seeds=${mats.wheat_seeds}): the fixture was not set up`);
  }
  if (farmingEnabled.enabled === 0) {
    reasons.push(`no resident in the live colony has FARMING enabled (scanned ${farmingEnabled.scanned}): a disabled trade never reaches the planner (see the warning in ColonyLabor), so nobody can be dispatched to work this plot`);
  }
  if (!progress.till_observed) reasons.push("TILL not observed: farm ground never became minecraft:farmland");
  if (!progress.crop_appeared) reasons.push(`${FARM_CROP_BLOCK} crop never appeared after farmland was created`);
  if (!progress.harvest_observed) reasons.push("HARVEST not observed: mature crop was not removed with harvest evidence");

  return {
    status: reasons.length === 0 ? "PASS" : "FAIL",
    reasons,
    evidence: {
      scenario:
        "first a fidelity check of the Citizens tab panel, then a single-block folkways:farm with crop wheat marked through the real UI only; the member supply chest holds only planks, sticks and wheat seeds, and the hoe is dropped on the ground for residents to pick up; no resident packs are written and folkways internals are untouched",
      panel_fidelity: panelFidelity,
      supply_chest_member: supplyChestMember,
      supply_materials: mats,
      farming_enabled: farmingEnabled,
      farm: farmResult.setup?.farm,
      farm_zone_arm: farmResult.setup?.farm_zone_arm,
      setup_commands: farmResult.setup?.command_results,
      maturity_command: farmResult.observation?.maturity_command,
      world_progress: progress,
      transitions: farmResult.analysis.transitions,
    },
  };
}

async function countFarmingEnabledResidents(server: any, colonyEvidence: any) {
  const uuids: string[] = (colonyEvidence.residents ?? []).map((c: any) => c.uuid).filter(Boolean);
  const per: any[] = [];
  let enabled = 0;
  for (const uuid of uuids) {
    const disabled = await entityDataHolds(server, uuid,
      `{"neoforge:attachments":{"folkways:resident_capabilities":{withheld:["folkways:farming"]}}}`);
    if (!disabled) enabled++;
    per.push({ uuid, farming_disabled: disabled });
  }
  return { scanned: uuids.length, enabled, per };
}

async function entityDataHolds(server: any, uuid: string, nbtPredicate: string) {
  return world.probe(server, `if data entity ${uuid} ${nbtPredicate}`);
}

function blockedAutonomyReason(colonyEvidence: any) {
  const reasons: string[] = [];
  if (!colonyEvidence?.origin) reasons.push("cannot seed farm work because founding produced no colony origin");
  if (!(colonyEvidence?.resident_count >= 1)) reasons.push("cannot seed farm work because founding produced no residents");
  if (!colonyEvidence?.stand_block) reasons.push("cannot open the colony panel because founding produced no stand block");
  return reasons;
}

async function checkFarmPanelFidelity(server: any, client: any, playerName: string, standBlock: any) {
  const reasons: string[] = [];
  const steps: any = {};
  let screenshot: any;

  try {
    steps.open_panel = await openColonyPanelViaBook(server, client, playerName, standBlock);
    steps.click_citizens_tab = await softly(() => clickElement(client, { id: "folkways.tab.residents" }));
    await waitForElement(client, { idPattern: "folkways.resident.row.*" }, { timeoutMs: 10_000 });
    await tick.sprint(server, 5);

    const rowIds = (await panelElements(client))
      .map((element) => element.id)
      .filter((id): id is string => typeof id === "string" && id.startsWith("folkways.resident.row."));
    steps.resident_rows = rowIds;
    if (rowIds.length === 0) {
      reasons.push("no folkways.resident.row.* widget appeared (the roster sync value never reached the client, so the Citizens tab stayed a pool of unnamed rows)");
    } else {
      steps.click_first_row = await softly(() => clickElement(client, { id: rowIds[0].replace("resident.row.", "resident.name.") }));
      await waitForElement(client, { idPattern: "folkways.resident.vocation.*" }, { timeoutMs: 8_000 });
      await tick.sprint(server, 3);
      screenshot = await captureCurrentViewScreenshot(client, PANEL_FIDELITY_SHOT, {
        phase: "runtime",
        worldReady: true,
        step: "after_panel_open",
      });

      const checks: Array<readonly [string, any]> = [
        ["Citizens tab click", steps.click_citizens_tab],
        ["First row click", steps.click_first_row],
      ];
      for (const [label, result] of checks) {
        if (!actionOk(result)) {
          reasons.push(`${label} did not complete through click(widget): ${actionReason(result)}`);
        }
      }
    }
  } catch (error) {
    reasons.push(`Citizens-tab permission setup threw: ${errorSummary(error)}`);
  } finally {
    await screen.dismiss(client);
  }

  return {
    status: reasons.length === 0 ? "PASS" : "FAIL",
    pass: reasons.length === 0,
    reasons,
    steps,
    screenshot,
  };
}

async function setupAutonomousFarmScenario(server: any, client: any, playerName: string, colonyEvidence: any) {
  const origin = colonyEvidence.origin;
  const chest = { x: origin.x, y: origin.y, z: origin.z + 8 };
  const craftingTable = { x: origin.x + 2, y: origin.y, z: origin.z + 8 };
  const farmGround = { x: origin.x + 4, y: origin.y - 1, z: origin.z + 10 };
  const farm = {
    crop_id: FARM_ZONE_CROP_ID,
    expected_crop_block: FARM_CROP_BLOCK,
    seed_item: FARM_SEED_ITEM,
    ground: farmGround,
    planting: { x: farmGround.x, y: farmGround.y + 1, z: farmGround.z },
  };
  const injectedStorageItems = [
    { slot: "container.0", item: "minecraft:oak_planks", count: 2, purpose: "wooden hoe head material" },
    { slot: "container.1", item: "minecraft:stick", count: 2, purpose: "wooden hoe handle material" },
    { slot: "container.2", item: FARM_SEED_ITEM, count: 4, purpose: "farm planting seed source" },
  ];
  const commandSpecs = [
    { command: `setblock ${commandPos(farm.ground)} minecraft:dirt`, required: true },
    { command: `setblock ${commandPos(farm.planting)} minecraft:air`, required: false },
    { command: `setblock ${commandPos({ x: farm.planting.x, y: farm.planting.y + 1, z: farm.planting.z })} minecraft:air`, required: false },
    { command: `setblock ${commandPos(craftingTable)} minecraft:crafting_table`, required: true },
    { command: `setblock ${commandPos(chest)} minecraft:chest{Items:[]}`, required: true },
    ...injectedStorageItems.map((it) => ({
      command: `item replace block ${commandPos(chest)} ${it.slot} with ${it.item} ${it.count}`,
      required: true,
    })),
  ];
  const commandResults = [];
  for (const spec of commandSpecs) {
    commandResults.push(await taggedCommand(server, spec.command, spec.required));
  }
  await tick.sprint(server, 3);

  const optIn = await optInContainerViaUI(server, client, playerName, chest);
  const supplyChestMember = await isChestColonyMember(server, chest);
  const supplyMaterials = {
    oak_planks: await blockHasItem(server, chest, "minecraft:oak_planks"),
    stick: await blockHasItem(server, chest, "minecraft:stick"),
    wheat_seeds: await blockHasItem(server, chest, FARM_SEED_ITEM),
  };
  const farmZoneArm = await armFarmZoneViaPanel(server, client, playerName, colonyEvidence.stand_block, farm.ground);
  const hoes = await stockToolsForResidents(server, chest, TRADE_TOOLS.farming, RESIDENT_COUNT, 3);

  return {
    hoes,
    origin,
    chest,
    crafting_table: craftingTable,
    farm,
    injected_storage_items: injectedStorageItems,
    opt_in: optIn,
    supply_chest_member: supplyChestMember,
    supply_materials: supplyMaterials,
    farm_zone_arm: farmZoneArm,
    farm_zone_armed: farmZoneArm.armed,
    command_results: commandResults,
  };
}

async function armFarmZoneViaPanel(server: any, client: any, playerName: string, standBlock: any, farmGround: any) {
  const evidence: any = { farmGround };
  try {
    evidence.crop = FARM_ZONE_CROP_ID;
    evidence.zone_mode = await setBookGesture(server, client, playerName, "box");

    evidence.corner_1 = await aimAndWorldLeftClick(server, client, playerName, farmGround);
    await tick.sprint(server, 3);
    evidence.corner_2 = await aimAndWorldLeftClick(server, client, playerName, farmGround);
    await tick.sprint(server, 5);
    evidence.configure = await createMarkedZone(server, client, playerName, standBlock, "farm");
    await tick.sprint(server, 5);

    const registry = await colonyRegistry(server);
    evidence.zone_kind = await zoneKindAt(server, registry, 0);
    evidence.zone_crop = await zoneChoiceSettingAt(server, registry, "crop", 0);
    evidence.zone_cells = await zoneCellsAt(server, registry, 0);
    evidence.armed = evidence.zone_kind === ZONE_KIND_ID.farm
      && evidence.zone_crop === FARM_CROP_BLOCK
      && evidence.zone_cells === 1;
  } catch (error) {
    evidence.armed = false;
    evidence.error = errorSummary(error);
  } finally {
    await screen.dismiss(client);
  }
  return evidence;
}

async function aimAndWorldLeftClick(server: any, client: any, playerName: string, block: any) {
  const eyeTarget = { x: block.x + 0.5, y: block.y + 1, z: block.z + 0.5 };
  const view = { x: block.x + 0.5, y: block.y + 1, z: block.z + 1.5 };
  const camera = cameraLookingAt({ x: view.x, y: view.y + 1.62, z: view.z }, eyeTarget);
  const teleport = await taggedCommand(server, `tp ${playerName} ${view.x} ${view.y} ${view.z} ${camera.yaw} ${camera.pitch}`, false);
  await tick.sprint(server, 3);
  const press = await safeAction(() => input.button(client, { button: "left", action: "press" }));
  const release = await safeAction(() => input.button(client, { button: "left", action: "release" }));
  return { block, view, camera, teleport, click: press, release };
}

async function sampleAutonomousFarmScenario(server: any, setup: any) {
  const samples: any[] = [];
  let order = 0;
  let maturityCommand: any = null;
  let maturityForcedOrder: number | undefined;
  samples.push(await farmSnapshot(server, setup, order++, "after_setup"));

  for (let iteration = 1; iteration <= 60; iteration++) {
    await tick.sprint(server, 20);
    const sample = await farmSnapshot(server, setup, order++, `after_${iteration * 20}_ticks`);
    samples.push(sample);

    if (!maturityCommand && isCropBlock(sample.crop_block)) {
      maturityCommand = await taggedCommand(server, `setblock ${commandPos(setup.farm.planting)} ${FARM_CROP_BLOCK}[age=7]`, true);
      const matureSample = await farmSnapshot(server, setup, order++, "after_maturity_command");
      samples.push(matureSample);
      maturityForcedOrder = matureSample.order;

      for (let fine = 0; fine < 30; fine++) {
        await tick.sprint(server, 2);
        samples.push(await farmSnapshot(server, setup, order++, `after_maturity_fine_${(fine + 1) * 2}`));
      }
    }

    const analysis = analyzeAutonomousFarmSamples(samples, maturityForcedOrder);
    if (analysis.world_progress.till_observed && analysis.world_progress.crop_appeared && analysis.world_progress.harvest_observed) {
      break;
    }
  }

  return { sample_period_ticks: 20, max_sample_iterations: 60, maturity_command: maturityCommand, maturity_forced_order: maturityForcedOrder, samples };
}

async function farmSnapshot(server: any, setup: any, order: number, label: string) {
  const [entitiesResult, groundResult, cropResult] = await Promise.all([
    world.entities(server, { dimension: "minecraft:overworld", limit: 1000 }),
    world.block(server, setup.farm.ground, { dimension: "minecraft:overworld" }),
    world.block(server, setup.farm.planting, { dimension: "minecraft:overworld" }),
  ]);
  const residents = entitiesResult.entities
    .filter((entity: any) => entityTypeMatches(entity.type, RESIDENT_TYPE))
    .map((entity: any) => ({ id: entity.id, uuid: entity.uuid, position: entity.position }));
  return { order, label, farm_ground_block: blockInfo(groundResult), crop_block: blockInfo(cropResult), residents };
}

function analyzeAutonomousFarmSamples(samples: any[], maturityForcedOrder: number | undefined) {
  const movement = summarizeResidentMovement(samples);
  const firstTill = samples.find((sample) => isFarmlandBlock(sample.farm_ground_block));
  const firstCrop = samples.find((sample) => isCropBlock(sample.crop_block));
  const firstMature =
    maturityForcedOrder === undefined
      ? samples.find((sample) => isMatureCropBlock(sample.crop_block))
      : samples.find((sample) => sample.order >= maturityForcedOrder && isMatureCropBlock(sample.crop_block));
  const matureOrder = maturityForcedOrder ?? firstMature?.order;
  const cropRemovedAfterMature =
    matureOrder !== undefined && samples.some((sample) => sample.order > matureOrder && !isCropBlock(sample.crop_block));
  const matureCropConsumed =
    matureOrder !== undefined && samples.some((sample) => sample.order > matureOrder && !isMatureCropBlock(sample.crop_block));
  const harvestObserved = cropRemovedAfterMature || matureCropConsumed;

  return {
    movement,
    world_progress: {
      any: Boolean(firstTill || firstCrop || harvestObserved),
      till_observed: Boolean(firstTill),
      crop_appeared: Boolean(firstCrop),
      crop_mature_observed: Boolean(firstMature),
      maturity_forced_by_test_command: maturityForcedOrder !== undefined,
      crop_removed_after_maturity: cropRemovedAfterMature,
      mature_crop_consumed_after_maturity: matureCropConsumed,
      harvest_observed: harvestObserved,
      first_till_order: firstTill?.order ?? null,
      first_crop_order: firstCrop?.order ?? null,
      first_mature_order: firstMature?.order ?? null,
    },
    transitions: {
      ground_blocks: uniqueBlockStates(samples.map((sample) => sample.farm_ground_block)),
      crop_blocks: uniqueBlockStates(samples.map((sample) => sample.crop_block)),
    },
  };
}

async function captureCurrentViewScreenshot(client: any, frameRel: string, context: any) {
  try {
    const result = shotSummary(await media.screenshot(client, { path: path.join(REPO_ROOT, frameRel) }));
    return { ...result, context, path: frameRel, exists: existsSync(path.join(REPO_ROOT, frameRel)), bytes: fileSize(path.join(REPO_ROOT, frameRel)) };
  } catch (error) {
    return {
      status: "unsupported",
      path: frameRel,
      exists: existsSync(path.join(REPO_ROOT, frameRel)),
      bytes: fileSize(path.join(REPO_ROOT, frameRel)),
      reason: `current-view screenshot is not available in this @izakyl/blockwright-client build: ${errorSummary(error)}`,
    };
  }
}
function commandSucceeded(result: any) {
  return result?.status === "ok" && result.success === true;
}

/** A softly()-wrapped action went through: it answered its result instead of a SafeFailure. */
function actionOk(result: any) {
  return result != null && !isSafeFailure(result);
}

/** A world.block answer as the {id, state} the samples and evidence keep. */
function blockInfo(result: BlockAtJson | null | undefined) {
  return result?.loaded ? { id: result.id, state: result.state } : null;
}

/** A screenshot's metadata for the evidence, without its pixel buffers. */
function shotSummary(result: media.ScreenshotResult) {
  const { png: _png, rgba: _rgba, ...rest } = result;
  return { status: "ok", ...rest };
}

function actionReason(result: any) {
  return result?.reason ?? result?.error ?? "unknown";
}
async function safeScreenshot(client: any, options: any) {
  try {
    const { path: shotRel, camera, context } = options;
    const shot = shotSummary(await media.screenshot(client, { path: path.join(REPO_ROOT, shotRel), camera }));
    return { ...shot, path: shotRel, context };
  } catch (error) {
    return { status: "unsupported", path: options.path, camera: options.camera, context: options.context, reason: errorSummary(error), error: errorToString(error) };
  }
}

function annotateColonyScreenshot(rawRel: string, annotatedRel: string, camera: any, blockHits: any[]) {
  const rawAbs = path.join(REPO_ROOT, rawRel);
  const annotatedAbs = path.join(REPO_ROOT, annotatedRel);
  if (!existsSync(rawAbs) || fileSize(rawAbs) === 0) {
    return { status: "skipped", path: annotatedRel, exists: existsSync(annotatedAbs), bytes: fileSize(annotatedAbs), reason: "raw screenshot was not captured" };
  }
  if (!existsSync("/usr/bin/ffmpeg")) {
    return { status: "unsupported", path: annotatedRel, exists: false, bytes: 0, reason: "/usr/bin/ffmpeg is missing" };
  }
  const size = imageSize(rawAbs) ?? { width: 1280, height: 720, source: "fallback" };
  const boxes = colonyAnnotationBoxes(blockHits, camera, size.width, size.height);
  if (boxes.length === 0) {
    return { status: "skipped", path: annotatedRel, exists: existsSync(annotatedAbs), bytes: fileSize(annotatedAbs), reason: "no structure block projected into the screenshot viewport" };
  }
  const filters = boxes.flatMap((box) => [
    `drawbox=x=${box.x}:y=${box.y}:w=${box.w}:h=${box.h}:color=${box.color}:t=3`,
    `drawtext=text='${box.label}':x=${box.x}:y=${Math.max(0, box.y - 18)}:fontsize=16:fontcolor=white:box=1:boxcolor=black@0.55`,
  ]);
  const result = spawnSync("/usr/bin/ffmpeg", ["-y", "-i", rawAbs, "-vf", filters.join(","), annotatedAbs], { encoding: "utf8" });
  if (result.status !== 0) {
    return { status: "failed", path: annotatedRel, exists: existsSync(annotatedAbs), bytes: fileSize(annotatedAbs), reason: trimText(result.stderr || result.stdout || `ffmpeg exited ${result.status}`, 1000) };
  }
  return { status: "annotated", path: annotatedRel, exists: existsSync(annotatedAbs), bytes: fileSize(annotatedAbs), boxes };
}

function imageSize(filePath: string) {
  if (!existsSync("/usr/bin/ffprobe")) return null;
  const result = spawnSync("/usr/bin/ffprobe", ["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=s=x:p=0", filePath], { encoding: "utf8" });
  if (result.status !== 0) return null;
  const [width, height] = result.stdout.trim().split("x").map((value) => Number.parseInt(value, 10));
  if (!Number.isFinite(width) || !Number.isFinite(height)) return null;
  return { width, height, source: "ffprobe" };
}

function colonyAnnotationBoxes(blockHits: any[], camera: any, width: number, height: number) {
  const structureHits = blockHits
    .filter((hit) => (isBed(hit.id, hit.state) && hit.state.includes("part=foot")) || hit.id === "minecraft:chest" || hit.id === "minecraft:crafting_table")
    .slice(0, 16);
  const bedCount = { value: 0 };
  return structureHits
    .map((hit) => {
      const projected = projectWorldToScreen({ x: hit.position.x + 0.5, y: hit.position.y + 0.2, z: hit.position.z + 0.5 }, camera, width, height);
      if (!projected) return null;
      const isBedFoot = isBed(hit.id, hit.state);
      const label = isBedFoot ? `bed${++bedCount.value}` : hit.id === "minecraft:chest" ? "chest" : "craft";
      const color = isBedFoot ? "yellow@0.9" : hit.id === "minecraft:chest" ? "lime@0.9" : "cyan@0.9";
      const boxW = isBedFoot ? 28 : 34;
      const boxH = isBedFoot ? 22 : 28;
      return {
        label,
        color,
        world: hit.position,
        x: clamp(Math.round(projected.x - boxW / 2), 0, Math.max(0, width - boxW)),
        y: clamp(Math.round(projected.y - boxH / 2), 0, Math.max(0, height - boxH)),
        w: boxW,
        h: boxH,
      };
    })
    .filter(Boolean);
}

function projectWorldToScreen(point: any, camera: any, width: number, height: number) {
  const yaw = degreesToRadians(camera.yaw ?? 0);
  const pitch = degreesToRadians(camera.pitch ?? 0);
  const forward = { x: -Math.sin(yaw) * Math.cos(pitch), y: -Math.sin(pitch), z: Math.cos(yaw) * Math.cos(pitch) };
  const right = { x: Math.cos(yaw), y: 0, z: Math.sin(yaw) };
  const up = cross(right, forward);
  const delta = { x: point.x - camera.position.x, y: point.y - camera.position.y, z: point.z - camera.position.z };
  const depth = dot(delta, forward);
  if (depth <= 0.01) return null;
  const fov = degreesToRadians(camera.fov ?? 70);
  const aspect = width / height;
  const halfHeight = Math.tan(fov / 2) * depth;
  const halfWidth = halfHeight * aspect;
  const cameraX = dot(delta, right);
  const cameraY = dot(delta, up);
  return { x: width / 2 + (cameraX / halfWidth) * (width / 2), y: height / 2 - (cameraY / halfHeight) * (height / 2) };
}

function isBed(id: string, state: string) {
  return /minecraft:[a-z_]+_bed$/.test(id) || /minecraft:[a-z_]+_bed/.test(state);
}

function fileSize(filePath: string) {
  return existsSync(filePath) ? statSync(filePath).size : 0;
}

function isFarmlandBlock(block: any) {
  return blockId(block) === "minecraft:farmland";
}

function isCropBlock(block: any) {
  return blockId(block) === FARM_CROP_BLOCK || String(block?.state ?? "").includes(FARM_CROP_BLOCK);
}

function isMatureCropBlock(block: any) {
  return isCropBlock(block) && String(block?.state ?? "").includes("age=7");
}

function blockId(block: any) {
  return block?.id ?? "";
}

function uniqueBlockStates(blocks: any[]) {
  const seen = new Set<string>();
  const result = [];
  for (const block of blocks) {
    const key = `${block?.id ?? "unknown"} ${block?.state ?? ""}`.trim();
    if (!seen.has(key)) {
      seen.add(key);
      result.push({ id: block?.id ?? null, state: block?.state ?? null });
    }
  }
  return result;
}
function trimText(value: string, max: number) {
  return value.length > max ? `${value.slice(0, max - 3)}...` : value;
}
function degreesToRadians(value: number) {
  return (value * Math.PI) / 180;
}

function dot(a: any, b: any) {
  return a.x * b.x + a.y * b.y + a.z * b.z;
}

function cross(a: any, b: any) {
  return { x: a.y * b.z - a.z * b.y, y: a.z * b.x - a.x * b.z, z: a.x * b.y - a.y * b.x };
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

type Cell = { x: number; y: number; z: number };

function forestryLayout(origin: Cell) {
  const ground = origin.y - 1;
  const woodlot = {
    min: { x: origin.x - 3, y: ground, z: origin.z - 14 },
    max: { x: origin.x - 3 + WOODLOT_SIZE - 1, y: ground, z: origin.z - 14 + WOODLOT_SIZE - 1 },
  };
  const chest: Cell = { x: origin.x - 6, y: origin.y, z: origin.z - 6 };

  const fellGround: Cell = { x: origin.x + 8, y: ground, z: origin.z - 10 };
  const base: Cell = { x: fellGround.x, y: fellGround.y + 1, z: fellGround.z };
  const trunk: Cell[] = [];
  for (let dy = 0; dy < TREE_BODY_HEIGHT; dy++) trunk.push({ x: base.x, y: base.y + dy, z: base.z });
  const branches: Cell[] = [
    { x: base.x + 1, y: base.y + 5, z: base.z },
    { x: base.x + 2, y: base.y + 5, z: base.z },
    { x: base.x, y: base.y + 6, z: base.z + 1 },
    { x: base.x, y: base.y + 6, z: base.z + 2 },
    { x: base.x - 1, y: base.y + 4, z: base.z - 1 },
  ];
  const body = [...trunk, ...branches];
  return {
    woodlot,
    chest,
    fellGround,
    base,
    trunk,
    branches,
    body,
    beyondReach: body.filter((cell) => cellDistance(cell, base) > UNREACHABLE_FROM_FOOT),
    beyondBodySideways: { x: base.x + TREE_BODY_RADIUS + 1, y: base.y + 5, z: base.z },
    beyondBodyAbove: { x: base.x, y: base.y + TREE_BODY_HEIGHT, z: base.z },
  };
}

function expectedWoodlotSaplings() {
  return Math.ceil(WOODLOT_SIZE / MIN_SAPLING_SPACING) ** 2;
}

function cellDistance(a: Cell, b: Cell) {
  return Math.sqrt((a.x - b.x) ** 2 + (a.y - b.y) ** 2 + (a.z - b.z) ** 2);
}

function cellKey(cell: Cell) {
  return `${cell.x},${cell.y},${cell.z}`;
}

function closestHorizontalPair(cells: Cell[]) {
  let best = Infinity;
  let pair: Cell[] = [];
  for (let i = 0; i < cells.length; i++) {
    for (let j = i + 1; j < cells.length; j++) {
      const chebyshev = Math.max(Math.abs(cells[i].x - cells[j].x), Math.abs(cells[i].z - cells[j].z));
      if (chebyshev < best) {
        best = chebyshev;
        pair = [cells[i], cells[j]];
      }
    }
  }
  return { distance: best, pair };
}

async function readCells(server: any, cells: Cell[]) {
  const out: Array<{ cell: Cell; block: any }> = [];
  for (let i = 0; i < cells.length; i += 12) {
    const chunk = cells.slice(i, i + 12);
    const results = await Promise.all(
      chunk.map((cell) => world.block(server, cell, { dimension: "minecraft:overworld" })),
    );
    results.forEach((result, index) => out.push({ cell: chunk[index], block: blockInfo(result) }));
  }
  return out;
}

async function readWoodlotSaplings(server: any, woodlot: { min: Cell; max: Cell }) {
  const cells: Cell[] = [];
  for (let x = woodlot.min.x; x <= woodlot.max.x; x++) {
    for (let z = woodlot.min.z; z <= woodlot.max.z; z++) {
      cells.push({ x, y: woodlot.min.y + 1, z });
    }
  }
  const read = await readCells(server, cells);
  return read.filter((entry) => blockId(entry.block) === FORESTRY_CROP_ID).map((entry) => entry.cell);
}

function isLogCell(entry: { block: any }) {
  return blockId(entry.block) === FORESTRY_LOG_BLOCK;
}

async function buildForestryFixtures(server: any, layout: ReturnType<typeof forestryLayout>) {
  const saplingStacks = 64;
  const commandSpecs = [
    { command: `fill ${commandPos(layout.woodlot.min)} ${commandPos(layout.woodlot.max)} minecraft:dirt`, required: true },
    { command: `setblock ${commandPos(layout.fellGround)} minecraft:stone`, required: true },
    { command: `setblock ${commandPos(layout.chest)} minecraft:chest{Items:[]}`, required: true },
    { command: `item replace block ${commandPos(layout.chest)} container.0 with ${FORESTRY_CROP_ID} ${saplingStacks}`, required: true },
  ];
  const results = [];
  for (const spec of commandSpecs) {
    results.push(await taggedCommand(server, spec.command, spec.required));
  }
  await tick.sprint(server, 5);
  return { sapling_stock: saplingStacks, command_results: results };
}

async function buildStandingTree(server: any, layout: ReturnType<typeof forestryLayout>) {
  const placed = [...layout.body, layout.beyondBodySideways, layout.beyondBodyAbove];
  const results = [];
  await tick.freeze(server, true);
  try {
    for (const cell of placed) {
      results.push(await taggedCommand(server, `setblock ${commandPos(cell)} ${FORESTRY_LOG_BLOCK}`, true));
    }
  } finally {
    await tick.freeze(server, false);
  }
  await tick.sprint(server, 5);
  const standing = await readCells(server, placed);
  const missing = standing.filter((entry) => !isLogCell(entry)).map((entry) => cellKey(entry.cell));
  return {
    placed_cells: placed.length,
    standing_logs: standing.filter(isLogCell).length,
    missing,
    fixture_ok: missing.length === 0,
    command_results: results,
  };
}

async function armForestryZoneViaPanel(
  server: any, client: any, playerName: string, standBlock: any,
  cornerA: Cell, cornerB: Cell, label: string, index: number, cells: number,
) {
  const evidence: any = { label, corner_a: cornerA, corner_b: cornerB, crop: FORESTRY_CROP_ID, index, want_cells: cells };
  try {
    evidence.zone_mode = await setBookGesture(server, client, playerName, "box");
    evidence.corner_1 = await aimAndWorldLeftClick(server, client, playerName, cornerA);
    await tick.sprint(server, 3);
    evidence.corner_2 = await aimAndWorldLeftClick(server, client, playerName, cornerB);
    await tick.sprint(server, 5);

    evidence.action_bar_before = await drainActionBar(client);
    evidence.configure = await createMarkedZone(server, client, playerName, standBlock, "farm");
    evidence.set_crop = await configureZone(server, client, playerName, standBlock,
      { min: cornerA, max: cornerB },
      [{ key: "crop", option: FORESTRY_CROP_ID, shot: index === 1 ? CROP_PICKER_SHOT : undefined }]);

    const registry = await colonyRegistry(server);
    evidence.zone_kind = await zoneKindAt(server, registry, index);
    evidence.zone_crop = await zoneChoiceSettingAt(server, registry, "crop", index);
    evidence.zone_cells = await zoneCellsAt(server, registry, index);
    evidence.created = evidence.zone_kind === ZONE_KIND_ID.farm
      && evidence.zone_crop === FORESTRY_CROP_ID
      && evidence.zone_cells === cells;
  } catch (error) {
    evidence.created = false;
    evidence.error = errorSummary(error);
  } finally {
    await screen.dismiss(client);
  }
  return evidence;
}

async function observeWoodlotSowing(server: any, layout: ReturnType<typeof forestryLayout>) {
  const firstCell: Cell = { x: layout.woodlot.min.x, y: layout.woodlot.min.y + 1, z: layout.woodlot.min.z };
  const evidence: any = { first_cell: firstCell, samples: [] };
  await taggedCommand(server, "time set day", false);
  try {
    const started = await waitMatched(server, `if block ${commandPos(firstCell)} ${FORESTRY_CROP_ID}`, {
      maxTicks: 2400,
      stepTicks: 20,
    });
    evidence.first_sapling = { matched: started.matched, ticks: started.ticks, reason: started.reason };
  } catch (error) {
    evidence.first_sapling = { matched: false, error: errorSummary(error) };
  }

  let saplings = await readWoodlotSaplings(server, layout.woodlot);
  let stable = 0;
  let ticks = 0;
  for (let i = 0; i < 10 && stable < 2; i++) {
    await tick.sprint(server, 200);
    ticks += 200;
    const now = await readWoodlotSaplings(server, layout.woodlot);
    stable = now.length > 0 && now.length === saplings.length ? stable + 1 : 0;
    saplings = now;
    evidence.samples.push({ ticks, saplings: now.length });
  }
  await tick.sprint(server, 300);
  ticks += 300;
  saplings = await readWoodlotSaplings(server, layout.woodlot);
  evidence.samples.push({ ticks, saplings: saplings.length, label: "after_settle" });

  const closest = closestHorizontalPair(saplings);
  return {
    ...evidence,
    sapling_in_chest: await blockHasItem(server, layout.chest, FORESTRY_CROP_ID),
    sapling_in_packs: (await countInResidentPacks(server)).totals?.[FORESTRY_CROP_ID] ?? 0,
    observed_ticks: ticks,
    saplings,
    sapling_count: saplings.length,
    distinct_x: new Set(saplings.map((cell) => cell.x)).size,
    distinct_z: new Set(saplings.map((cell) => cell.z)).size,
    closest_pair_distance: closest.distance,
    closest_pair: closest.pair,
  };
}

async function observeTreeFelling(server: any, layout: ReturnType<typeof forestryLayout>) {
  const allDown = layout.body.map((cell) => `if block ${commandPos(cell)} minecraft:air`).join(" ");
  const evidence: any = {};
  await taggedCommand(server, "time set day", false);
  const CHUNK_TICKS = 3000;
  const CHUNKS = 4;
  const trajectory: { after_ticks: number; standing: number }[] = [];
  try {
    let spent = 0;
    let matched = false;
    let reason = "";
    for (let chunk = 0; chunk < CHUNKS && !matched; chunk++) {
      const felled = await waitMatched(server, allDown, { maxTicks: CHUNK_TICKS, stepTicks: 20 });
      matched = felled.matched;
      reason = felled.reason;
      spent += felled.ticks;
      const probe = await readCells(server, layout.body);
      trajectory.push({ after_ticks: spent, standing: probe.filter(isLogCell).length });
    }
    evidence.wait = { matched, ticks: spent, reason, trajectory };
  } catch (error) {
    evidence.wait = { matched: false, error: errorSummary(error), trajectory };
  }

  const body = await readCells(server, layout.body);
  const bounds = await readCells(server, [layout.beyondBodySideways, layout.beyondBodyAbove]);
  const stillStanding = body.filter(isLogCell).map((entry) => cellKey(entry.cell));
  const branchesStanding = body
    .filter((entry) => isLogCell(entry) && layout.branches.some((branch) => cellKey(branch) === cellKey(entry.cell)))
    .map((entry) => cellKey(entry.cell));
  const beyondReachStanding = body
    .filter((entry) => isLogCell(entry) && layout.beyondReach.some((cell) => cellKey(cell) === cellKey(entry.cell)))
    .map((entry) => cellKey(entry.cell));
  return {
    ...evidence,
    body_cells: layout.body.length,
    still_standing: stillStanding,
    branches_standing: branchesStanding,
    beyond_reach_standing: beyondReachStanding,
    boundary_logs_intact: bounds.every(isLogCell),
    boundary_logs: bounds.map((entry) => ({ cell: cellKey(entry.cell), id: blockId(entry.block) })),
  };
}

async function observeForestryGameplay(
  server: any, client: any, playerName: string, colonyEvidence: any,
  snapshotContainers: Array<{ x: number; y: number; z: number }>,
) {
  const layout = forestryLayout(colonyEvidence.origin);
  snapshotContainers.push(layout.chest);
  const expectedSaplings = expectedWoodlotSaplings();
  const evidence: any = { layout: { woodlot: layout.woodlot, chest: layout.chest, fell_ground: layout.fellGround, tree_base: layout.base } };
  const reasons: string[] = [];

  try {
    evidence.fixtures = await buildForestryFixtures(server, layout);
    evidence.opt_in = await optInContainerViaUI(server, client, playerName, layout.chest);
    evidence.chest_is_member = await isChestColonyMember(server, layout.chest);
    evidence.chest_has_saplings = await blockHasItem(server, layout.chest, FORESTRY_CROP_ID);
    evidence.hoes = await stockToolsForResidents(server, layout.chest, TRADE_TOOLS.farming,
      RESIDENT_COUNT, 1);

    evidence.saplings_before = (await readWoodlotSaplings(server, layout.woodlot)).length;

    evidence.woodlot_zone = await armForestryZoneViaPanel(
      server, client, playerName, colonyEvidence.stand_block, layout.woodlot.min, layout.woodlot.max,
      "woodlot", 1, WOODLOT_SIZE * WOODLOT_SIZE);
    evidence.fell_zone = await armForestryZoneViaPanel(
      server, client, playerName, colonyEvidence.stand_block, layout.fellGround, layout.fellGround,
      "felling", 2, 1);

    evidence.sowing = await observeWoodlotSowing(server, layout);
    evidence.hoes_in_a_pack = await packHasItem(server, "minecraft:wooden_hoe");

    const packsBefore = await countInResidentPacks(server);
    evidence.tree_fixture = await buildStandingTree(server, layout);
    evidence.felling = await observeTreeFelling(server, layout);
    const packsAfter = await countInResidentPacks(server);
    evidence.logs_into_packs =
      (packsAfter.totals?.[FORESTRY_LOG_BLOCK] ?? 0) - (packsBefore.totals?.[FORESTRY_LOG_BLOCK] ?? 0);
  } catch (error) {
    return {
      status: "FAIL",
      reasons: [`forestry setup/observation threw: ${errorSummary(error)}`],
      evidence: { ...evidence, error: errorToString(error) },
    };
  }

  if (expectedSaplings < 4) {
    reasons.push(`fixture: a ${WOODLOT_SIZE}×${WOODLOT_SIZE} woodlot admits only ${expectedSaplings} saplings at step ${MIN_SAPLING_SPACING} — too few for spacing to show up in two axes`);
  }
  if (layout.beyondReach.length === 0) {
    reasons.push(`fixture: no log of the ${layout.body.length}-cell body sits further than ${UNREACHABLE_FROM_FOOT} blocks from the trunk foot — "a felling reaches past what a stance at the foot could touch" would be vacuous`);
  }
  if (layout.branches.length < 5) {
    reasons.push(`fixture: only ${layout.branches.length} branch logs — trunk-and-branch needs branches the trunk column never passes through, including one connected only diagonally`);
  }

  const setupFailures = [
    ...(evidence.fixtures.command_results ?? []),
    ...(evidence.tree_fixture.command_results ?? []),
  ].filter((result: any) => result.required && !commandSucceeded(result));
  if (setupFailures.length > 0) {
    reasons.push(`forestry fixture command(s) failed: ${setupFailures.map((r: any) => r.command).join("; ")}`);
  }
  if (!evidence.chest_is_member) {
    reasons.push("the forestry supply chest is still not a colony member after opt-in: saplings are not in colony stock, so sowing on this plot has nothing to plant");
  }
  if (!evidence.chest_has_saplings) {
    reasons.push(`no ${FORESTRY_CROP_ID} in the forestry supply chest: the woodlot has nothing to plant`);
  }
  if (!evidence.hoes_in_a_pack) {
    reasons.push("no resident ever picked up the wooden hoe: TillNode is the only node here that needs a tool, and residents without a hoe only get NO_TOOL");
  }
  if (evidence.saplings_before !== 0) {
    reasons.push(`the woodlot already held ${evidence.saplings_before} saplings before the zone existed — the sowing assertion below would prove nothing`);
  }
  if (!evidence.woodlot_zone.created) {
    reasons.push(`the woodlot zone was not created as ${FORESTRY_CROP_ID} at ${WOODLOT_SIZE}x${WOODLOT_SIZE}: the colony holds`
      + ` kind=${evidence.woodlot_zone.zone_kind} crop=${evidence.woodlot_zone.zone_crop}`
      + ` cells=${evidence.woodlot_zone.zone_cells}`
      + ` (${JSON.stringify(evidence.woodlot_zone.error ?? null)})`);
  }
  if (!evidence.fell_zone.created) {
    reasons.push(`the felling zone was not created as a single-block ${FORESTRY_CROP_ID}: the colony holds`
      + ` kind=${evidence.fell_zone.zone_kind} crop=${evidence.fell_zone.zone_crop}`
      + ` cells=${evidence.fell_zone.zone_cells}`
      + ` (${JSON.stringify(evidence.fell_zone.error ?? null)})`);
  }
  if (!evidence.tree_fixture.fixture_ok) {
    reasons.push(`the standing tree was never built: ${evidence.tree_fixture.missing.length} of ${evidence.tree_fixture.placed_cells} cells hold no log (${evidence.tree_fixture.missing.join(" ")})`);
  }

  const sowing = evidence.sowing;
  if (sowing.sapling_count < 4) {
    reasons.push(`woodlot sowing: only ${sowing.sapling_count} saplings stand in the ${WOODLOT_SIZE}×${WOODLOT_SIZE} woodlot after ${sowing.observed_ticks} ticks (expected ${expectedSaplings}); fewer than 4 makes every spacing claim below vacuous`);
  }
  if (!(sowing.distinct_x >= 2 && sowing.distinct_z >= 2)) {
    reasons.push(`woodlot sowing: the saplings occupy ${sowing.distinct_x} distinct x and ${sowing.distinct_z} distinct z — spacing was only ever measured along one axis, which a 1-wide fixture would also satisfy`);
  }

  if (sowing.sapling_count >= 2 && sowing.closest_pair_distance < MIN_SAPLING_SPACING) {
    reasons.push(`woodlot spacing: two saplings stand ${sowing.closest_pair_distance} apart (${JSON.stringify(sowing.closest_pair)}), but a Tree(radius=${TREE_BODY_RADIUS}) body needs ≥${MIN_SAPLING_SPACING} — the pass is not judging a candidate against the seeds it has already accepted this cycle`);
  }
  if (sowing.sapling_count > expectedSaplings) {
    reasons.push(`woodlot spacing: ${sowing.sapling_count} saplings stand in ${WOODLOT_SIZE * WOODLOT_SIZE} cells, more than the ${expectedSaplings} a step-${MIN_SAPLING_SPACING} grid admits — the field was carpeted`);
  }

  const felling = evidence.felling;
  if (felling.still_standing.length > 0) {
    const trail = (felling.wait?.trajectory ?? [])
      .map((point: any) => `${point.after_ticks}t:${point.standing}`).join(" → ");
    reasons.push(`felling: ${felling.still_standing.length} of ${felling.body_cells} logs are still standing after ${felling.wait?.ticks ?? "?"} ticks (${felling.still_standing.join(" ")}); progress [${trail}] - strictly decreasing = budget ran out, flat = truly stuck`);
  }
  if (felling.branches_standing.length > 0) {
    reasons.push(`felling: the trunk came down but ${felling.branches_standing.length} branch log(s) are left standing (${felling.branches_standing.join(" ")}) — the felling took the trunk column only, not the connected body`);
  }
  if (felling.beyond_reach_standing.length > 0) {
    reasons.push(`felling: ${felling.beyond_reach_standing.join(" ")} still stand — these sit further than ${UNREACHABLE_FROM_FOOT} blocks from the trunk foot, so a felling lowered to one reach-gated break per log stops exactly here`);
  }
  if (!felling.boundary_logs_intact) {
    reasons.push(`felling: a log outside the body box was taken too (${JSON.stringify(felling.boundary_logs)}) — the felling is walking along connected wood instead of being bounded by the tree's own body`);
  }
  if (!(evidence.logs_into_packs >= 1)) {
    reasons.push(`felling: the logs vanished but no ${FORESTRY_LOG_BLOCK} reached any resident's pack (delta ${evidence.logs_into_packs}) — nobody actually broke them`);
  }

  return { status: reasons.length === 0 ? "PASS" : "FAIL", reasons, evidence };
}

function forestryFailureMessage(forestry: any) {
  const evidence = forestry.evidence ?? {};
  const digest = {
    woodlot_zone_cells: evidence.woodlot_zone?.zone_cells,
    first_sapling: evidence.sowing?.first_sapling,
    sapling_count: evidence.sowing?.sapling_count,
    saplings: evidence.sowing?.saplings,
    sapling_in_chest: evidence.sowing?.sapling_in_chest,
    sapling_in_packs: evidence.sowing?.sapling_in_packs,
    closest_pair_distance: evidence.sowing?.closest_pair_distance,
    sowing_ticks: evidence.sowing?.observed_ticks,
    felling_ticks: evidence.felling?.wait?.ticks,
    logs_still_standing: evidence.felling?.still_standing,
    boundary_logs: evidence.felling?.boundary_logs,
    logs_into_packs: evidence.logs_into_packs,
  };
  return `${(forestry.reasons ?? []).join("; ")} | forestry world state: ${JSON.stringify(digest)}`;
}
