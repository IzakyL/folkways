import { expect, test } from "@playwright/test";
import { tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { optInContainerViaUI } from "../shared/colony/membership-optin";
import { stockToolsForResidents, foundColonyFast, foundingBox, TRADE_TOOLS, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { aimAndWorldLeftClick, countInResidentPacks, mustCommand, packHasItem } from "../shared/ui/world-ui";
import {
  colonyRegistry,
  configureZone,
  createMarkedZone,
  setBookGesture,
  zoneCellsAt,
  zoneChoiceSettingAt,
  zoneKindAt,
  ZONE_KIND_ID,
} from "../shared/ui/zone-tools";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, tryCommand, waitMatched } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { around, cover, twins, type Stage } from "../shared/harness/stage";

const RESIDENT_TYPE = "folkways_vanilla:resident";
const RESIDENT_COUNT = 6;
const FARM_CROP_BLOCK = "minecraft:wheat";
const FARM_SEED_ITEM = "minecraft:wheat_seeds";

const FORESTRY_CROP_ID = "minecraft:oak_sapling";
const FORESTRY_LOG_BLOCK = "minecraft:oak_log";
const TREE_BODY_RADIUS = 2;
const TREE_BODY_HEIGHT = 9;
const MIN_SAPLING_SPACING = TREE_BODY_RADIUS + 1;
const WOODLOT_SIZE = 7;
const UNREACHABLE_FROM_FOOT = 6.5;

type Cell = { x: number; y: number; z: number };
type Scene = { server: MinecraftServer; client: MinecraftClient; stage: Stage; player: string; stand: Cell };

for (const stage of twins()) test(stage.title("farm end-to-end: till → crop → harvest via panel"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.slow);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const snapshotContainers: Cell[] = [];
  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("farm-server")), instance: seededServer(stage.name(`farm-server-${runId}`)) },
      client: { trace: tracePath("e2e", stage.name("farm-client")), instance: stage.name(`farm-client-${runId}`) },
      snapshot: () => ({ containers: snapshotContainers.map((at) => stage.at(at)), entityTypes: [RESIDENT_TYPE, "minecraft:item"] }),
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    await world.command(server, `gamemode survival ${player}`);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await stage.lay(server, cover(foundingBox(origin), around(forestryLayout(origin).body, 0, 1)));
    const founding = await foundColonyFast(server, client, player, { origin, residentCount: RESIDENT_COUNT, stage });
    const scene: Scene = { server, client, stage, player, stand: founding.standBlock };

    await farmWheat(scene, origin, snapshotContainers);
    await forestry(scene, origin, snapshotContainers);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

// A single-block wheat farm marked through the book. The member supply chest holds hoe materials and seeds; the
// hoes are stocked there for residents to pick up. Maturity is forced once the crop appears.
async function farmWheat(scene: Scene, origin: Cell, snapshotContainers: Cell[]) {
  const { server, client, stage, player } = scene;
  const block = (cell: Cell) => commandPos(stage.at(cell));
  const chest = { x: origin.x, y: origin.y, z: origin.z + 8 };
  const craftingTable = { x: origin.x + 2, y: origin.y, z: origin.z + 8 };
  const ground = { x: origin.x + 4, y: origin.y - 1, z: origin.z + 10 };
  const planting = { x: ground.x, y: ground.y + 1, z: ground.z };
  snapshotContainers.push(chest);
  await mustCommand(server, `setblock ${block(ground)} minecraft:dirt`);
  await tryCommand(server, `setblock ${block(planting)} minecraft:air`);
  await tryCommand(server, `setblock ${block({ ...planting, y: planting.y + 1 })} minecraft:air`);
  await mustCommand(server, `setblock ${block(craftingTable)} minecraft:crafting_table`);
  await mustCommand(server, `setblock ${block(chest)} minecraft:chest{Items:[]}`);
  await mustCommand(server, `item replace block ${block(chest)} container.0 with minecraft:oak_planks 2`);
  await mustCommand(server, `item replace block ${block(chest)} container.1 with minecraft:stick 2`);
  await mustCommand(server, `item replace block ${block(chest)} container.2 with ${FARM_SEED_ITEM} 4`);
  await tick.sprint(server, 3);
  await optInContainerViaUI(server, client, player, chest, stage);

  await markZone(scene, ground, ground);
  const registry = await colonyRegistry(server);
  expect(await zoneKindAt(server, registry, 0)).toBe(ZONE_KIND_ID.farm);
  expect(await zoneChoiceSettingAt(server, registry, "crop", 0), "a new farm zone grows wheat").toBe(FARM_CROP_BLOCK);
  expect(await zoneCellsAt(server, registry, 0)).toBe(1);
  await stockToolsForResidents(server, stage.at(chest), TRADE_TOOLS.farming, RESIDENT_COUNT, 3);

  const blockAt = (cell: Cell) => world.block(server, stage.at(cell), { dimension: "minecraft:overworld" });
  const mature = async () => {
    const crop = await blockAt(planting);
    return crop.id === FARM_CROP_BLOCK && String(crop.state ?? "").includes("age=7");
  };
  let tilled = false, cropped = false, harvested = false;
  for (let iteration = 1; iteration <= 60 && !(tilled && cropped && harvested); iteration++) {
    await tick.sprint(server, 20);
    tilled ||= (await blockAt(ground)).id === "minecraft:farmland";
    if (cropped) {
      harvested ||= !(await mature());
    } else if ((await blockAt(planting)).id === FARM_CROP_BLOCK) {
      cropped = true;
      await mustCommand(server, `setblock ${block(planting)} ${FARM_CROP_BLOCK}[age=7]`);
      for (let fine = 0; fine < 30 && !harvested; fine++) {
        await tick.sprint(server, 2);
        harvested = !(await mature());
      }
    }
  }
  expect(tilled, "the farm ground becomes farmland").toBe(true);
  expect(cropped, "wheat is planted on the tilled ground").toBe(true);
  expect(harvested, "the mature wheat is harvested").toBe(true);
}

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
  // Branches the trunk column never passes through, one joined only diagonally.
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
    branches,
    body,
    // Logs no stance at the trunk foot could reach.
    beyondReach: body.filter((cell) => cellDistance(cell, base) > UNREACHABLE_FROM_FOOT),
    // Logs joined to the tree but outside its Tree(radius, height) box.
    beyondBox: [{ x: base.x + TREE_BODY_RADIUS + 1, y: base.y + 5, z: base.z }, { x: base.x, y: base.y + TREE_BODY_HEIGHT, z: base.z }],
  };
}

