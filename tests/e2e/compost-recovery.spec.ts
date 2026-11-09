import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { defineTask, javaTask, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { clickElement, scrollElementIntoView } from "../ldlib2";
import { settlePanel, toggleCell, typeCell } from "../shared/ui/site-panel";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededServer } from "../shared/harness/mod-settings";
import { tracePath } from "../shared/harness/out-paths";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Stage, type Vec } from "../shared/harness/stage";

const BONE = "minecraft:bone_meal";
const SEED = "minecraft:wheat_seeds";
type Stack = { id: string; count: number };
type State = { tick: number; fill: number; activeFeed: boolean; activeId: string; awaitingSeed: boolean; stores: Stack[]; packs: Stack[]; drops: Stack[]; withdrawn: Stack[];
  residents: Array<{ id: string; hat: boolean }>;
  automatic: boolean; defaultReserve: number; wheatReserve: number; discovered: string[];
  seedBudget: { complete: boolean; physical: number; compostable: number } };
type Cells = { supply: Vec; compost: Vec; withdrawn: Vec };

for (const stage of twins()) test(stage.title("compost recovery: discover player-filled bone meal without seed stock, then fill and store a resident batch"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const runId = newRunId(); let pair: E2EPair | undefined; let cells: Cells | undefined;
  const evidence: Record<string, any> = { stage: stage.kind, samples: [], timings: [], fixtureChanges: [] };
  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("compost-recovery-server-" + runId)), instance: seededServer(stage.name("compost-recovery-server-" + runId)) },
      client: { trace: tracePath("e2e", stage.name("compost-recovery-client-" + runId)), instance: stage.name("compost-recovery-client-" + runId) },
      snapshot: () => ({ containers: cells ? [stage.at(cells.supply), stage.at(cells.withdrawn)] : [], entityTypes: ["folkways_vanilla:resident", "minecraft:item"] }),
    });
    const { server, client } = pair; const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    const box = foundingBox(origin); await stage.lay(server, box);
    const block = (cell: Vec) => commandPos(stage.at(cell));
    await world.command(server, "fill " + block({ ...box.min, y: origin.y - 1 }) + " " + block({ ...box.max, y: origin.y - 1 }) + " minecraft:smooth_stone");
    const founded = await foundColonyFast(server, client, player.name, { origin, residentCount: 1, stage });
    evidence.founding = founded.evidence;
    for (const command of ["gamerule doDaylightCycle false", "gamerule doMobSpawning false", "gamerule randomTickSpeed 0", "difficulty peaceful", "time set day", "weather clear"]) await world.command(server, command);
    cells = { supply: { x: origin.x - 12, y: origin.y, z: origin.z - 11 }, compost: { x: origin.x + 11, y: origin.y, z: origin.z + 10 }, withdrawn: { x: origin.x - 12, y: origin.y, z: origin.z + 12 } };
    await world.command(server, "setblock " + block(cells.supply) + " minecraft:chest{Items:[]}");
    await world.command(server, "setblock " + block(cells.compost) + " minecraft:composter");
    await world.command(server, "setblock " + block(cells.withdrawn) + " minecraft:chest{Items:[]}");
    evidence.supply = await optInViaBook(server, client, player.name, cells.supply, "chest", stage);
    // Explicit input fixture: the real player's held wheat seeds enter insertItem, one at a time.
    // No LEVEL write, resident task call, bone-meal grant or inventory delivery manufactures the result.
    await screen.dismiss(client); await world.command(server, "gamemode survival " + player.name);
    await world.command(server, "item replace entity " + player.name + " weapon.mainhand with " + SEED + " 64");
    evidence.playerInput = await runColonyTask(server, PLAYER_FILL, { player: player.name, compost: stage.at(cells.compost) });
    expect(evidence.playerInput.fill).toBe(7); expect(evidence.playerInput.spent).toBeGreaterThanOrEqual(7);
    await world.command(server, "item replace entity " + player.name + " weapon.mainhand with folkways_core:colony_book");
    evidence.fixtureChanges.push("player's leftover input seeds replaced by the colony book after native prefill; no colony seed stock was supplied");
    await tick.sprint(server, 25);
    const mature = await read(server, stage, cells); evidence.playerMature = mature;
    expect(mature.fill, "only the original scheduled block tick matures seven into eight").toBe(8);
    expect(total(mature, BONE)).toBe(0); expect(total(mature, SEED)).toBe(0);
    expect(mature.seedBudget.compostable, "collection must work without any surplus seed allowance").toBe(0);
    evidence.compostMark = await optInViaBook(server, client, player.name, cells.compost, "worksite", stage);
    await screen.dismiss(client);
    const collected = await until(server, stage, cells, evidence, "player batch: autonomous discovery, native extraction and warehouse delivery", sample => sample.fill === 0 && count(sample.stores, BONE) === 1 && count(sample.packs, BONE) === 0, 5000);
    expect(total(collected, BONE), "one real mature batch creates exactly one physical bone meal").toBe(1);
    expect(count(collected.drops, BONE)).toBe(0);
    await tick.sprint(server, 120); evidence.playerStable = await read(server, stage, cells);
    expect(total(evidence.playerStable, BONE), "repeated forty-tick sweeps cannot collect a finished batch twice").toBe(1);
    expect(evidence.playerStable.fill).toBe(0);

    await openCompostSettings(server, client, player.name, founded.standBlock, stage);
    expect((await read(server, stage, cells)).automatic).toBe(true);
    evidence.automaticOff = await toggleCell(server, client, "folkways.settings.compost.enabled");
    expect((await read(server, stage, cells)).automatic).toBe(false);
    await closeCompostSettings(server, client);

    // A second batch starts at zero and receives only warehouse seeds, without any hat.
    // There is no grass zone, so the product must reach storage rather than disappear into grass restoration.
    for (let slot = 20; slot < 24; slot++) await world.command(server, "item replace block " + block(cells.supply) + " container." + slot + " with " + SEED + " 64");
    evidence.residentInput = await read(server, stage, cells);
    expect(evidence.residentInput.fill).toBe(0); expect(total(evidence.residentInput, SEED)).toBe(256);
    await openCompostSettings(server, client, player.name, founded.standBlock, stage);
    const discovered = (await read(server, stage, cells)).discovered;
    expect(discovered, "configuration rows come from the colony's actual seeds").toEqual([SEED]);
    const wheatRow = "folkways.settings.compost.reserve." + discovered.indexOf(SEED);
    expect(evidence.residentInput.defaultReserve, "new colonies retain a stack by default").toBe(64);
    evidence.defaultReserve80 = await typeCell(server, client, "folkways.settings.compost.default_reserve", "80");
    expect((await read(server, stage, cells)).defaultReserve).toBe(80);
    expect((await read(server, stage, cells)).wheatReserve, "an unconfigured seed inherits the default").toBe(80);
    evidence.wheatReserve106 = await typeCell(server, client, wheatRow, "106");
    expect((await read(server, stage, cells)).wheatReserve).toBe(106);
    evidence.defaultReserve96 = await typeCell(server, client, "folkways.settings.compost.default_reserve", "96");
    expect((await read(server, stage, cells)).defaultReserve).toBe(96);
    expect((await read(server, stage, cells)).wheatReserve, "individual seed settings survive a change to the default").toBe(106);
    await scrollElementIntoView(client, { id: "folkways.settings.compost.reset.0" });
    evidence.wheatUseDefault = await clickElement(client, { id: "folkways.settings.compost.reset.0" });
    await settlePanel(server, client);
    expect((await read(server, stage, cells)).wheatReserve, "reset restores inheritance").toBe(96);
    evidence.restoreDefault = await typeCell(server, client, "folkways.settings.compost.default_reserve", "64");
    const restored = await read(server, stage, cells);
    expect(restored.defaultReserve).toBe(64);
    expect(restored.wheatReserve, "reset seeds inherit the restored default before resident composting").toBe(64);
    await closeCompostSettings(server, client);
    await tick.sprint(server, 200);
    const disabled = await read(server, stage, cells); evidence.automaticDisabled = disabled;
    expect(disabled.fill, "the colony switch prevents automatic input even with surplus seeds").toBe(0);
    expect(total(disabled, SEED)).toBe(256);
    await openCompostSettings(server, client, player.name, founded.standBlock, stage);
    evidence.automaticOn = await toggleCell(server, client, "folkways.settings.compost.enabled");
    expect((await read(server, stage, cells)).automatic).toBe(true);
    await closeCompostSettings(server, client);
    const levels = new Set<number>(); let previous = 0; let completed = 0;
    const residentBatch = await until(server, stage, cells, evidence, "resident batch: average-cost filling without hats, native maturity, extraction and storage", sample => {
      levels.add(sample.fill); if (previous === 8 && sample.fill === 0) completed++;
      previous = sample.fill;
      expect([0, 7, 8], "a resident fills the entire batch in one action").toContain(sample.fill);
      expect((256 - total(sample, SEED)) % 21, "every empty composter batch consumes exactly 21 wheat seeds").toBe(0);
      expect(sample.residents.every(resident => !resident.hat), "automatic composting does not require or fabricate a straw hat").toBe(true);
      expect(total(sample, SEED), "the standing seed reserve survives average-cost composting").toBeGreaterThanOrEqual(64);
      expect(total(sample, BONE), "every observed native extraction produces exactly one physical item").toBe(1 + completed);
      return completed > 0 && count(sample.stores, BONE) >= 2 && count(sample.packs, BONE) === 0;
    }, 12000);
    evidence.levels = [...levels]; evidence.completed = completed;
    expect(levels.has(7), "average-cost resident input reaches the maturing state").toBe(true);
    expect(levels.has(8), "native scheduled ticking reaches collectable compost").toBe(true);
    expect(total(residentBatch, SEED)).toBeLessThan(256);
    expect(total(residentBatch, BONE)).toBe(1 + completed);
    expect(count(residentBatch.drops, BONE)).toBe(0);
    // Interruption fixture: let the real next feed be planned before the player fills the same barrel.
    // The original warehouse seeds are transferred to an unregistered chest, never deleted or duplicated.
    await world.command(server, "item replace entity " + player.name + " weapon.mainhand with " + SEED + " 64");
    const queued = await until(server, stage, cells, evidence, "observe an actually planned feed awaiting its physical seed", sample => sample.activeFeed && sample.awaitingSeed && sample.fill < 7 && count(sample.packs, SEED) === 0, 3000);
    evidence.interruptedFeed = queued.activeId;
    const boneBefore = total(queued, BONE); const seedsBefore = total(queued, SEED);
    evidence.interruption = await runColonyTask(server, FILL_AND_WITHDRAW, { player: player.name, compost: stage.at(cells.compost), withdrawn: stage.at(cells.withdrawn) });
    expect(evidence.interruption.fill).toBe(7); expect(evidence.interruption.withdrawnSeeds).toBe(seedsBefore);
    evidence.fixtureChanges.push("after a real feed was planned, move warehouse seeds to an unregistered physical isolation chest and use the player's held seeds to natively fill to seven in one explicit interruption");
    await tick.sprint(server, 25);
    const interrupted = await read(server, stage, cells); evidence.afterInterruption = interrupted;
    expect(interrupted.fill, "the player's accepted native batch matures even though the original feed input is no longer available").toBe(8);
    expect(total(interrupted, SEED)).toBe(0); expect(count(interrupted.withdrawn, SEED)).toBe(seedsBefore);
    const recovered = await until(server, stage, cells, evidence, "an obsolete input-dependent feed must not hide a mature input-free collection", sample => {
      expect(total(sample, SEED)).toBe(0); expect(count(sample.withdrawn, SEED), "unregistered stock stays physically conserved outside colony logistics").toBe(seedsBefore);
      expect(total(sample, BONE)).toBeLessThanOrEqual(boneBefore + 1);
      return sample.fill === 0 && count(sample.stores, BONE) === boneBefore + 1 && count(sample.packs, BONE) === 0;
    }, 3000);
    expect(total(recovered, BONE)).toBe(boneBefore + 1); expect(recovered.drops.filter(item => item.id === BONE)).toEqual([]);
    await tick.sprint(server, 120); evidence.interruptionStable = await read(server, stage, cells);
    expect(total(evidence.interruptionStable, BONE)).toBe(boneBefore + 1);
    expect(count(evidence.interruptionStable.withdrawn, SEED)).toBe(seedsBefore);
  } catch (error) { pair?.failing(error); evidence.failure = String(error); throw error; }
  finally {
    if (pair && cells) evidence.atEnd = await read(pair.server, stage, cells).catch(error => ({ error: String(error) }));
    const file = testInfo.outputPath("compost-recovery-evidence.json"); writeFileSync(file, JSON.stringify(evidence, null, 2));
    await testInfo.attach("compost-recovery-evidence", { path: file, contentType: "application/json" }); await pair?.teardown(testInfo);
  }
});

