import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { setMaintainDemandViaUI } from "../shared/colony/membership-optin";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seedModSettings } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Stage } from "../shared/harness/stage";

const COOKERY = "kaleidoscope_cookery:";
const RESIDENT = "folkways:resident";
const ITEM = "minecraft:item";
const RAW_MEAT = COOKERY + "raw_cut_small_meats";
const RICE = COOKERY + "rice";
const COOKED_RICE = COOKERY + "cooked_rice";
const RAW_DOUGH = COOKERY + "raw_dough";
const RAW_NOODLES = COOKERY + "raw_noodles";
const UDON = COOKERY + "udon_noodle";
const BOWL = "minecraft:bowl";
const WATER = "minecraft:water_bucket";
const BUCKET = "minecraft:bucket";
const LID = COOKERY + "stockpot_lid";
const CHICKEN = "minecraft:chicken";
const BONE = "minecraft:bone";
const STEP_TICKS = 20;
const PRODUCTION_TICKS = 6000;
const NO_BOWLS_TICKS = 800;
const RESIDENT_COUNT = 3;

// With rice_1/rice_2 available, two bowls may legitimately start a smaller batch. Isolate the real rice_3 recipe
// through the supported recipe blacklist, so this test can require all three carriers before any input is spent.
const REFUSED_RICE = Array.from({ length: 9 }, (_, i) => i + 1).filter((count) => count !== 3)
  .map((count) => `${COOKERY}stockpot/rice_${count}`);