// A 7×7 woodlot sown with oak saplings spaced for a Tree(radius=2) body, and a standing oak with branches, logs
// past reach and logs past its box, all felled with the wood reaching residents' packs.
async function forestry(scene: Scene, origin: Cell, snapshotContainers: Cell[]) {
  const { server, client, stage, player } = scene;
  const block = (cell: Cell) => commandPos(stage.at(cell));
  const layout = forestryLayout(origin);
  snapshotContainers.push(layout.chest);
  await mustCommand(server, `fill ${block(layout.woodlot.min)} ${block(layout.woodlot.max)} minecraft:dirt`);
  await mustCommand(server, `setblock ${block(layout.fellGround)} minecraft:stone`);
  await mustCommand(server, `setblock ${block(layout.chest)} minecraft:chest{Items:[]}`);
  await mustCommand(server, `item replace block ${block(layout.chest)} container.0 with ${FORESTRY_CROP_ID} 64`);
  await tick.sprint(server, 5);
  await optInContainerViaUI(server, client, player, layout.chest, stage);
  await stockToolsForResidents(server, stage.at(layout.chest), TRADE_TOOLS.farming, RESIDENT_COUNT, 1);
  expect(await readWoodlotSaplings(scene, layout.woodlot), "the woodlot starts bare").toEqual([]);

  await markZone(scene, layout.woodlot.min, layout.woodlot.max, FORESTRY_CROP_ID);
  await markZone(scene, layout.fellGround, layout.fellGround, FORESTRY_CROP_ID);
  const registry = await colonyRegistry(server);
  for (const [index, cells] of [[1, WOODLOT_SIZE * WOODLOT_SIZE], [2, 1]] as const) {
    expect(await zoneKindAt(server, registry, index)).toBe(ZONE_KIND_ID.farm);
    expect(await zoneChoiceSettingAt(server, registry, "crop", index)).toBe(FORESTRY_CROP_ID);
    expect(await zoneCellsAt(server, registry, index)).toBe(cells);
  }

  const saplings = await observeWoodlotSowing(scene, layout);
  const expectedSaplings = Math.ceil(WOODLOT_SIZE / MIN_SAPLING_SPACING) ** 2;
  const closest = closestHorizontalPair(saplings);
  const sown = `saplings ${JSON.stringify(saplings)}`;
  expect(saplings.length, `the woodlot is sown; ${sown}`).toBeGreaterThanOrEqual(4);
  expect(new Set(saplings.map((cell) => cell.x)).size, `saplings span the x axis; ${sown}`).toBeGreaterThanOrEqual(2);
  expect(new Set(saplings.map((cell) => cell.z)).size, `saplings span the z axis; ${sown}`).toBeGreaterThanOrEqual(2);
  expect(closest.distance, `no two saplings closer than a tree body allows; ${JSON.stringify(closest.pair)}`)
    .toBeGreaterThanOrEqual(MIN_SAPLING_SPACING);
  expect(saplings.length, `the woodlot is not carpeted; ${sown}`).toBeLessThanOrEqual(expectedSaplings);
  expect(await packHasItem(server, "minecraft:wooden_hoe"), "a resident picks up a stocked hoe").toBe(true);

  const packsBefore = await countInResidentPacks(server);
  const placed = [...layout.body, ...layout.beyondBox];
  await tick.freeze(server, true);
  try {
    for (const cell of placed) await mustCommand(server, `setblock ${block(cell)} ${FORESTRY_LOG_BLOCK}`);
  } finally {
    await tick.freeze(server, false);
  }
  await tryCommand(server, "time set day");
  const allDown = layout.body.map((cell) => `if block ${block(cell)} minecraft:air`).join(" ");
  for (let chunk = 0; chunk < 4; chunk++) {
    if ((await waitMatched(server, allDown, { maxTicks: 3000, stepTicks: 20 })).matched) break;
  }
  const standing = async (cells: Cell[]) => (await Promise.all(cells.map(async (cell) =>
    ((await world.block(server, stage.at(cell), { dimension: "minecraft:overworld" })).id === FORESTRY_LOG_BLOCK ? [cellKey(cell)] : []))))
    .flat();
  expect(await standing(layout.branches), "the felling takes the branches with the trunk").toEqual([]);
  expect(await standing(layout.beyondReach), `logs past ${UNREACHABLE_FROM_FOOT} blocks from the foot are felled`).toEqual([]);
  expect(await standing(layout.body), "the whole tree is felled").toEqual([]);
  expect(await standing(layout.beyondBox), "logs joined past the tree's box are felled").toEqual([]);
  const packsAfter = await countInResidentPacks(server);
  expect((packsAfter.totals?.[FORESTRY_LOG_BLOCK] ?? 0) - (packsBefore.totals?.[FORESTRY_LOG_BLOCK] ?? 0),
    "the felled logs reach residents' packs").toBeGreaterThanOrEqual(1);
}

