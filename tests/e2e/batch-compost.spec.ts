import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { defineTask, javaTask, screen, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { commandPos } from "../shared/harness/bw-helpers";
import { BUDGET } from "../shared/harness/budgets";
import { seededServer } from "../shared/harness/mod-settings";
import { tracePath } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Stage, type Vec } from "../shared/harness/stage";

type State = { tick: number; fill: number; seeds: number; carriedSeeds: number; bone: number;
  storedBone: number; dropped: number; walked: number; cookery: boolean; claims: Array<{ node: string; count: number }>;
  residents: Array<{ id: string; doing: string; balk: string; seeds: number }> };
const SEED = "minecraft:wheat_seeds";

for (const cookery of [false, true]) for (const stage of twins()) {
  test(stage.title(`batch compost ${cookery ? "with Cookery" : "without Cookery"}: collect without seeds, carry surplus once and preserve reserves`), async ({}, testInfo) => {
    test.setTimeout(BUDGET.heavy);
    const run = newRunId(); let pair: E2EPair | undefined;
    const evidence: Record<string, any> = { stage: stage.kind, cookery, samples: [] };
    try {
      pair = await launchPair({
        config: cookery ? "blockwright.toml#" + (stage.kind === "ground" ? "cookery" : "cookery-sable") : stage.config,
        server: { instance: seededServer(stage.name("batch-compost-server-" + run)), trace: tracePath("e2e", stage.name("batch-compost-server-" + run)) },
        client: { instance: stage.name("batch-compost-client-" + run), trace: tracePath("e2e", stage.name("batch-compost-client-" + run)) },
      });
      const { server, client } = pair;
      const player = await waitForGroundedPlayer(server);
      const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
      const box = foundingBox(origin); await stage.lay(server, box);
      const at = (cell: Vec) => commandPos(stage.at(cell));
      await world.command(server, "fill " + at({ ...box.min, y: origin.y - 1 }) + " " + at({ ...box.max, y: origin.y - 1 }) + " minecraft:smooth_stone");
      evidence.founding = (await foundColonyFast(server, client, player.name, { origin, residentCount: 1, stage })).evidence;
      for (const command of ["gamerule doDaylightCycle false", "gamerule doMobSpawning false", "gamerule randomTickSpeed 0", "difficulty peaceful", "time set day", "weather clear"]) await world.command(server, command);
      const supply = { x: origin.x - 11, y: origin.y, z: origin.z - 10 };
      const compost = { x: origin.x + 10, y: origin.y, z: origin.z + 9 };
      await world.command(server, "setblock " + at(supply) + " minecraft:chest{Items:[]}");
      await world.command(server, "setblock " + at(compost) + " minecraft:composter");
      await optInViaBook(server, client, player.name, supply, "chest", stage);
      await screen.dismiss(client);
      await world.command(server, "gamemode survival " + player.name);
      await world.command(server, "item replace entity " + player.name + " weapon.mainhand with " + SEED + " 64");
      evidence.playerFill = await runColonyTask(server, PREFILL, { player: player.name, compost: stage.at(compost) });
      expect(evidence.playerFill.fill).toBe(7);
      await world.command(server, "item replace entity " + player.name + " weapon.mainhand with folkways:colony_book");
      await tick.sprint(server, 25);
      const read = () => server.run(READ, { compost: stage.at(compost) });
      expect((await read()).fill).toBe(8);
      await optInViaBook(server, client, player.name, compost, "worksite", stage);
      await screen.dismiss(client);
      let state = await until(server, read, evidence, "collect player compost without seed stock", s => s.fill === 0 && s.storedBone === 1, 4000, 20);
      expect(state.seeds).toBe(0); expect(state.bone).toBe(1); expect(state.dropped).toBe(0);
      expect(state.cookery).toBe(cookery);
      await tick.meter.start(server);

      // This physical stock is the only source for the resident batch: one reserve stack plus 16 surplus seeds.
      // A hat is supplied only in the Cookery profile, making the original single-seed implementation comparable.
      if (cookery) await world.command(server, "item replace block " + at(supply) + " container.26 with kaleidoscope_cookery:straw_hat");
      await world.command(server, "item replace block " + at(supply) + " container.24 with " + SEED + " 64");
      await world.command(server, "item replace block " + at(supply) + " container.25 with " + SEED + " 16");
      const begin = await read(); let previousCarried = 0; let acquisitions = 0; let maxCarried = 0;
      state = await until(server, read, evidence, "finish one physical batch without fetching each seed", s => {
        expect(s.seeds, "the standing planting reserve cannot be composted").toBeGreaterThanOrEqual(64);
        expect(s.seeds).toBeLessThanOrEqual(80);
        expect(s.dropped, "neither input nor bone meal should spill into the world").toBe(0);
        if (previousCarried === 0 && s.carriedSeeds > 0) acquisitions++;
        previousCarried = s.carriedSeeds; maxCarried = Math.max(maxCarried, s.carriedSeeds);
        evidence.batch = { elapsedTicks: s.tick - begin.tick, walked: s.walked - begin.walked,
          seedsConsumed: 80 - s.seeds, acquisitions, maxCarried };
        return s.seeds === 64 && s.carriedSeeds === 0 && s.fill < 7 && s.storedBone === s.bone;
      }, 9000, 5);
      evidence.batch = { elapsedTicks: state.tick - begin.tick, walked: state.walked - begin.walked,
        seedsConsumed: 80 - state.seeds, acquisitions, maxCarried };
      expect(maxCarried, "a batch must physically fetch multiple seeds together").toBeGreaterThanOrEqual(8);
      expect(maxCarried, "reserve checks must not carry the protected stack along with this batch").toBeLessThanOrEqual(16);
      expect(acquisitions, "one 16-seed allowance should not produce one warehouse fetch per seed").toBe(1);
      expect(state.storedBone).toBe(state.bone);
      await tick.sprint(server, 120);
      evidence.stable = await read();
      expect(evidence.stable.seeds).toBe(64); expect(evidence.stable.bone).toBe(state.bone);
    } catch (error) { pair?.failing(error); evidence.failure = String(error); throw error; }
    finally {
      if (pair) {
        const csv = testInfo.outputPath("batch-compost-ticks.csv");
        try { await tick.meter.dump(pair.server, csv); await testInfo.attach("batch-compost-ticks.csv", { path: csv, contentType: "text/csv" }); }
        catch (error) { evidence.tickMeterError = String(error); }
      }
      const file = testInfo.outputPath("batch-compost-evidence.json");
      writeFileSync(file, JSON.stringify(evidence, null, 2));
      await testInfo.attach("batch-compost-evidence.json", { path: file, contentType: "application/json" });
      await pair?.teardown(testInfo);
    }
  });
}