for (const stage of twins()) test(stage.title("cookery: chop multiple products, budget every bowl, and cook a real board-to-pot chain"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const runId = newRunId();
  const serverName = stage.name(`cookery-server-${runId}`);
  seedModSettings(serverName, { mode: "SINGLE_THREAD", refusedRecipes: REFUSED_RICE });
  const evidence: Record<string, unknown> = { stage: stage.kind, refusedRecipes: REFUSED_RICE };
  let pair: E2EPair | undefined;
  let containers: Vec[] = [];
  try {
    pair = await launchPair({
      config: stage.kind === "ground" ? "blockwright.toml#cookery" : "blockwright.toml#cookery-sable",
      server: { trace: tracePath("e2e", stage.name("cookery-server")), instance: serverName },
      client: { trace: tracePath("e2e", stage.name("cookery-client")), instance: stage.name(`cookery-client-${runId}`) },
      snapshot: () => ({ containers: containers.map((at) => stage.at(at)), entityTypes: [RESIDENT, ITEM] }),
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const box = foundingBox(origin);
    await stage.lay(server, box);
    await world.command(server, `fill ${commandPos(stage.at({ ...box.min, y: origin.y - 1 }))}`
      + ` ${commandPos(stage.at({ ...box.max, y: origin.y - 1 }))} minecraft:smooth_stone`);
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT, stage });
    evidence.founding = founding.evidence;
    await world.command(server, "gamerule doDaylightCycle false");
    await world.command(server, "gamerule doMobSpawning false");
    await world.command(server, "difficulty peaceful");
    await world.command(server, "time set day");
    await world.command(server, `gamemode survival ${playerName}`);
    await tryCommand(server, `execute positioned ${commandPos(stage.world(origin))} run kill @e[type=minecraft:fox,distance=..48]`);
    await tryCommand(server, `execute positioned ${commandPos(stage.world(origin))} run kill @e[type=${ITEM},distance=..48]`);
    const layout = kitchen(origin);
    containers = [founding.foodChest, layout.supply, layout.chopped, layout.rice, layout.riceAgain, layout.udon];
    const block = (at: Vec) => commandPos(stage.at(at));
    for (const chest of containers.slice(1)) {
      await world.command(server, `setblock ${block(chest)} minecraft:chest{Items:[]}`);
    }
    await world.command(server, `setblock ${block(layout.board)} ${COOKERY}chopping_board`);
    await world.command(server, `setblock ${block(layout.fire)} minecraft:campfire[lit=true]`);
    await world.command(server, `setblock ${block(layout.pot)} ${COOKERY}stockpot`);
    await tick.sprint(server, 5);
    evidence.optIn = [];
    const marked = evidence.optIn as unknown[];
    for (const chest of containers.slice(1)) {
      marked.push(await optInViaBook(server, client, playerName, chest, "chest", stage));
    }
    // optInViaBook's kind is diagnostic only: point mode chooses the registered kitchen block delegation.
    for (const station of [layout.board, layout.pot]) {
      marked.push(await optInViaBook(server, client, playerName, station, "worksite", stage));
      expect(await world.probe(server,
        `if data block ${block(station)} "neoforge:attachments"."folkways:colony_member"`),
      "the book marks the real kitchen block as a colony member").toBe(true);
    }
    await putStock(server, stage, layout.supply, COOKERY + "iron_kitchen_knife", 1);

    evidence.chopOrder = await setMaintainDemandViaUI(server, client, playerName, layout.chopped, RAW_MEAT, 3, stage);
    const beforeChop = await inventory(server, stage, containers);
    evidence.beforeChop = beforeChop;
    expectCount(beforeChop, RAW_MEAT, 0, "the UI selector must not inject chopped meat");
    expectCount(beforeChop, CHICKEN, 0, "the fixture has no unstated chicken input");
    expectCount(beforeChop, BONE, 0, "the fixture starts without the byproduct");
    expectCount(beforeChop, COOKERY + "iron_kitchen_knife", 1, "one physical knife is available for automatic equipment supply");
    await putStock(server, stage, layout.supply, CHICKEN, 1);
    const chopped = await waitForProduction(server, stage, layout, layout.chopped, RAW_MEAT, 3, CHICKEN);
    evidence.chopped = chopped;
    expect(chopped.boardInputSeen, "a resident must load the real board through the planner").toBe(true);
    await tick.sprint(server, 120);
    const afterChop = await inventory(server, stage, containers);
    evidence.afterChop = afterChop;
    expectCount(afterChop, CHICKEN, 0, "one chicken is actually spent");
    expectCount(afterChop, RAW_MEAT, 3, "one chicken yields exactly three meat pieces");
    expectCount(afterChop, BONE, 1, "the secondary bone is preserved exactly once");
    expectCount(afterChop, COOKERY + "iron_kitchen_knife", 1, "the same knife remains available");
    expect(await world.probe(server, `if block ${block(layout.board)} ${COOKERY}chopping_board unless data block ${block(layout.board)} CurrentCutStack.id`),
      "the board is empty after delivering every product").toBe(true);

    evidence.riceOrder = await setMaintainDemandViaUI(server, client, playerName, layout.rice, COOKED_RICE, 3, stage);
    const beforeRice = await inventory(server, stage, containers);
    evidence.beforeRice = beforeRice;
    expectCount(beforeRice, COOKED_RICE, 0, "the rice selector must not inject the ordered product");
    await putStock(server, stage, layout.supply, RICE, 6);
    await putStock(server, stage, layout.supply, WATER, 1);
    await putStock(server, stage, layout.supply, WATER, 1);
    await putStock(server, stage, layout.supply, LID, 1);
    await putStock(server, stage, layout.supply, BOWL, 2);
    await tick.sprint(server, NO_BOWLS_TICKS);
    const shortOfBowls = await inventory(server, stage, containers);
    evidence.shortOfBowls = shortOfBowls;
    expectCount(shortOfBowls, COOKED_RICE, 0, "a three-serving recipe cannot start with two bowls");
    expectCount(shortOfBowls, RICE, 6, "missing carriers do not half-spend the grain");
    expectCount(shortOfBowls, WATER, 2, "missing carriers do not half-spend the water");
    expectCount(shortOfBowls, BOWL, 2, "missing carriers remain accounted for");
    expect(await world.probe(server, `if data block ${block(layout.pot)} {Status:0}`),
      "the empty stockpot waits until all carriers are available").toBe(true);
    await putStock(server, stage, layout.supply, BOWL, 1);
    evidence.firstRice = await waitForProduction(server, stage, layout, layout.rice, COOKED_RICE, 3);
    await tick.sprint(server, 120);
    const firstRice = await inventory(server, stage, containers);
    evidence.afterFirstRice = firstRice;
    expectCount(firstRice, COOKED_RICE, 3, "the isolated rice_3 recipe delivers exactly three bowls");
    expectCount(firstRice, RICE, 3, "the first batch spends three grain items");
    expectCount(firstRice, WATER, 1, "the first batch spends one water bucket");
    expectCount(firstRice, BUCKET, 1, "the emptied bucket remains in logistics");
    expectCount(firstRice, BOWL, 0, "three physical bowls become three served dishes");
    expectCount(firstRice, LID, 1, "the lid is returned after the batch");

    expect(await countAt(server, stage.at(layout.riceAgain), COOKED_RICE),
      "the second order's target starts empty").toBe(0);
    evidence.secondRiceOrder = await setMaintainDemandViaUI(server, client, playerName, layout.riceAgain, COOKED_RICE, 3, stage);
    const beforeSecondRice = await inventory(server, stage, containers);
    evidence.beforeSecondRice = beforeSecondRice;
    expectCount(beforeSecondRice, COOKED_RICE, 3, "the second selector cannot add dishes to the existing three");
    await putStock(server, stage, layout.supply, BOWL, 3);
    evidence.secondRice = await waitForProduction(server, stage, layout, layout.riceAgain, COOKED_RICE, 3);
    evidence.firstRiceStillStocked = await waitForProduction(server, stage, layout, layout.rice, COOKED_RICE, 3);
    await tick.sprint(server, 120);
    const secondRice = await inventory(server, stage, containers);
    evidence.afterSecondRice = secondRice;
    expectCount(secondRice, COOKED_RICE, 6, "two batches produce six dishes in total");
    expectCount(secondRice, RICE, 0, "both batches spend the six staged grain items");
    expectCount(secondRice, WATER, 0, "both batches spend the two staged water buckets");
    expectCount(secondRice, BUCKET, 2, "both empty buckets survive");
    expectCount(secondRice, BOWL, 0, "all six bowls are in the six dishes");
    expectCount(secondRice, LID, 1, "repeating a batch does not duplicate the lid");

    // These are the upstream raw_noodles_from_raw_dough and stockpot/udon_noodle JSON recipes: one dough
    // produces three noodles; the soup consumes all three, two lettuce, two eggs, water and one bowl.
    evidence.udonOrder = await setMaintainDemandViaUI(server, client, playerName, layout.udon, UDON, 1, stage);
    const beforeUdon = await inventory(server, stage, containers);
    evidence.beforeUdon = beforeUdon;
    expectCount(beforeUdon, UDON, 0, "the soup selector must not inject a finished dish");
    expectCount(beforeUdon, RAW_NOODLES, 0, "there are no pre-made noodles to bypass the board");
    await putStock(server, stage, layout.supply, RAW_DOUGH, 1);
    await putStock(server, stage, layout.supply, COOKERY + "lettuce", 2);
    await putStock(server, stage, layout.supply, "minecraft:egg", 2);
    await putStock(server, stage, layout.supply, WATER, 1);
    await putStock(server, stage, layout.supply, BOWL, 1);
    const udon = await waitForProduction(server, stage, layout, layout.udon, UDON, 1, RAW_DOUGH);
    evidence.udon = udon;
    expect(udon.boardInputSeen, "the soup demand must first dispatch dough processing at the board").toBe(true);
    expect(udon.potCookingSeen, "the same chain must then cook in the real stockpot").toBe(true);
    await tick.sprint(server, 120);
    const final = await inventory(server, stage, containers);
    evidence.final = final;
    expectCount(final, UDON, 1, "the entire chain produces one real soup");
    for (const item of [RAW_DOUGH, RAW_NOODLES, COOKERY + "lettuce", "minecraft:egg", WATER, BOWL]) {
      expectCount(final, item, 0, "the exact chain inputs are spent without leftover copies");
    }
    expectCount(final, BUCKET, 3, "every batch returns its water bucket");
    expectCount(final, LID, 1, "the three batches share one lid");
    expectCount(final, RAW_MEAT, 3, "the earlier board product remains accounted for");
    expectCount(final, BONE, 1, "the earlier secondary product remains accounted for");
    expectCount(final, COOKED_RICE, 6, "the earlier six rice dishes remain accounted for");
    expect(await world.probe(server, `if data block ${block(layout.pot)} {Status:0}`),
      "the pot is ready for another batch").toBe(true);
  } catch (error) {
    pair?.failing(error);
    evidence.failure = String(error);
    if (pair && containers.length > 0) {
      try {
        evidence.atFailure = await inventory(pair.server, stage, containers);
      } catch (snapshotError) {
        evidence.snapshotFailure = String(snapshotError);
      }
    }
    throw error;
  } finally {
    try {
      const evidencePath = testInfo.outputPath("cookery-evidence.json");
      writeFileSync(evidencePath, JSON.stringify(evidence, null, 2));
      await testInfo.attach("cookery-evidence.json", { path: evidencePath, contentType: "application/json" });
    } finally {
      await pair?.teardown(testInfo);
    }
  }
});

