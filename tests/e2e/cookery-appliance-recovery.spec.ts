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

const FLINT = "minecraft:flint_and_steel";
const CHARGE = "minecraft:fire_charge";

for (const stage of twins()) test(stage.title("stove recovery: borrow real ignition across long travel, return worn flint and wait for water and rain"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const evidence: Record<string, any> = { stage: stage.kind, samples: [], fixtureChanges: [] };
  const runId = newRunId(); let pair: E2EPair | undefined; let cells: Cells | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#" + (stage.kind === "ground" ? "cookery" : "cookery-sable"),
      server: { trace: tracePath("e2e", stage.name("stove-recovery-server-" + runId)), instance: seededServer(stage.name("stove-recovery-server-" + runId)) },
      client: { trace: tracePath("e2e", stage.name("stove-recovery-client-" + runId)), instance: stage.name("stove-recovery-client-" + runId) },
      snapshot: () => ({ containers: cells ? [stage.at(cells.supply), stage.at(cells.stove)] : [], entityTypes: ["folkways:resident", "minecraft:item"] }),
    });
    const { server, client } = pair; const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    const box = foundingBox(origin); await stage.lay(server, box);
    await world.command(server, "fill " + commandPos(stage.at({ ...box.min, y: origin.y - 1 })) + " " + commandPos(stage.at({ ...box.max, y: origin.y - 1 })) + " minecraft:smooth_stone");
    evidence.founding = (await foundColonyFast(server, client, player.name, { origin, residentCount: 1, stage })).evidence;
    for (const command of ["gamerule doDaylightCycle false", "gamerule doMobSpawning false", "difficulty peaceful", "time set day", "weather clear"]) await world.command(server, command);
    cells = { supply: { x: origin.x - 12, y: origin.y, z: origin.z - 11 }, stove: { x: origin.x + 11, y: origin.y, z: origin.z + 10 } };
    const block = (cell: Vec) => commandPos(stage.at(cell));
    await world.command(server, "setblock " + block(cells.supply) + " minecraft:chest{Items:[]}");
    await world.command(server, "setblock " + block(cells.stove) + " kaleidoscope_cookery:stove[lit=false]");
    await world.command(server, "item replace block " + block(cells.supply) + " container.0 with " + FLINT + " 1");
    evidence.supplyMark = await optInViaBook(server, client, player.name, cells.supply, "worksite", stage);
    await tick.sprint(server, 85); evidence.beforeTask = await read(server, stage, cells);
    expect(evidence.beforeTask.residents).toHaveLength(1);
    expect(quantity(evidence.beforeTask, FLINT)).toBe(1);
    expect(packCount(evidence.beforeTask, FLINT), "a stored ignition tool is not distributed to every resident before a stove needs work").toBe(0);
    expect(sourceCount(evidence.beforeTask, FLINT)).toBe(1);
    evidence.stoveMark = await optInViaBook(server, client, player.name, cells.stove, "worksite", stage);
    evidence.registered = await read(server, stage, cells);
    expect(evidence.registered.stovePoint, "the book registers the native stove as a real colony ledger point").toBe(true);
    expect(evidence.registered.stoveHasBlockEntity, "registration preserves the native stove without fabricating a block entity").toBe(false);
    await screen.dismiss(client); await world.command(server, "gamemode survival " + player.name);
    const finished = await until(server, stage, cells, evidence, "actual long-distance flint borrowing, ignition and return", state => state.lit
      && sharedCount(state, FLINT) === 1 && state.shared.some((item: any) => item.id === FLINT && item.damage === 1) && packCount(state, FLINT) === 0, 5000);
    expect(evidence.samples.some((sample: any) => sourceCount(sample.state, FLINT) === 0 && packCount(sample.state, FLINT) === 1), "the read-only samples observe the actual tool leave the warehouse and enter the resident's pack").toBe(true);
    expect(finished.residents[0].walked, "the real task walks between distant supply and stove through multiple sweeps").toBeGreaterThan(evidence.beforeTask.residents[0].walked + 8);
    expect(quantity(finished, FLINT)).toBe(1); expect(finished.drops).toEqual([]);
    // Explicit fixture change: remove the returned flint and offer two actual consumable charges.
    // The production path must now consume one charge, without inventing a returned replacement.
    // Ordinary shared tools can return to any real colony store. Observe the returned item before
    // replacing that exact physical slot, rather than assuming it returned to source slot zero.
    const returned = finished.shared.find((item: any) => item.id === FLINT);
    expect(returned).toBeDefined();
    evidence.fixtureChanges.push({ action: "replace the returned physical flint with exactly two fire charges; extinguish native stove", returned });
    await world.command(server, "item replace block " + commandPos(returned.at) + " container." + returned.slot + " with " + CHARGE + " 2");
    await world.command(server, "setblock " + block(cells.stove) + " kaleidoscope_cookery:stove[lit=false]");
    await world.command(server, "setblock " + block({ ...cells.stove, y: cells.stove.y + 1 }) + " minecraft:water");
    await tick.sprint(server, 85); evidence.wet = await read(server, stage, cells);
    expect(evidence.wet.lit).toBe(false); expect(evidence.wet.dry).toBe(false); expect(quantity(evidence.wet, CHARGE)).toBe(2);
    // Establish real rain at the world projection, including aboard where the stove's storage
    // coordinates are elsewhere. Keep the water cover until the native weather transition finishes.
    const exposed = stage.world(cells.stove);
    evidence.fixtureChanges.push({ action: "set the projected stove's precipitation biome to plains", at: exposed });
    evidence.rainBiome = await world.command(server, "fillbiome "
      + commandPos({ x: exposed.x - 4, y: exposed.y, z: exposed.z - 4 }) + " "
      + commandPos({ x: exposed.x + 4, y: exposed.y + 4, z: exposed.z + 4 }) + " minecraft:plains");
    evidence.rainCommand = await world.command(server, "weather rain 600s");
    evidence.rainStarted = await until(server, stage, cells, evidence, "the native global rain transition", state => state.globalRaining, 250);
    expect(evidence.rainStarted.precipitation, "the actual projected worksite has a rain-bearing biome").toBe("RAIN");
    await world.command(server, "setblock " + block({ ...cells.stove, y: cells.stove.y + 1 }) + " minecraft:air");
    evidence.exposedToRain = await read(server, stage, cells);
    expect(evidence.exposedToRain.canSeeSky, "the actual projected stove is exposed to the sky").toBe(true);
    expect(evidence.exposedToRain.rainingAt, "native rain reaches the stove before testing the waiting period").toBe(true);
    await tick.sprint(server, 85); evidence.raining = await read(server, stage, cells);
    expect(evidence.raining.rainingAt, "the rain boundary is exercised at the actual projected stove position").toBe(true);
    expect(evidence.raining.dry).toBe(false); expect(evidence.raining.lit).toBe(false); expect(quantity(evidence.raining, CHARGE)).toBe(2);
    evidence.clearCommand = await world.command(server, "weather clear 600s");
    const relit = await until(server, stage, cells, evidence, "one consumable charge lights the dry native stove", state => state.lit && sharedCount(state, CHARGE) === 1 && packCount(state, CHARGE) === 0, 5000);
    expect(quantity(relit, CHARGE), "the consumed charge never appears as a fake tool return").toBe(1);
    expect(packCount(relit, FLINT)).toBe(0); expect(relit.drops).toEqual([]);
    await tick.sprint(server, 120); evidence.stable = await read(server, stage, cells);
    expect(evidence.stable.lit).toBe(true); expect(quantity(evidence.stable, CHARGE)).toBe(1); expect(evidence.stable.drops).toEqual([]);
  } catch (error) { pair?.failing(error); evidence.failure = String(error); throw error; }
  finally {
    if (pair && cells) evidence.atEnd = await read(pair.server, stage, cells).catch(error => ({ error: String(error) }));
    const file = testInfo.outputPath("stove-recovery-evidence.json"); writeFileSync(file, JSON.stringify(evidence, null, 2));
    await testInfo.attach("stove-recovery-evidence", { path: file, contentType: "application/json" }); await pair?.teardown(testInfo);
  }
});

