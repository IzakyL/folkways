import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { defineTask, javaTask, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement } from "../ldlib2";
import { foundColonyFast, foundingBox, openColonyPanelViaBook, optInViaBook, vocationPriority, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { deliverOrderViaSitePanel } from "../shared/colony/membership-optin";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { scrollCellIntoView, wheelCell } from "../shared/ui/site-panel";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededServer } from "../shared/harness/mod-settings";
import { tracePath } from "../shared/harness/out-paths";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Stage, type Vec } from "../shared/harness/stage";

const K = "kaleidoscope_cookery:";
const WHEAT = "minecraft:wheat";
const FLOUR = K + "flour";
const PLANKS = "minecraft:oak_planks";
const CRAFTING = "folkways:crafting";

for (const stage of twins()) test(stage.title("millstone interruption: release, walk clear and resume the accepted native batch"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const runId = newRunId();
  const evidence: Record<string, any> = { stage: stage.kind, samples: [], interruption: "crafting disabled and restored through the colony book" };
  let pair: E2EPair | undefined;
  let cells: ReturnType<typeof layout> | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#" + (stage.kind === "ground" ? "cookery" : "cookery-sable"),
      server: { trace: tracePath("e2e", stage.name("millstone-server")), instance: seededServer(stage.name("millstone-server-" + runId)) },
      client: { trace: tracePath("e2e", stage.name("millstone-client")), instance: stage.name("millstone-client-" + runId) },
      snapshot: () => ({ containers: cells ? [stage.at(cells.supply), stage.at(cells.flour), stage.at(cells.next), stage.at(cells.mill)] : [], entityTypes: ["folkways:resident", "minecraft:item"] }),
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    const box = foundingBox(origin); await stage.lay(server, box);
    await world.command(server, "fill " + commandPos(stage.at({ ...box.min, y: origin.y - 1 })) + " "
      + commandPos(stage.at({ ...box.max, y: origin.y - 1 })) + " minecraft:smooth_stone");
    evidence.founding = (await foundColonyFast(server, client, player.name, { origin, residentCount: 1, stage })).evidence;
    for (const command of ["gamerule doDaylightCycle false", "gamerule doMobSpawning false", "difficulty peaceful", "time set day"]) await world.command(server, command);
    cells = layout(origin); const block = (cell: Vec) => commandPos(stage.at(cell));
    for (const chest of [cells.supply, cells.flour, cells.next]) await world.command(server, "setblock " + block(chest) + " minecraft:chest{Items:[]}");
    const parts = [[-1,-1,"left_up"],[0,-1,"up"],[1,-1,"right_up"],[-1,0,"left_center"],[0,0,"center"],[1,0,"right_center"],[-1,1,"left_down"],[0,1,"down"],[1,1,"right_down"]] as const;
    for (const [x, z, part] of parts) await world.command(server, "setblock " + block({ x: cells.mill.x + x, y: cells.mill.y, z: cells.mill.z + z }) + " " + K + "millstone[part=" + part + ",facing=north]");
    await world.command(server, "item replace block " + block(cells.supply) + " container.0 with " + WHEAT + " 3");
    await world.command(server, "item replace block " + block(cells.supply) + " container.1 with " + PLANKS + " 2");
    await tick.sprint(server, 5);
    evidence.marks = [];
    for (const at of [cells.supply, cells.flour, cells.next, cells.mill]) evidence.marks.push(await optInViaBook(server, client, player.name, at, "worksite", stage));
    await world.command(server, "gamemode survival " + player.name);
    evidence.initial = await read(server, stage, cells);
    expect(evidence.initial.residents).toHaveLength(1);
    const worker = evidence.initial.residents[0].uuid as string;
    const priority = await vocationPriority(server, worker, CRAFTING);
    expect(priority, "the actual resident starts with the crafting vocation").toBeGreaterThan(0);
    expect(total(evidence.initial, WHEAT)).toBe(3); expect(total(evidence.initial, FLOUR)).toBe(0);
    evidence.flourOrder = await deliverOrderViaSitePanel(server, client, player.name, cells.flour, FLOUR, 3, stage);
    await screen.dismiss(client);
    const grinding = await until(server, stage, cells, evidence, "native binding", state => state.bound && state.input?.id === WHEAT && state.input.count > 0, 4000, 10);
    expect(grinding.boundUuid, "the saved native EntityId identifies the actual sole resident").toBe(worker);
    expect(grinding.progress, "interruption happens during the real countdown, before any mature product").toBeGreaterThan(40);
    expect(grinding.residents[0].bound, "native isBoundTo agrees with the persisted non-NIL EntityId").toBe(true);
    expect(total(grinding, WHEAT)).toBe(3); expect(total(grinding, FLOUR)).toBe(0);
    evidence.beforeCancel = grinding;
    const accepted = grinding.input.count as number;
    evidence.cancelVocation = await crafting(server, client, player.name, cells.supply, stage, worker, 0);
    const released = await until(server, stage, cells, evidence, "physical cancellation exit", state => !state.bound
      && !state.residents[0].exiting && state.residents[0].outside && state.residents[0].walked > grinding.residents[0].walked + 0.05, 700, 5);
    evidence.released = released;
    expect(released.input?.id).toBe(WHEAT); expect(released.input.count, "accepted input remains in the native mill instead of being refunded").toBe(accepted);
    expect(total(released, WHEAT)).toBe(3); expect(total(released, FLOUR)).toBe(0);
    expect(await vocationPriority(server, worker, CRAFTING)).toBe(0);
    await tick.sprint(server, 80);
    const paused = await read(server, stage, cells); evidence.paused = paused;
    expect(paused.bound, "a resident who left must not be caught again during the cancelled vocation").toBe(false);
    expect(paused.progress, "without a native motor the saved real batch does not finish by itself").toBe(released.progress);
    expect(total(paused, WHEAT)).toBe(3); expect(total(paused, FLOUR)).toBe(0);
    evidence.restoreVocation = await crafting(server, client, player.name, cells.supply, stage, worker, priority);
    const resumed = await until(server, stage, cells, evidence, "resume accepted native batch", state => state.bound
      && state.input?.id === WHEAT && state.input.count === accepted, 1500, 5);
    evidence.resumed = resumed;
    expect(resumed.progress, "recovery continues the actual saved countdown rather than reinserting raw wheat").toBeLessThanOrEqual(paused.progress);
    const finished = await until(server, stage, cells, evidence, "flour delivery", state => count(state.chests.flour, FLOUR) === 3, 7000, 20);
    evidence.finished = finished;
    expect(total(finished, WHEAT)).toBe(0); expect(total(finished, FLOUR)).toBe(3);
    expect(finished.bound, "native output delivery follows actual unbinding").toBe(false);
    expect(finished.residents[0].exiting, "the first order waits for real departure").toBe(false);
    expect(finished.drops, "native mill output is not also ejected as loose duplicates").toEqual([]);
    const beforeNext = finished.residents[0];
    evidence.nextOrder = await deliverOrderViaSitePanel(server, client, player.name, cells.next, PLANKS, 2, stage);
    const next = await until(server, stage, cells, evidence, "next task after leaving the mill", state => count(state.chests.next, PLANKS) === 2, 3000, 20);
    evidence.next = next;
    expect(next.residents[0].uuid).toBe(worker);
    expect(next.residents[0].walked, "the former native motor really walks on its next ordinary logistics task").toBeGreaterThan(beforeNext.walked + 0.5);
    expect(Math.hypot(next.residents[0].x - beforeNext.x, next.residents[0].z - beforeNext.z), "the next task moves away from the mill instead of being completed in place").toBeGreaterThan(3);
    expect(total(next, PLANKS)).toBe(2); expect(total(next, FLOUR)).toBe(3); expect(next.bound).toBe(false);
    await tick.sprint(server, 120); const stable = await read(server, stage, cells); evidence.stable = stable;
    expect(stable.bound).toBe(false); expect(stable.drops).toEqual([]); expect(total(stable, FLOUR)).toBe(3); expect(total(stable, WHEAT)).toBe(0);
  } catch (error) {
    pair?.failing(error); evidence.failure = String(error); throw error;
  } finally {
    if (pair && cells) evidence.atEnd = await read(pair.server, stage, cells).catch(error => ({ error: String(error) }));
    const file = testInfo.outputPath("millstone-interruption-evidence.json"); writeFileSync(file, JSON.stringify(evidence, null, 2));
    await testInfo.attach("millstone-interruption-evidence", { path: file, contentType: "application/json" }); await pair?.teardown(testInfo);
  }
});