function kitchen(origin: Vec) {
  const at = (x: number, z: number, y = 0): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  return { supply: at(-6, 5), chopped: at(6, 4), rice: at(6, 7), riceAgain: at(6, 10), udon: at(6, 13),
    board: at(-3, 7), fire: at(0, 8), pot: at(0, 8, 1) };
}

type Kitchen = ReturnType<typeof kitchen>;
type StoredStack = { Slot?: number; id: string; count?: number };
type Inventory = { totals: Record<string, number>; chests: unknown[]; residents: unknown[]; drops: unknown[] };

async function stacksAt(server: MinecraftServer, at: Vec): Promise<StoredStack[]> {
  const container = await world.container(server, at, { dimension: "minecraft:overworld", nbt: true });
  expect(container.found, `the container at ${commandPos(at)} must be readable`).toBe(true);
  const items = (container.nbt as { Items?: StoredStack[] } | undefined)?.Items;
  expect(Array.isArray(items), `the container at ${commandPos(at)} must expose its complete Items tag`).toBe(true);
  return items!;
}

async function countAt(server: MinecraftServer, at: Vec, item: string): Promise<number> {
  return (await stacksAt(server, at)).filter((stack) => stack.id === item).reduce((sum, stack) => sum + (stack.count ?? 1), 0);
}