async function openCompostSettings(server: MinecraftServer, client: MinecraftClient, player: string, stand: Vec, stage: Stage) {
  await openColonyPanelViaBook(server, client, player, stand, stage);
  await scrollElementIntoView(client, { id: "folkways.page.compost_settings" });
  await clickElement(client, { id: "folkways.page.compost_settings" });
  await settlePanel(server, client);
}

async function closeCompostSettings(server: MinecraftServer, client: MinecraftClient) {
  await screen.dismiss(client);
}

function count(stacks: Stack[], id: string): number { return stacks.reduce((sum, item) => sum + (item.id === id ? item.count : 0), 0); }
function total(state: State, id: string): number { return count(state.stores, id) + count(state.packs, id) + count(state.drops, id); }
async function read(server: MinecraftServer, stage: Stage, cells: Cells): Promise<State> {
  return server.run(READ_COMPOST, { compost: stage.at(cells.compost), withdrawn: stage.at(cells.withdrawn) });
}
async function until(server: MinecraftServer, stage: Stage, cells: Cells, evidence: Record<string, any>, phase: string, ready: (state: State) => boolean, budget: number): Promise<State> {
  let last: State | undefined;
  const timing: { phase: string; firstMatureTick?: number; collectedTick?: number; storedTick?: number } = { phase };
  evidence.timings.push(timing);
  for (let elapsed = 0; elapsed <= budget;) {
    last = await read(server, stage, cells); evidence.samples.push({ phase, elapsed, state: last });
    if (last.fill === 8 && timing.firstMatureTick === undefined) timing.firstMatureTick = last.tick;
    if (timing.firstMatureTick !== undefined) {
      if (last.fill === 0 && timing.collectedTick === undefined) timing.collectedTick = last.tick;
      expect(last.tick - timing.firstMatureTick,
        phase + ": one free farmer in the open fixture must collect and store a mature batch within 1200 real world ticks").toBeLessThanOrEqual(1200);
    }
    if (ready(last)) {
      if (timing.firstMatureTick !== undefined) timing.storedTick = last.tick;
      return last;
    }
    // Once native maturity has actually been observed, run a whole forty-tick sweep without
    // freezing between its phases. Repeated short frozen sprints can miss server-clock phases;
    // this fixture correction does not establish a fix for a normally running player's colony.
    // Keep the fine samples around native levels six/seven so neither seven nor eight is skipped.
    const step = last.fill === 8 ? 40 : last.fill >= 6 ? 2 : 10;
    await tick.sprint(server, step); elapsed += step;
  }
  throw new Error("Timed out: " + phase + "; final state=" + JSON.stringify(last));
}