async function until(server: MinecraftServer, read: () => Promise<State>, evidence: Record<string, any>, phase: string,
  ready: (state: State) => boolean, budget: number, step: number): Promise<State> {
  for (let elapsed = 0; elapsed <= budget; elapsed += step) {
    const state = await read(); evidence.samples.push({ phase, ...state });
    if (ready(state)) return state;
    await tick.sprint(server, step);
  }
  throw new Error("Timed out: " + phase + "; last=" + JSON.stringify(evidence.samples.at(-1)));
}

const PREFILL = defineTask<{ player: string; compost: Vec }, { fill: number }>({
  name: "folkways.batch-compost.player-prefill", side: "server",
  source: javaTask({ imports: ["net.minecraft.core.BlockPos", "net.minecraft.world.item.Items", "net.minecraft.world.level.block.ComposterBlock"], body: `
var args=Args.of(ctx);var raw=(Map<?,?>)args.value("compost");var cell=new BlockPos(((Number)raw.get("x")).intValue(),((Number)raw.get("y")).intValue(),((Number)raw.get("z")).intValue());
var level=ctx.server().overworld();var player=ctx.server().getPlayerList().getPlayerByName(args.string("player"));var input=player.getMainHandItem();
if(!input.is(Items.WHEAT_SEEDS)||input.getCount()!=64)throw new IllegalStateException("prefill needs physical held seeds");
while(!input.isEmpty()&&level.getBlockState(cell).getValue(ComposterBlock.LEVEL)<7)ComposterBlock.insertItem(player,level.getBlockState(cell),level,input,cell);
return Map.of("fill",level.getBlockState(cell).getValue(ComposterBlock.LEVEL));
` }),
});