async function markZone(scene: Scene, cornerA: Cell, cornerB: Cell, crop?: string) {
  const { server, client, stage, player, stand } = scene;
  await setBookGesture(server, client, player, "box");
  await aimAndWorldLeftClick(server, client, player, cornerA, stage);
  await tick.sprint(server, 3);
  await aimAndWorldLeftClick(server, client, player, cornerB, stage);
  await tick.sprint(server, 5);
  await createMarkedZone(server, client, player, stand, "farm", stage);
  if (crop) await configureZone(server, client, player, stand, { min: cornerA, max: cornerB }, [{ key: "crop", option: crop }], stage);
  await tick.sprint(server, 5);
}

async function observeWoodlotSowing(scene: Scene, layout: ReturnType<typeof forestryLayout>) {
  const { server, stage } = scene;
  const firstCell: Cell = { x: layout.woodlot.min.x, y: layout.woodlot.min.y + 1, z: layout.woodlot.min.z };
  await tryCommand(server, "time set day");
  await waitMatched(server, `if block ${commandPos(stage.at(firstCell))} ${FORESTRY_CROP_ID}`, { maxTicks: 2400, stepTicks: 20 });
  // Sowing settles once the count holds over two 200-tick reads.
  let saplings = await readWoodlotSaplings(scene, layout.woodlot);
  let stable = 0;
  for (let i = 0; i < 10 && stable < 2; i++) {
    await tick.sprint(server, 200);
    const now = await readWoodlotSaplings(scene, layout.woodlot);
    stable = now.length > 0 && now.length === saplings.length ? stable + 1 : 0;
    saplings = now;
  }
  await tick.sprint(server, 300);
  return readWoodlotSaplings(scene, layout.woodlot);
}

async function readWoodlotSaplings(scene: Scene, woodlot: { min: Cell; max: Cell }) {
  const cells: Cell[] = [];
  for (let x = woodlot.min.x; x <= woodlot.max.x; x++) {
    for (let z = woodlot.min.z; z <= woodlot.max.z; z++) cells.push({ x, y: woodlot.min.y + 1, z });
  }
  const sown: Cell[] = [];
  for (let i = 0; i < cells.length; i += 12) {
    const chunk = cells.slice(i, i + 12);
    const read = await Promise.all(chunk.map((cell) => world.block(scene.server, scene.stage.at(cell), { dimension: "minecraft:overworld" })));
    sown.push(...chunk.filter((_, index) => read[index].id === FORESTRY_CROP_ID));
  }
  return sown;
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