const PLAYER_FILL = defineTask<{ player: string; compost: Vec }, { fill: number; spent: number }>({
  name: "folkways.e2e.native-player-compost-input", side: "server", timeoutMs: 20000,
  source: javaTask({ imports: ["net.minecraft.core.BlockPos", "net.minecraft.world.item.Items", "net.minecraft.world.level.block.ComposterBlock"], body: `
var args=Args.of(ctx);var at=(Map<?,?>)args.value("compost");var cell=new BlockPos(((Number)at.get("x")).intValue(),((Number)at.get("y")).intValue(),((Number)at.get("z")).intValue());
var player=ctx.server().getPlayerList().getPlayerByName(args.string("player"));var level=ctx.server().overworld();var input=player.getMainHandItem();
if(!input.is(Items.WHEAT_SEEDS)||input.getCount()!=64)throw new IllegalStateException("prefill needs exactly 64 actual held wheat seeds");
int before=input.getCount();while(!input.isEmpty()&&level.getBlockState(cell).getValue(ComposterBlock.LEVEL)<7)ComposterBlock.insertItem(player,level.getBlockState(cell),level,input,cell);
return Map.of("fill",level.getBlockState(cell).getValue(ComposterBlock.LEVEL),"spent",before-input.getCount());
` }),
});

