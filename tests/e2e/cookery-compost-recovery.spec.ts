import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { defineTask, javaTask, screen, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
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
const HAT = "kaleidoscope_cookery:straw_hat";
type Stack = { id: string; count: number };
type State = { tick: number; fill: number; marked: boolean; activeFeed: boolean; activeId: string; awaitingSeed: boolean; stores: Stack[]; packs: Stack[]; drops: Stack[]; withdrawn: Stack[];
  residents: Array<{ id: string; walked: number; doing: string; balk: string; hat: boolean }>;
  seedBudget: { complete: boolean; physical: number; compostable: number };
  compostStats: { acceptedSeeds: number; collections: number }; stalls: string[] };
type Cells = { supply: Vec; compost: Vec; withdrawn: Vec };

for (const stage of twins()) test(stage.title("compost recovery: discover player-filled bone meal without seed stock, then fill and store a resident batch"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const runId = newRunId(); let pair: E2EPair | undefined; let cells: Cells | undefined;
  const evidence: Record<string, any> = { stage: stage.kind, samples: [], timings: [], fixtureChanges: [] };
  try {
    pair = await launchPair({
      config: "blockwright.toml#" + (stage.kind === "ground" ? "cookery" : "cookery-sable"),
      server: { trace: tracePath("e2e", stage.name("compost-recovery-server-" + runId)), instance: seededServer(stage.name("compost-recovery-server-" + runId)) },
      client: { trace: tracePath("e2e", stage.name("compost-recovery-client-" + runId)), instance: stage.name("compost-recovery-client-" + runId) },
      snapshot: () => ({ containers: cells ? [stage.at(cells.supply), stage.at(cells.withdrawn)] : [], entityTypes: ["folkways:resident", "minecraft:item"] }),
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
    await world.command(server, "item replace entity " + player.name + " weapon.mainhand with folkways:colony_book");
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
    expect(collected.compostStats.collections - mature.compostStats.collections).toBe(1);
    expect(collected.compostStats.acceptedSeeds).toBe(mature.compostStats.acceptedSeeds);
    await tick.sprint(server, 120); evidence.playerStable = await read(server, stage, cells);
    expect(total(evidence.playerStable, BONE), "repeated forty-tick sweeps cannot collect a finished batch twice").toBe(1);
    expect(evidence.playerStable.fill).toBe(0);

    // A second batch starts at zero and receives only warehouse seeds and an actual hat.
    // There is no grass zone, so the product must reach storage rather than disappear into grass restoration.
    await world.command(server, "item replace block " + block(cells.supply) + " container.26 with " + HAT + " 1");
    for (let slot = 20; slot < 24; slot++) await world.command(server, "item replace block " + block(cells.supply) + " container." + slot + " with " + SEED + " 64");
    const residentInput = await read(server, stage, cells); evidence.residentInput = residentInput;
    expect(residentInput.fill).toBe(0); expect(total(residentInput, SEED)).toBe(256);
    const initialBone = total(residentInput, BONE);
    expect(initialBone, "the resident phase starts with only the player's collected bone meal").toBe(1);
    const levels = new Set<number>(); let completed = 0;
    const assertResidentBalance = (sample: State) => {
      completed = sample.compostStats.collections - residentInput.compostStats.collections;
      const accepted = sample.compostStats.acceptedSeeds - residentInput.compostStats.acceptedSeeds;
      expect(completed, "settled native collection count cannot go backwards").toBeGreaterThanOrEqual(0);
      expect(accepted, "settled native input count cannot go backwards").toBeGreaterThanOrEqual(0);
      expect(total(sample, SEED), "the standing seed reserve survives native composting").toBeGreaterThanOrEqual(64);
      expect(256 - total(sample, SEED), "physical seed loss matches independent accepted-input receipts").toBe(accepted);
      expect(total(sample, BONE), "physical bone meal matches independent native collection receipts").toBe(initialBone + completed);
      expect(count(sample.drops, SEED), "resident input cannot spill into the world").toBe(0);
      expect(count(sample.drops, BONE), "native output cannot spill into the world").toBe(0);
    };
    const residentBatch = await until(server, stage, cells, evidence, "resident batch: native filling, maturity, extraction and storage", sample => {
      levels.add(sample.fill);
      // A batch can collect and resume feeding between observations, without an observed empty frame.
      assertResidentBalance(sample);
      return completed > 0 && count(sample.stores, BONE) >= 2 && count(sample.packs, BONE) === 0;
    }, 12000);
    evidence.levels = [...levels]; evidence.completed = completed;
    expect(levels.has(7), "native resident input reaches the maturing state").toBe(true);
    expect(levels.has(8), "native scheduled ticking reaches collectable compost").toBe(true);
    expect(total(residentBatch, SEED)).toBeLessThan(256);
    expect(total(residentBatch, BONE)).toBe(initialBone + completed);
    expect(count(residentBatch.drops, BONE)).toBe(0);
    // Interruption fixture: let the real next feed be planned before the player fills the same barrel.
    // The original warehouse seeds are transferred to an unregistered chest, never deleted or duplicated.
    await world.command(server, "item replace entity " + player.name + " weapon.mainhand with " + SEED + " 64");
    const queued = await until(server, stage, cells, evidence, "observe an actually planned feed awaiting its physical seed", sample => {
      assertResidentBalance(sample);
      return sample.activeFeed && sample.awaitingSeed && sample.fill < 7 && count(sample.packs, SEED) === 0;
    }, 3000);
    evidence.interruptedFeed = queued.activeId;
    const boneBefore = total(queued, BONE); const seedsBefore = total(queued, SEED);
    evidence.interruption = await runColonyTask(server, FILL_AND_WITHDRAW, { player: player.name, compost: stage.at(cells.compost), withdrawn: stage.at(cells.withdrawn) });
    expect(evidence.interruption.fill).toBe(7); expect(evidence.interruption.withdrawnSeeds).toBe(seedsBefore);
    evidence.fixtureChanges.push("after a real feed was planned, move warehouse seeds to an unregistered physical isolation chest and use the player's held seeds to natively fill to seven in one explicit interruption");
    await tick.sprint(server, 25);
    const interrupted = await read(server, stage, cells); evidence.afterInterruption = interrupted;
    expect(interrupted.fill, "the player's accepted native batch matures even though the original feed input is no longer available").toBe(8);
    expect(total(interrupted, SEED)).toBe(0); expect(count(interrupted.withdrawn, SEED)).toBe(seedsBefore);
    expect(interrupted.compostStats.acceptedSeeds).toBe(queued.compostStats.acceptedSeeds);
    const recovered = await until(server, stage, cells, evidence, "an obsolete input-dependent feed must not hide a mature input-free collection", sample => {
      expect(total(sample, SEED)).toBe(0); expect(count(sample.withdrawn, SEED), "unregistered stock stays physically conserved outside colony logistics").toBe(seedsBefore);
      expect(total(sample, BONE)).toBeLessThanOrEqual(boneBefore + 1);
      expect(sample.compostStats.acceptedSeeds, "the obsolete resident feed cannot consume withdrawn seed stock").toBe(queued.compostStats.acceptedSeeds);
      expect(total(sample, BONE)).toBe(boneBefore + sample.compostStats.collections - queued.compostStats.collections);
      return sample.fill === 0 && count(sample.stores, BONE) === boneBefore + 1 && count(sample.packs, BONE) === 0;
    }, 3000);
    expect(total(recovered, BONE)).toBe(boneBefore + 1); expect(recovered.drops.filter(item => item.id === BONE)).toEqual([]);
    expect(recovered.compostStats.collections - queued.compostStats.collections).toBe(1);
    await tick.sprint(server, 120); evidence.interruptionStable = await read(server, stage, cells);
    expect(total(evidence.interruptionStable, BONE)).toBe(boneBefore + 1);
    expect(count(evidence.interruptionStable.withdrawn, SEED)).toBe(seedsBefore);
    expect(evidence.interruptionStable.compostStats.collections).toBe(recovered.compostStats.collections);
    expect(evidence.interruptionStable.compostStats.acceptedSeeds).toBe(queued.compostStats.acceptedSeeds);
  } catch (error) { pair?.failing(error); evidence.failure = String(error); throw error; }
  finally {
    if (pair && cells) evidence.atEnd = await read(pair.server, stage, cells).catch(error => ({ error: String(error) }));
    const file = testInfo.outputPath("compost-recovery-evidence.json"); writeFileSync(file, JSON.stringify(evidence, null, 2));
    await testInfo.attach("compost-recovery-evidence", { path: file, contentType: "application/json" }); await pair?.teardown(testInfo);
  }
});

function count(stacks: Stack[], id: string): number { return stacks.reduce((sum, item) => sum + (item.id === id ? item.count : 0), 0); }
function total(state: State, id: string): number { return count(state.stores, id) + count(state.packs, id) + count(state.drops, id); }
async function read(server: MinecraftServer, stage: Stage, cells: Cells): Promise<State> {
  return server.run(READ_COMPOST, { compost: stage.at(cells.compost), withdrawn: stage.at(cells.withdrawn) });
}
async function until(server: MinecraftServer, stage: Stage, cells: Cells, evidence: Record<string, any>, phase: string, ready: (state: State) => boolean, budget: number): Promise<State> {
  let last: State | undefined;
  const timing: { phase: string; firstMatureTick?: number; collectedTick?: number; storedTick?: number } = { phase };
  evidence.timings.push(timing);
  let matureCollections: number | undefined;
  for (let elapsed = 0; elapsed <= budget;) {
    last = await read(server, stage, cells); evidence.samples.push({ phase, elapsed, state: last });
    if (last.fill === 8 && timing.firstMatureTick === undefined) {
      timing.firstMatureTick = last.tick;
      matureCollections = last.compostStats.collections;
    }
    if (timing.firstMatureTick !== undefined) {
      if (matureCollections !== undefined && last.compostStats.collections > matureCollections && timing.collectedTick === undefined) timing.collectedTick = last.tick;
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
    "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "io.github.izakyl.folkways.core.api.terms.Containers", "net.minecraft.core.BlockPos", "net.minecraft.world.Container",
    "net.minecraft.world.item.Items", "net.minecraft.world.level.block.ComposterBlock",
  ], members: `
private static BlockPos point(Object raw){var at=(Map<?,?>)raw;return new BlockPos(((Number)at.get("x")).intValue(),((Number)at.get("y")).intValue(),((Number)at.get("z")).intValue());}
`, body: `
var args=Args.of(ctx);var level=ctx.server().overworld();var colony=Colonies.all(ctx.server()).getFirst();var cell=point(args.value("compost"));
var player=ctx.server().getPlayerList().getPlayerByName(args.string("player"));var input=player.getMainHandItem();
if(!input.is(Items.WHEAT_SEEDS)||input.getCount()!=64)throw new IllegalStateException("interruption needs the player's actual held 64 wheat seeds");
if(!(level.getBlockEntity(point(args.value("withdrawn"))) instanceof Container isolated)||!isolated.isEmpty())throw new IllegalStateException("interruption needs an empty physical isolation chest");
if(colony.holdingAt(io.github.izakyl.folkways.core.api.terms.WorldPos.of(level,point(args.value("withdrawn")))).isPresent())throw new IllegalStateException("isolation chest must stay outside colony logistics");
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
    "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "io.github.izakyl.folkways.core.api.terms.WorldPos", "io.github.izakyl.folkways.plugins.compost.CompostContent",
    "io.github.izakyl.folkways.plugins.compost.CompostPresence", "net.minecraft.world.Container",
    "com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod", "net.minecraft.world.entity.EquipmentSlot",
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
 residents.add(Map.of("id",resident.id().toString(),"walked",entity.walkDist,"doing",body.doing().map(Object::toString).orElse(""),"balk",body.balk().map(Object::toString).orElse(""),"hat",body.mob().getItemBySlot(EquipmentSlot.HEAD).is(TagMod.STRAW_HAT)));
}
for(var entity:level.getAllEntities())if(entity instanceof ItemEntity drop&&!drop.getItem().isEmpty())drops.add(item(drop.getItem()));
var support=colony.service(CompostContent.ID,CompostPresence.class).orElseThrow();var budget=support.budget(level);var wheat=budget.tally(Items.WHEAT_SEEDS);
var detached=(Map<?,?>)args.value("withdrawn");var isolatedAt=new BlockPos(((Number)detached.get("x")).intValue(),((Number)detached.get("y")).intValue(),((Number)detached.get("z")).intValue());
var withdrawn=new ArrayList<Object>();if(!(level.getBlockEntity(isolatedAt) instanceof Container isolated))throw new IllegalStateException("missing physical isolation chest");
for(int slot=0;slot<isolated.getContainerSize();slot++)if(!isolated.getItem(slot).isEmpty())withdrawn.add(item(isolated.getItem(slot)));
boolean activeFeed=false;String activeId="";
for(var active:support.activeSessions())for(var claim:colony.reservedInputs(level))if(claim.node().equals(active)){activeFeed=true;activeId=active.toString();}
var out=new LinkedHashMap<String,Object>();out.put("tick",level.getGameTime());out.put("fill",level.getBlockState(cell).getValue(ComposterBlock.LEVEL));out.put("marked",colony.holdingAt(WorldPos.of(level,cell)).isPresent());
out.put("stores",stores);out.put("packs",packs);out.put("drops",drops);out.put("residents",residents);out.put("withdrawn",withdrawn);out.put("activeFeed",activeFeed);out.put("activeId",activeId);boolean awaitingSeed=false;for(var claim:colony.reservedInputs(level))if(claim.node().toString().equals(activeId))awaitingSeed=true;out.put("awaitingSeed",awaitingSeed);
var stats=support.stats();out.put("compostStats",Map.of("acceptedSeeds",stats.acceptedSeeds(),"collections",stats.collections()));
out.put("seedBudget",Map.of("complete",budget.complete(),"physical",wheat.physical(),"compostable",wheat.compostable()));out.put("stalls",colony.stallsIn(level.dimension()).stream().map(Object::toString).filter(text->text.contains("compost")||text.contains("wheat_seeds")).toList());return out;
` }),
});