type Cells = { supply: Vec; stove: Vec };
async function read(server: MinecraftServer, stage: Stage, cells: Cells): Promise<any> {
  return runColonyTask(server, IGNITION_READ, { supply: stage.at(cells.supply), stove: stage.at(cells.stove) });
}
function sourceCount(state: any, id: string): number { return state.source.reduce((sum: number, item: any) => sum + (item.id === id ? item.count : 0), 0); }
function sharedCount(state: any, id: string): number { return state.shared.reduce((sum: number, item: any) => sum + (item.id === id ? item.count : 0), 0); }
function packCount(state: any, id: string): number { return state.residents.reduce((sum: number, resident: any) => sum + resident.pack.reduce((n: number, item: any) => n + (item.id === id ? item.count : 0), 0), 0); }
function quantity(state: any, id: string): number { return sharedCount(state, id) + packCount(state, id) + state.drops.reduce((sum: number, item: any) => sum + (item.id === id ? item.count : 0), 0); }
async function until(server: MinecraftServer, stage: Stage, cells: Cells, evidence: Record<string, any>, phase: string, ready: (state: any) => boolean, budget: number): Promise<any> {
  for (let elapsed = 0; elapsed <= budget; elapsed += 5) {
    const state = await read(server, stage, cells); evidence.samples.push({ phase, elapsed, state }); if (ready(state)) return state;
    await tick.sprint(server, 5);
  }
  throw new Error("Timed out: " + phase);
}