// Explicit interruption, separate from the read-only observer: withdraw the old physical input into
// a real unregistered chest, then let the player's actual held seeds enter the native composter.
const FILL_AND_WITHDRAW = defineTask<{ player: string; compost: Vec; withdrawn: Vec }, { fill: number; withdrawnSeeds: number; spent: number }>({
  name: "folkways.e2e.interrupt-compost-feed-with-player-batch", side: "server", timeoutMs: 20000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.core.resident.api.Bodies",
    "io.github.izakyl.folkways.core.item.api.Containers", "net.minecraft.core.BlockPos", "net.minecraft.world.Container",
    "net.minecraft.world.item.Items", "net.minecraft.world.level.block.ComposterBlock",
  ], members: `
private static BlockPos point(Object raw){var at=(Map<?,?>)raw;return new BlockPos(((Number)at.get("x")).intValue(),((Number)at.get("y")).intValue(),((Number)at.get("z")).intValue());}
`, body: `
var args=Args.of(ctx);var level=ctx.server().overworld();var colony=Colonies.all(ctx.server()).getFirst();var cell=point(args.value("compost"));
var player=ctx.server().getPlayerList().getPlayerByName(args.string("player"));var input=player.getMainHandItem();
if(!input.is(Items.WHEAT_SEEDS)||input.getCount()!=64)throw new IllegalStateException("interruption needs the player's actual held 64 wheat seeds");
if(!(level.getBlockEntity(point(args.value("withdrawn"))) instanceof Container isolated)||!isolated.isEmpty())throw new IllegalStateException("interruption needs an empty physical isolation chest");
if(colony.claimAt(io.github.izakyl.folkways.core.world.api.WorldPos.of(level,point(args.value("withdrawn")))).isPresent())throw new IllegalStateException("isolation chest must stay outside colony logistics");
for(var resident:colony.residents()){
 var body=Bodies.of(level.getEntity(resident.id())).orElseThrow();
 for(var stack:body.pack().contents())if(stack.is(Items.WHEAT_SEEDS))throw new IllegalStateException("the planned feed already fetched its seed; the fixture must observe the earlier input-waiting stage");
}
int removed=0;
for(var at:colony.storesIn(level.dimension())){
 var source=colony.store(level,at).orElseThrow();
 for(int slot=0;slot<source.getContainerSize();slot++)if(source.getItem(slot).is(Items.WHEAT_SEEDS)){
  var moved=source.removeItemNoUpdate(slot);removed+=moved.getCount();
  if(!Containers.insert(isolated,moved).isEmpty())throw new IllegalStateException("physical isolation chest unexpectedly full");
 }
 source.setChanged();
}
isolated.setChanged();int before=input.getCount();
while(!input.isEmpty()&&level.getBlockState(cell).getValue(ComposterBlock.LEVEL)<7)ComposterBlock.insertItem(player,level.getBlockState(cell),level,input,cell);
return Map.of("fill",level.getBlockState(cell).getValue(ComposterBlock.LEVEL),"withdrawnSeeds",removed,"spent",before-input.getCount());
` }),
});
// Observation only. This never requests, commits, cancels, moves or supplies resident work.
const READ_COMPOST = defineTask<{ compost: Vec; withdrawn: Vec }, State>({
  name: "folkways.e2e.read-autonomous-compost", side: "server", timeoutMs: 20000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.core.resident.api.Bodies",
    "io.github.izakyl.folkways.core.world.api.WorldPos", "io.github.izakyl.folkways.vanilla.compost.CompostPresence",
    "io.github.izakyl.folkways.vanilla.compost.CompostSettings", "io.github.izakyl.folkways.vanilla.compost.CompostContent", "io.github.izakyl.folkways.core.work.api.Node", "net.minecraft.world.Container",
    "net.minecraft.world.entity.EquipmentSlot",
    "net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.world.item.ItemStack",
    "net.minecraft.world.item.Items", "net.minecraft.world.entity.item.ItemEntity", "net.minecraft.world.level.block.ComposterBlock",
  ], members: `
private static Map<String,Object> item(ItemStack stack){return Map.of("id",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount());}
`, body: `
var level=ctx.server().overworld();var args=Args.of(ctx);var raw=(Map<?,?>)args.value("compost");var cell=new BlockPos(((Number)raw.get("x")).intValue(),((Number)raw.get("y")).intValue(),((Number)raw.get("z")).intValue());
var colony=Colonies.all(ctx.server()).getFirst();var stores=new ArrayList<Object>();var packs=new ArrayList<Object>();var drops=new ArrayList<Object>();var residents=new ArrayList<Object>();
for(var at:colony.storesIn(level.dimension())){var store=colony.store(level,at).orElseThrow();for(int slot=0;slot<store.getContainerSize();slot++)if(!store.getItem(slot).isEmpty())stores.add(item(store.getItem(slot)));}
for(var resident:colony.residents()){
 var entity=level.getEntity(resident.id());if(entity==null)throw new IllegalStateException("colony resident is not loaded");var body=Bodies.of(entity).orElseThrow();
 for(var stack:body.pack().contents())if(!stack.isEmpty())packs.add(item(stack));
 residents.add(Map.of("id",resident.id().toString(),"hat",!body.mob().getItemBySlot(EquipmentSlot.HEAD).isEmpty()));
}
for(var entity:level.getAllEntities())if(entity instanceof ItemEntity drop&&!drop.getItem().isEmpty())drops.add(item(drop.getItem()));
var support=colony.service(CompostContent.ID,CompostPresence.class).orElseThrow();var budget=support.budget(level);var wheat=budget.tally(Items.WHEAT_SEEDS);
var detached=(Map<?,?>)args.value("withdrawn");var isolatedAt=new BlockPos(((Number)detached.get("x")).intValue(),((Number)detached.get("y")).intValue(),((Number)detached.get("z")).intValue());
var withdrawn=new ArrayList<Object>();if(!(level.getBlockEntity(isolatedAt) instanceof Container isolated))throw new IllegalStateException("missing physical isolation chest");
for(int slot=0;slot<isolated.getContainerSize();slot++)if(!isolated.getItem(slot).isEmpty())withdrawn.add(item(isolated.getItem(slot)));
boolean activeFeed=false;String activeId="";var provider=support.station();
var activeField=provider.getClass().getDeclaredField("active");activeField.setAccessible(true);
for(var value:((Map<?,?>)activeField.get(provider)).values())if(value instanceof Node node&&node.spec().site().where().equals(WorldPos.of(level,cell))){activeFeed=!node.spec().needs().isEmpty();activeId=node.id().toString();}
var out=new LinkedHashMap<String,Object>();out.put("tick",level.getGameTime());out.put("fill",level.getBlockState(cell).getValue(ComposterBlock.LEVEL));
out.put("stores",stores);out.put("packs",packs);out.put("drops",drops);out.put("residents",residents);out.put("withdrawn",withdrawn);out.put("activeFeed",activeFeed);out.put("activeId",activeId);boolean awaitingSeed=false;for(var claim:colony.reservedInputs(level))if(claim.node().toString().equals(activeId))awaitingSeed=true;out.put("awaitingSeed",awaitingSeed);
out.put("automatic",CompostSettings.enabled(colony));out.put("defaultReserve",CompostSettings.defaultReserve(colony));out.put("wheatReserve",CompostSettings.reserve(colony,Items.WHEAT_SEEDS));out.put("discovered",CompostSettings.discovered(colony,ctx.server()).stream().map(item->BuiltInRegistries.ITEM.getKey(item).toString()).toList());
out.put("seedBudget",Map.of("complete",budget.complete(),"physical",wheat.physical(),"compostable",wheat.compostable()));return out;
` }),
});