async function putStock(server: MinecraftServer, stage: Stage, chest: Vec, item: string, count: number) {
  const at = stage.at(chest);
  const occupied = new Set((await stacksAt(server, at)).map((stack) => stack.Slot));
  const slot = Array.from({ length: 27 }, (_, i) => 26 - i).find((candidate) => !occupied.has(candidate));
  expect(slot, `the supply chest needs an empty slot for ${item}; overwriting existing goods would invalidate accounting`).toBeDefined();
  await world.command(server, `execute unless data block ${commandPos(at)} Items[{Slot:${slot}b}]`
    + ` run item replace block ${commandPos(at)} container.${slot} with ${item} ${count}`);
}

async function inventory(server: MinecraftServer, stage: Stage, containers: readonly Vec[]): Promise<Inventory> {
  const totals: Record<string, number> = {};
  const add = (stack: { id: string; count?: number }) => {
    expect(typeof stack.id, "every sampled stack must have an item id").toBe("string");
    totals[stack.id] = (totals[stack.id] ?? 0) + (stack.count ?? 1);
  };
  const chests = await Promise.all(containers.map(async (cell) => {
    const items = await stacksAt(server, stage.at(cell));
    items.forEach(add);
    return { cell, items };
  }));
  const sampled = await world.entities(server, { dimension: "minecraft:overworld", types: [RESIDENT, ITEM], nbt: true, limit: 1000 });
  expect(sampled.limited, "material accounting must include every resident and item entity").toBe(false);
  expect(sampled.nbtTruncated, "material accounting must not use truncated NBT").not.toBe(true);
  const residents: unknown[] = [];
  const drops: unknown[] = [];
  for (const entity of sampled.entities) {
    const nbt = entity.nbt as Record<string, any> | undefined;
    if (entity.type === RESIDENT) {
      const pack = nbt?.["neoforge:attachments"]?.["folkways:body_state"]?.Pack;
      expect(Array.isArray(pack?.Items), `resident ${entity.uuid}'s pack must be readable`).toBe(true);
      for (const entry of pack.Items) add(entry.Stack);
      residents.push({ uuid: entity.uuid, position: entity.position, items: pack.Items });
    } else if (entity.type === ITEM) {
      expect(nbt?.Item, `drop ${entity.uuid}'s stack must be readable`).toBeDefined();
      add(nbt!.Item);
      drops.push({ uuid: entity.uuid, position: entity.position, stack: nbt!.Item });
    }
  }
  expect(residents.length, "the fixture must still have all its residents").toBe(RESIDENT_COUNT);
  return { totals, chests, residents, drops };
}

function expectCount(inventory: Inventory, item: string, expected: number, reason: string) {
  expect(inventory.totals[item] ?? 0, `${reason}: ${item}; sampled ${JSON.stringify(inventory)}`).toBe(expected);
}

async function waitForProduction(server: MinecraftServer, stage: Stage, layout: Kitchen, target: Vec,
  item: string, count: number, boardInput?: string) {
  let boardInputSeen = false;
  let potCookingSeen = false;
  let observed = 0;
  for (let elapsed = 0; elapsed <= PRODUCTION_TICKS; elapsed += STEP_TICKS) {
    const [stock, board, pot] = await Promise.all([
      countAt(server, stage.at(target), item),
      boardInput ? world.probe(server,
        `if data block ${commandPos(stage.at(layout.board))} {CurrentCutStack:{id:"${boardInput}"}}`) : false,
      world.probe(server, `if data block ${commandPos(stage.at(layout.pot))} {Status:2}`),
    ]);
    observed = stock;
    boardInputSeen ||= board;
    potCookingSeen ||= pot;
    if (stock >= count) return { elapsed, stock, boardInputSeen, potCookingSeen };
    await tick.sprint(server, STEP_TICKS);
  }
  throw new Error(`${item} never reached ${count} in its ordered chest within ${PRODUCTION_TICKS} ticks`
    + ` (observed=${observed}, boardInputSeen=${boardInputSeen}, potCookingSeen=${potCookingSeen})`);
}