function layout(origin: Vec) {
  const at = (x: number, z: number) => ({ x: origin.x + x, y: origin.y, z: origin.z + z });
  return { supply: at(-7, 5), mill: at(-2, 9), flour: at(6, 6), next: at(8, -6) };
}

async function crafting(server: MinecraftServer, client: MinecraftClient, name: string, at: Vec, stage: Stage, uuid: string, target: number) {
  const row = "folkways.resident.row." + uuid.slice(0, 8);
  const cell = "folkways.resident.vocation.folkways.crafting." + uuid.slice(0, 8);
  const evidence: Record<string, any> = { before: await vocationPriority(server, uuid, CRAFTING), target };
  try {
    await openColonyPanelViaBook(server, client, name, at, stage);
    await clickElement(client, { id: "folkways.tab.residents" }); await waitForElement(client, { id: row }, { timeoutMs: 10000 });
    await scrollCellIntoView(server, client, row, cell);
    let priority = evidence.before as number;
    if ((priority === 0) !== (target === 0)) { await clickElement(client, { id: cell }); await tick.sprint(server, 5); priority = await vocationPriority(server, uuid, CRAFTING); }
    for (let step = 0; step < 4 && priority !== target && target !== 0; step++) {
      await scrollCellIntoView(server, client, row, cell); await wheelCell(server, client, cell, target > priority ? 1 : -1);
      await tick.sprint(server, 5); priority = await vocationPriority(server, uuid, CRAFTING);
    }
    evidence.after = priority; expect(priority, "the actual book vocation control reaches the requested state").toBe(target); return evidence;
  } finally { await screen.dismiss(client); }
}

