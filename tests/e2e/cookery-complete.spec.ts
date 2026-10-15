import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { defineTask, javaTask, screen, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { deliverOrderViaSitePanel } from "../shared/colony/membership-optin";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { markSouthRack } from "../shared/cookery/rack";
import { twins, type Stage, type Vec } from "../shared/harness/stage";

const K = "kaleidoscope_cookery:";
const CASES = [
  { dish: K + "baozi_plate", depth: 5, kinds: 4,
    food: ["minecraft:wheat", "minecraft:porkchop", K + "green_chili", "minecraft:water_bucket"],
    intermediates: [K + "flour", K + "raw_dough", K + "stuffed_dough_food", K + "baozi"], knife: false, spatula: false },
  { dish: K + "udon_noodle", depth: 4, kinds: 4,
    food: ["minecraft:wheat", K + "lettuce", "minecraft:egg", "minecraft:water_bucket"],
    intermediates: [K + "flour", K + "raw_dough", K + "raw_noodles"], knife: true, spatula: false },
  { dish: K + "fish_flavored_shredded_pork_rice_bowl", depth: 3, kinds: 6,
    food: ["minecraft:wheat_seeds", K + "green_chili", K + "rice_panicle", "minecraft:brown_mushroom", "minecraft:porkchop", "minecraft:water_bucket"],
    intermediates: [K + "oil", K + "rice", K + "cooked_rice", K + "fish_flavored_shredded_pork"], knife: false, spatula: true },
] as const;
const MATERIALS = ["minecraft:oak_planks", "minecraft:iron_ingot"];

const READ_WORKERS = defineTask<void, any[]>({
  name: "folkways.cookery.complete-read-workers", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.api.resident.body.Bodies"], body: `
var rows=new ArrayList<Object>();
for(var entity:ctx.server().overworld().getAllEntities()) Bodies.of(entity).ifPresent(body->{
  var row=new LinkedHashMap<String,Object>();row.put("uuid",body.id().toString());
  row.put("doing",body.doing().map(Object::toString).orElse(""));
  row.put("balk",body.balk().map(reason->Map.of("doing",reason.doing().toString(),"why",reason.why().translationKey(),"at",reason.at())).orElse(Map.of()));
  rows.add(row);
});return rows;
` }),
});

// The objective runtime graph is produced by tests/shared/cookery/recipe-graph.mjs.
// There is one final delivery only: no orders for flour, dough, noodles, rice, stir-fry or tools.
for (const scenario of CASES) for (const stage of twins()) test(stage.title("complete cookery order: " + scenario.dish), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const runId = newRunId();
  const evidence: Record<string, any> = { scenario, stage: stage.kind, whitelist: [...scenario.food, ...MATERIALS], samples: [] };
  let pair: E2EPair | undefined;
  let cells: ReturnType<typeof kitchen> | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#" + (stage.kind === "ground" ? "cookery" : "cookery-sable"),
      server: { trace: tracePath("e2e", stage.name("complete-cookery-server-" + runId)), instance: seededServer(stage.name("complete-cookery-server-" + runId)) },
      client: { trace: tracePath("e2e", stage.name("complete-cookery-client-" + runId)), instance: stage.name("complete-cookery-client-" + runId) },
      snapshot: () => ({ containers: cells ? [stage.at(cells.target), stage.at(cells.store)] : [], entityTypes: ["folkways:resident", "minecraft:item"] }),
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const name = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const box = foundingBox(origin);
    box.max.z = Math.max(box.max.z, origin.z + 17);
    await stage.lay(server, box);
    await world.command(server, "fill " + commandPos(stage.at({ ...box.min, y: origin.y - 1 })) + " "
      + commandPos(stage.at({ ...box.max, y: origin.y - 1 })) + " minecraft:smooth_stone");
    evidence.founding = (await foundColonyFast(server, client, name, { origin, residentCount: 3, stage })).evidence;
    for (const command of ["gamerule doDaylightCycle false", "gamerule doMobSpawning false", "difficulty peaceful", "time set day"]) await world.command(server, command);
    cells = kitchen(origin);
    const block = (at: Vec) => commandPos(stage.at(at));
    for (const chest of [cells.store, cells.target]) await world.command(server, "setblock " + block(chest) + " minecraft:chest{Items:[]}");
    // Configuration is a fixture input; permission/picker/all-items/revision behavior is separately tested through the real UI.
    await world.command(server, "setblock " + block(cells.source) + " folkways:creative_supply_box{all:0b,whitelist:"
      + JSON.stringify([...scenario.food, ...MATERIALS].map(id => "i" + id)) + "}");
    await world.command(server, "setblock " + block(cells.table) + " minecraft:crafting_table");
    await world.command(server, "setblock " + block(cells.board) + " " + K + "chopping_board");
    for (const at of [cells.stockpot, cells.pot, cells.steamer]) {
      await world.command(server, "setblock " + block({ ...at, y: at.y - 1 }) + " minecraft:magma_block");
    }
    await world.command(server, "setblock " + block(cells.stockpot) + " " + K + "stockpot");
    await world.command(server, "setblock " + block(cells.pot) + " " + K + "pot");
    await world.command(server, "setblock " + block(cells.steamer) + " " + K + "steamer");
    await world.command(server, "setblock " + block(cells.rack) + " " + K + "kitchenware_racks[facing=south]");
    await world.command(server, "setblock " + block(cells.mill) + " " + K + "millstone[part=center]");
    const parts = [[-1,-1,"left_up"],[0,-1,"up"],[1,-1,"right_up"],[-1,0,"left_center"],[1,0,"right_center"],[-1,1,"left_down"],[0,1,"down"],[1,1,"right_down"]] as const;
    for (const [dx, dz, part] of parts) await world.command(server, "setblock " + block({ x: cells.mill.x + dx, y: cells.mill.y, z: cells.mill.z + dz }) + " " + K + "millstone[part=" + part + "]");
    await tick.sprint(server, 5);
    for (const at of [cells.source, cells.store, cells.target, cells.table, cells.board, cells.stockpot, cells.pot, cells.steamer, cells.mill]) {
      await optInViaBook(server, client, name, at, "worksite", stage);
    }
    evidence.rackMark = await markSouthRack(server, client, name, cells.rack, stage);
    evidence.initial = await physical(server, stage, cells);
    expect(evidence.initial.tools, "three crafters start without a knife or spatula").toEqual([]);
    const before = await world.block(server, stage.at(cells.source), { nbt: true });
    for (const ingredient of scenario.food) expect(Number((before.nbt as any).generated[ingredient] ?? 0), "no food ingredients were generated before the food order").toBe(0);
    await world.command(server, "gamemode survival " + name);
    evidence.order = await deliverOrderViaSitePanel(server, client, name, cells.target, scenario.dish, 1, stage);
    await screen.dismiss(client);
    let done = false;
    for (let elapsed = 0; elapsed <= 16000; elapsed += 40) {
      const sample = await physical(server, stage, cells);
      evidence.samples.push({ elapsed, ...sample });
      if (sample.target.some((stack: any) => stack.id === scenario.dish && Number(stack.count ?? 1) >= 1)) { evidence.elapsed = elapsed; done = true; break; }
      await tick.sprint(server, 40);
    }
    expect(done, "the only final order must complete all real intermediate tasks, with no injected tools or products").toBe(true);
    await tick.sprint(server, 240);
    evidence.final = await physical(server, stage, cells);
    const source = await world.block(server, stage.at(cells.source), { nbt: true });
    const generated = (source.nbt as any).generated as Record<string, number | string>;
    evidence.generated = generated;
    for (const item of Object.keys(generated)) expect(evidence.whitelist, "every generated item is a declared base input").toContain(item);
    for (const item of [...scenario.intermediates, scenario.dish, K + "iron_kitchen_knife", K + "kitchen_shovel", K + "stockpot_lid", "minecraft:bowl"]) {
      expect(generated[item] ?? 0, "intermediates, tools and containers are actually made: " + item).toBe(0);
    }
    for (const item of scenario.food) expect(Number(generated[item] ?? 0), "the final chain spends its required base input " + item).toBeGreaterThan(0);
    expect(evidence.final.drops, "all outputs and reusable tools are returned to colony logistics").toEqual([]);
    const expectedTool = scenario.knife ? "kitchen_knife" : scenario.spatula ? "kitchen_shovel" : undefined;
    if (expectedTool) {
    expect(evidence.final.tools.filter((stack: any) => stack.id.includes(expectedTool)).length,
      "one default shared tool serves the order, instead of one per resident").toBe(1);
    expect(evidence.final.rackTools.some((stack: any) => stack.id.includes(expectedTool)), "the reusable task tool returns to the preferred rack").toBe(true);
    expect(evidence.final.residents.every((resident: any) => resident.pack.every((entry: any) => !entry.Stack?.id?.includes(expectedTool))), "residents release their borrowed tool after work").toBe(true);
    } else expect(evidence.final.tools, "a tool-free native recipe never creates kitchen tools").toEqual([]);
    expect(evidence.final.millBound, "the millstone releases its actual driver").toBe(false);
    await tick.sprint(server, 120);
    const stable = await physical(server, stage, cells);
    expect(stable.millBound, "an exited driver must not be captured again").toBe(false);
    expect(stable.target.filter((stack: any) => stack.id === scenario.dish).reduce((sum: number, stack: any) => sum + Number(stack.count ?? 1), 0)).toBe(1);
  } catch (error) {
    pair?.failing(error); evidence.failure = String(error); throw error;
  } finally {
    if (pair && cells) evidence.atEnd = await physical(pair.server, stage, cells).catch(error => ({ error: String(error) }));
    const file = testInfo.outputPath("complete-cookery-evidence.json"); writeFileSync(file, JSON.stringify(evidence, null, 2));
    await testInfo.attach("complete-cookery-evidence", { path: file, contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

function kitchen(origin: Vec) {
  const at = (x: number, z: number, y = 0) => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  return { source: at(-6, 5), store: at(6, 5), target: at(6, 8), table: at(-3, 5), board: at(0, 5),
    stockpot: at(3, 8, 1), pot: at(3, 11, 1), steamer: at(0, 14, 1), mill: at(-3, 11), rack: at(6, 11, 1) };
}

async function physical(server: MinecraftServer, stage: Stage, cells: ReturnType<typeof kitchen>) {
  const blocks = await Promise.all(Object.entries(cells).map(async ([key, at]) => [key, await world.block(server, stage.at(at), { nbt: true })] as const));
  const state = Object.fromEntries(blocks);
  const sampled = await world.entities(server, { dimension: "minecraft:overworld", types: ["folkways:resident", "minecraft:item"], nbt: true, limit: 1000 });
  expect(sampled.limited).toBe(false); expect(sampled.nbtTruncated).not.toBe(true);
  const residents = sampled.entities.filter(entity => entity.type === "folkways:resident").map(entity => {
    const nbt = entity.nbt as any; const pack = nbt?.["neoforge:attachments"]?.["folkways:body_state"]?.Pack?.Items;
    expect(Array.isArray(pack)).toBe(true);
    return { uuid: entity.uuid, position: entity.position, pack, armor: nbt.ArmorItems };
  });
  expect(residents.length).toBe(3);
  const drops = sampled.entities.filter(entity => entity.type === "minecraft:item").map(entity => ({ uuid: entity.uuid, stack: (entity.nbt as any).Item }));
  const rackTag = state.rack.nbt as any;
  const rackTools = collectStacks(rackTag).filter(stack => stack.id?.includes("kitchen_knife") || stack.id?.includes("kitchen_shovel"));
  const tools = [...collectStacks((state.store.nbt as any)?.Items), ...collectStacks((state.target.nbt as any)?.Items), ...rackTools,
    ...residents.flatMap(resident => collectStacks(resident.pack)), ...drops.map(drop => drop.stack)]
    .filter(stack => stack?.id?.includes("kitchen_knife") || stack?.id?.includes("kitchen_shovel"));
  const millTag = state.mill.nbt as any;
  return { workers: await server.run(READ_WORKERS), state, target: (state.target.nbt as any)?.Items ?? [], residents, drops, tools, rackTools,
    millBound: Array.isArray(millTag?.EntityId) ? millTag.EntityId.some((part: number) => part !== 0) : Boolean(millTag?.EntityId) };
}

function collectStacks(value: any): any[] {
  if (!value || typeof value !== "object") return [];
  if (typeof value.id === "string" && (value.count !== undefined || value.Count !== undefined)) return [value];
  return Object.values(value).flatMap(collectStacks);
}