// Read-only world, native LIT, physical warehouse and resident packs. No task, tool, progress or
// position mutations are hidden in this observer; construction and weather changes are explicit above.
const IGNITION_READ = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.e2e.read-real-stove-ignition", side: "server", timeoutMs: 20000,
  source: javaTask({ imports: [
    "com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock",
    "io.github.izakyl.folkways.cookery.stove.StoveIgnition",
    "io.github.izakyl.folkways.cookery.CookeryContent",
    "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held",
    "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "io.github.izakyl.folkways.core.api.terms.WorldPos", "io.github.izakyl.folkways.core.api.terms.WorldSpaces",
    "net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.world.Container",
    "net.minecraft.world.item.ItemStack", "net.minecraft.world.entity.item.ItemEntity",
  ], members: `
private static BlockPos point(Map<?,?> value){return new BlockPos(((Number)value.get("x")).intValue(),((Number)value.get("y")).intValue(),((Number)value.get("z")).intValue());}
private static Map<String,Object> item(ItemStack stack){return Map.of("id",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount(),"damage",stack.getDamageValue());}
`, body: `
var level=ctx.server().overworld();var args=Args.of(ctx);var stove=point((Map<?,?>)args.value("stove"));var sourceAt=point((Map<?,?>)args.value("supply"));
if(!(level.getBlockEntity(sourceAt) instanceof Container source))throw new IllegalStateException("missing real source chest");
var items=new ArrayList<Object>();for(int slot=0;slot<source.getContainerSize();slot++)if(!source.getItem(slot).isEmpty())items.add(item(source.getItem(slot)));
var colony=Colonies.all(ctx.server()).getFirst();var shared=new ArrayList<Object>();
for(var storeAt:colony.storesIn(level.dimension())){
 var store=colony.store(level,storeAt);if(store.isEmpty())throw new IllegalStateException("unreadable real colony store: "+storeAt);
 var cell=storeAt.block(level);for(int slot=0;slot<store.get().getContainerSize();slot++){
  var stack=store.get().getItem(slot);if(stack.isEmpty())continue;var row=new LinkedHashMap<String,Object>(item(stack));
  row.put("at",Map.of("x",cell.getX(),"y",cell.getY(),"z",cell.getZ()));row.put("slot",slot);shared.add(row);
 }
}
var residents=new ArrayList<Object>();var drops=new ArrayList<Object>();
for(var entity:level.getAllEntities()){
 var body=Bodies.of(entity);
 if(body.isPresent()){
  var pack=new ArrayList<Object>();for(var stack:body.get().pack().contents())if(!stack.isEmpty())pack.add(item(stack));
  var row=new LinkedHashMap<String,Object>();row.put("uuid",entity.getUUID().toString());row.put("pack",pack);row.put("walked",entity.walkDist);row.put("doing",body.get().doing().map(Object::toString).orElse(""));
  row.put("balk",body.get().balk().map(reason->Map.of("doing",reason.doing().toString(),"why",reason.why().translationKey(),"at",reason.at())).orElse(Map.of()));residents.add(row);
 }else if(entity instanceof ItemEntity drop&&!drop.getItem().isEmpty())drops.add(item(drop.getItem()));
}
var at=WorldPos.of(level,stove);var world=WorldSpaces.world(level,at).orElseThrow();var rainAt=BlockPos.containing(world).above();
var out=new LinkedHashMap<String,Object>();out.put("lit",level.getBlockState(stove).getValue(StoveBlock.LIT));out.put("dry",StoveIgnition.dry(level,at));
out.put("rainingAt",level.isRainingAt(rainAt));out.put("globalRaining",level.isRaining());out.put("rainingFlag",level.getLevelData().isRaining());out.put("rainLevel",level.getRainLevel(1));
out.put("canSeeSky",level.canSeeSky(rainAt));out.put("heightmapY",level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,rainAt).getY());
out.put("rainAt",Map.of("x",rainAt.getX(),"y",rainAt.getY(),"z",rainAt.getZ()));
out.put("biome",level.getBiome(rainAt).unwrapKey().map(key->key.location().toString()).orElse(""));out.put("precipitation",level.getBiome(rainAt).value().getPrecipitationAt(rainAt).toString());
out.put("source",items);out.put("shared",shared);out.put("residents",residents);out.put("drops",drops);
out.put("stovePoint",colony.holdingAt(at).filter(holding->holding.owner().equals(CookeryContent.STOVE)&&holding.what() instanceof Held.Point).isPresent());
out.put("stoveHasBlockEntity",level.getBlockEntity(stove)!=null);return out;
` }),
});