async function read(server: MinecraftServer, stage: Stage, cells: ReturnType<typeof layout>): Promise<any> {
  const native = await runColonyTask(server, MILL_READ, { mill: stage.at(cells.mill) });
  const chests = Object.fromEntries(await Promise.all(["supply", "flour", "next"].map(async key => {
    const block = await world.block(server, stage.at(cells[key as keyof typeof cells]), { nbt: true });
    return [key, (block.nbt as any)?.Items ?? []];
  })));
  return { ...native, chests };
}
async function until(server: MinecraftServer, stage: Stage, cells: ReturnType<typeof layout>, evidence: Record<string, any>, phase: string,
  ready: (state: any) => boolean, budget: number, step: number): Promise<any> {
  for (let elapsed = 0; elapsed <= budget; elapsed += step) {
    const state = await read(server, stage, cells); evidence.samples.push({ phase, elapsed, ...state });
    expect(state.residents, "the real sole resident remains observable in both coordinate frames").toHaveLength(1);
    if (ready(state)) return state; await tick.sprint(server, step);
  }
  throw new Error(phase + " did not complete in " + budget + " ticks; last state=" + JSON.stringify(evidence.samples.at(-1)));
}
function count(items: any[], id: string): number { return items.filter(stack => stack.id === id).reduce((sum, stack) => sum + Number(stack.count ?? stack.Count ?? 1), 0); }
function total(state: any, id: string): number {
  return Object.values(state.chests as Record<string, any[]>).reduce((sum, items) => sum + count(items, id), 0)
    + state.residents.reduce((sum: number, resident: any) => sum + Number(resident.goods[id] ?? 0), 0)
    + count(state.outputs, id) + count(state.drops.map((drop: any) => drop.stack), id) + (state.input?.id === id ? Number(state.input.count) : 0);
}

// Read-only: original native EntityId and Progress, actual entity positions/walk distance, inventories and drops.
// Fixture construction uses commands above; no running business state, ingredients, progress or positions are written here.
const MILL_READ = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.e2e.read-native-millstone", side: "server", timeoutMs: 20000,
  source: javaTask({ imports: [
    "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity",
    "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "io.github.izakyl.folkways.core.api.terms.WorldPos",
    "io.github.izakyl.folkways.core.api.terms.WorldSpaces",
    "net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries",
    "net.minecraft.world.entity.item.ItemEntity", "net.minecraft.Util",
  ], body: `
var level=ctx.server().overworld();var args=Args.of(ctx);var point=(Map<?,?>)args.value("mill");
var at=new BlockPos(((Number)point.get("x")).intValue(),((Number)point.get("y")).intValue(),((Number)point.get("z")).intValue());
if(!(level.getBlockEntity(at) instanceof MillstoneBlockEntity mill))throw new IllegalStateException("the native center block entity is missing at "+at);
var station=WorldPos.of(level,at);var nbt=mill.saveWithoutMetadata(level.registryAccess());
var bound=nbt.hasUUID("EntityId")?nbt.getUUID("EntityId"):Util.NIL_UUID;
var residents=new ArrayList<Object>();var drops=new ArrayList<Object>();var outputs=new ArrayList<Object>();
for(int slot=0;slot<mill.getOutputs().getSlots();slot++){var stack=mill.getOutputs().getStackInSlot(slot);if(!stack.isEmpty())outputs.add(Map.of("id",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount()));}
for(var entity:level.getAllEntities()){
  var body=Bodies.of(entity);
  if(body.isPresent()&&BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals("folkways:resident")){
    var local=WorldSpaces.relative(level,station.realm(),entity.position()).orElseThrow();
    var goods=new LinkedHashMap<String,Integer>();for(var stack:body.get().pack().contents())if(!stack.isEmpty())goods.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum);
    var row=new LinkedHashMap<String,Object>();row.put("uuid",entity.getUUID().toString());row.put("x",entity.getX());row.put("y",entity.getY());row.put("z",entity.getZ());
    row.put("local",Map.of("x",local.x,"y",local.y,"z",local.z));row.put("walked",entity.walkDist);row.put("bound",mill.isBoundTo((net.minecraft.world.entity.LivingEntity)entity));
    row.put("outside",Math.max(Math.abs((int)Math.floor(local.x)-station.cell().getX()),Math.abs((int)Math.floor(local.z)-station.cell().getZ()))>=3);
    row.put("exiting",entity.getPersistentData().contains("folkways.cookery.millstone.exit"));row.put("goods",goods);residents.add(row);
  }else if(entity instanceof ItemEntity drop&&!drop.getItem().isEmpty()){
    var stack=drop.getItem();drops.add(Map.of("uuid",entity.getUUID().toString(),"stack",Map.of("id",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount())));
  }
}
var input=mill.getInput();return Map.of("bound",!bound.equals(Util.NIL_UUID),"boundUuid",bound.toString(),"progress",nbt.getInt("Progress"),
  "input",input.isEmpty()?Map.of():Map.of("id",BuiltInRegistries.ITEM.getKey(input.getItem()).toString(),"count",input.getCount()),"outputs",outputs,"residents",residents,"drops",drops);
` }),
});