// Observe physical stock and public reservation data, without requesting or advancing resident work.
const READ = defineTask<{ compost: Vec }, State>({
  name: "folkways.batch-compost.observe", side: "server",
  source: javaTask({ imports: ["net.minecraft.core.BlockPos", "net.minecraft.world.item.Items", "net.minecraft.world.level.block.ComposterBlock",
    "net.minecraft.world.entity.item.ItemEntity", "net.neoforged.fml.ModList", "io.github.izakyl.folkways.core.api.colony.Colonies",
    "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.core.api.terms.Goods"], body: `
var level=ctx.server().overworld();var colony=Colonies.all(ctx.server()).getFirst();int seeds=0,carried=0,bone=0,storedBone=0,dropped=0;float walked=0;
for(var at:colony.storesIn(level.dimension())){var store=colony.store(level,at).orElseThrow();for(int slot=0;slot<store.getContainerSize();slot++){
 var stack=store.getItem(slot);if(stack.is(Items.WHEAT_SEEDS))seeds+=stack.getCount();if(stack.is(Items.BONE_MEAL))storedBone+=stack.getCount();}}
bone=storedBone;var residents=new ArrayList<Object>();
for(var resident:colony.residents()){var entity=level.getEntity(resident.id());var body=Bodies.of(entity).orElseThrow();walked+=entity.walkDist;
 int held=0;for(var stack:body.pack().contents()){if(stack.is(Items.WHEAT_SEEDS)){carried+=stack.getCount();held+=stack.getCount();}if(stack.is(Items.BONE_MEAL))bone+=stack.getCount();}
 residents.add(Map.of("id",body.id().toString(),"doing",body.doing().map(Object::toString).orElse(""),"balk",body.balk().map(Object::toString).orElse(""),"seeds",held));}
seeds+=carried;
for(var entity:level.getAllEntities())if(entity instanceof ItemEntity drop){var stack=drop.getItem();if(stack.is(Items.WHEAT_SEEDS)){seeds+=stack.getCount();dropped+=stack.getCount();}if(stack.is(Items.BONE_MEAL)){bone+=stack.getCount();dropped+=stack.getCount();}}
var raw=(Map<?,?>)Args.of(ctx).value("compost");var cell=new BlockPos(((Number)raw.get("x")).intValue(),((Number)raw.get("y")).intValue(),((Number)raw.get("z")).intValue());
var claims=new ArrayList<Object>();for(var claim:colony.reservedInputs(level))if(Goods.matches(Items.WHEAT_SEEDS.getDefaultInstance(),claim.goods()))claims.add(Map.of("node",claim.node().toString(),"count",claim.count()));
var out=new LinkedHashMap<String,Object>();out.put("tick",level.getGameTime());out.put("fill",level.getBlockState(cell).getValue(ComposterBlock.LEVEL));
out.put("seeds",seeds);out.put("carriedSeeds",carried);out.put("bone",bone);out.put("storedBone",storedBone);out.put("dropped",dropped);out.put("walked",walked);out.put("claims",claims);out.put("residents",residents);out.put("cookery",ModList.get().isLoaded("folkways_cookery"));return out;
` }),
});
