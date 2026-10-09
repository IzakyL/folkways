import { expect, test } from "@playwright/test";
import { clickElement, panelElements, typeIntoElement } from "../ldlib2";
import { camera, defineTask, input, javaTask, screen, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { setBookGesture, waitForGroundedPlayer } from "../e2e/colony-founding";
import { runColonyTask } from "../e2e/colony-tasks";
import {
  deployedSchematic, equipHandoffTool, HANDOFF_CONFIRM, HANDOFF_WINDOW, rightClickWorld,
} from "../e2e/schematic-tools";
import { BUDGET } from "../shared/budgets";
import { clickUnverified, tryCommand, waitForElement, softly, frames, safeAction } from "../shared/bw-helpers";
import { admitPromoSettlersFast, foundPromoColonyFast } from "./fast-setup";
import {
  cinematicWorld, hideHud, launchPromoPair, promoClip, PROMO_SKY_CONFIG, setFov, sleepMs, take, writeLook,
  type Look, type PromoPair,
} from "./promo-kit";
import { shaderEvidence } from "./shaders";
import path from "node:path";
import { mkdirSync } from "node:fs";

type Vec = { x: number; y: number; z: number };

const RESIDENTS = 16;
const EYE = 1.62;
// Each hand-off is named, so its card reads as what it is rather than the colony's next number.
const SITE_NAME = "folkways.site_name";
const COTTAGE = "Cottage.nbt";
const BACKGROUND_SHARE = 0.45;
const BACKGROUND_TICKS = 18_000;
const SCAFFOLDING = 128;
// A flat meadow on the west bank of a river in the promo-sky world: the river runs north into a lake about 40
// blocks east of here, and grassy hills rise to the west.
const ORIGIN = { x: -169, z: 218 };

const ROAD_STOCK: Record<string, number> = {
  "minecraft:andesite": 256, "minecraft:cobblestone": 192, "minecraft:polished_andesite": 128,
  "minecraft:stone_bricks": 256, "minecraft:dirt": 192, "minecraft:spruce_fence": 64, "minecraft:lantern": 24,
};

test("a blueprint and a road are laid by hand, then the colony's other works come into view", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo);
  const run = take("patterns");
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair("patterns", { config: PROMO_SKY_CONFIG });
    const { server, client, clientInstance } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    await tryCommand(server, `op ${player}`);
    await cinematicWorld(server);
    await world.command(server, `forceload add ${ORIGIN.x - 72} ${ORIGIN.z - 72} ${ORIGIN.x + 72} ${ORIGIN.z + 72}`);
    const site = await runColonyTask(server, GROUND, { origin: ORIGIN, residents: RESIDENTS });
    run.note("ground", site);
    await tryCommand(server, `kill @e[type=!minecraft:player,x=${ORIGIN.x},z=${ORIGIN.z},distance=..96]`);
    const origin = { x: ORIGIN.x, y: Number(site.y), z: ORIGIN.z };
    // Every spot the shot aims at stands on the real ground there: dy counts from the top block, as the origin
    // counts from the block the player stands on.
    const spots: Array<[number, number]> = [
      [0, 14], [-3, 0], [0, 3], [1, 8], [12, 8], [21, 8], [20, 8], [59, 8], [-37, -22], [-37, 12], [-29, 34],
      [13, -8], [35, -8], [-55, -18], [-43, -6],
    ];
    const heights = await runColonyTask(server, SURFACE, { cells: spots.map(([x, z]) => ({ x: origin.x + x, z: origin.z + z })) });
    run.note("heights", heights);
    const top = new Map(spots.map(([x, z], i) => [`${x},${z}`, Number((heights.tops as number[])[i])]));
    const at = (x: number, z: number, dy = 0): Vec => {
      const ground = top.get(`${x},${z}`);
      if (ground === undefined) throw new Error(`no surveyed ground at ${x},${z}`);
      return { x: origin.x + x, y: ground + 1 + dy, z: origin.z + z };
    };
    const above = (x: number, dy: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + dy, z: origin.z + z });

    const schematics = path.join(client.instance.instanceDir, "schematics");
    mkdirSync(schematics, { recursive: true });
    const cottage = await runColonyTask(server, SAVE_COTTAGE,
      { at: above(0, 60, 0), file: path.join(schematics, COTTAGE) });
    run.note("cottage", cottage);

    const hero = at(0, 14);
    await world.command(server, `tp ${player} ${hero.x + 0.5} ${hero.y} ${hero.z + 0.5} 180 12`);
    await world.command(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);
    const founded = await foundPromoColonyFast(server, player, site.members as Vec[]);
    const colony_id = founded.colony_id;
    run.note("admitted", await admitPromoSettlersFast(server, player, colony_id, RESIDENTS));
    run.note("crew", await runColonyTask(server, CREW, { colony_id, origin }));

    // The wall follows the foot of the western hills, the mine sits on their crest, the bridge crosses the river
    // on the line of the road and the dock runs out from the bank upstream, toward the lake.
    const background = [
      { pattern: "folkways:city_wall", points: [at(-37, -22), at(-37, 12), at(-29, 34)], knobs: {
        wall: ["minecraft:deepslate_bricks"], base: ["minecraft:cobbled_deepslate"],
        walkway: ["minecraft:polished_deepslate"], trim: ["minecraft:deepslate_tiles"],
        timber: ["minecraft:stripped_dark_oak_log"], panel: ["minecraft:dark_oak_planks"] } },
      { pattern: "folkways:mine", min: at(-55, -18, -1), max: { ...at(-43, -6, -1), y: at(-55, -18, -1).y }, knobs: {
        level: "shallow", timber: ["minecraft:dark_oak_log"], planks: ["minecraft:dark_oak_planks"],
        stairs: ["minecraft:dark_oak_stairs"], rail: ["minecraft:dark_oak_fence"],
        gate: ["minecraft:dark_oak_fence_gate"], lamp: ["minecraft:soul_lantern"], seal: ["minecraft:cobbled_deepslate"] } },
      { pattern: "folkways:arch_bridge", points: [at(20, 8), at(59, 8)], knobs: {
        arch: ["minecraft:cut_sandstone"], deck: ["minecraft:smooth_sandstone"], parapet: ["minecraft:sandstone_wall"],
        abutment: ["minecraft:chiseled_sandstone"], accent: ["minecraft:red_sandstone"] } },
      { pattern: "folkways:dock", points: [at(13, -8), at(35, -8)], knobs: {
        deck: ["minecraft:birch_planks"], beam: ["minecraft:stripped_birch_log"], pile: ["minecraft:birch_log"],
        rail: ["minecraft:birch_fence"], landing: ["minecraft:mossy_cobblestone"], lantern: ["minecraft:soul_lantern"] } },
    ];
    run.note("background", await runColonyTask(server, COMMISSION, { colony_id, sites: background }));
    // Work out of reach goes up from a scaffold, raised and lowered again from the colony's stores.
    run.note("scaffolding", await runColonyTask(server, STOCK_ITEMS,
      { chests: site.chests, items: { "minecraft:scaffolding": SCAFFOLDING } }));
    await tick.sprint(server, 60);
    run.note("background_stock", await runColonyTask(server, STOCK,
      { colony_id, yards: site.yards, share: BACKGROUND_SHARE }));
    await tick.sprint(server, BACKGROUND_TICKS);
    run.note("stalled", await runColonyTask(server, CLEAR, { yards: site.yards }));
    run.note("staged", await runColonyTask(server, STAGE,
      { colony_id, shares: { city_wall: 0.5, arch_bridge: 0.6, dock: 0.7 } }));
    const before = await runColonyTask(server, PLACARDS, { colony_id });
    run.note("before", before);
    expect((before.placards as any[]).length, "every background site must show a placard").toBe(background.length);

    // Keep the built scenery, but withdraw its work while the foreground hand-offs are filmed.
    // Empty stores and a relocation do not stop the mine or the remaining background orders.
    const calledOff = await runColonyTask(server, CALL_OFF,
      { colony_id, ids: (before.placards as any[]).map(one => one.id) });
    run.note("background_called_off", calledOff);

    run.note("gathered", await runColonyTask(server, GATHER, { colony_id, origin }));
    run.note("hero_stock", await runColonyTask(server, STOCK_ITEMS,
      { chests: site.chests, items: { ...cottage.materials as Record<string, number>, ...ROAD_STOCK } }));

    await world.command(server, `gamemode survival ${player}`);
    await world.command(server, `attribute ${player} minecraft:player.block_interaction_range base set 64`);
    await world.command(server, `tp ${player} ${hero.x + 0.5} ${hero.y} ${hero.z + 0.5} 180 12`);
    await tick.sprint(server, 10);
    await setBookGesture(server, client, player, "line");
    await safeAction(() => screen.dismiss(client));
    await setFov(client, 70);
    const eye = { x: hero.x + 0.5, y: hero.y + EYE, z: hero.z + 0.5 };
    let look: Look = { yaw: 180, pitch: 12 };
    await writeLook(client, look);
    await safeAction(() => frames(client, 30));
    await sleepMs(3_000);

    const anchor = at(-3, 0);
    const turnTo = async (target: Vec, ms = 700) => {
      const want = cameraLookingAt(eye, { x: target.x + 0.5, y: target.y + 0.5, z: target.z + 0.5 });
      look = await glide(client, look, want, ms);
    };
    const receipts: any = {};
    let gates: any;
    try {
    gates = await promoClip(run, client, server, "01-patterns", {
      subject: "First person: a cottage blueprint is handed off and residents come to build it, a road is drawn "
        + "from its door to the bridge, then the camera pulls back over the colony's other works, each still outlined "
        + "with a card saying what it has made or still needs",
      worldState: { colony_id, hero, anchor, background },
      seconds: 70,
      during: async () => {
        await sleepMs(1_200);
        await world.command(server, `item replace entity ${player} weapon.offhand from entity ${player} weapon.mainhand`);
        await world.command(server, `item replace entity ${player} weapon.mainhand with `
          + deployedSchematic(COTTAGE, player, anchor, cottage.size as Vec));
        await turnTo(at(0, 3, -1), 900);
        await sleepMs(1_500);
        receipts.equipped = await equipHandoffTool(client);
        receipts.opened = await rightClickWorld(client);
        receipts.window = await softly(() => waitForElement(client, { id: HANDOFF_WINDOW }, { timeoutMs: 8_000 }));
        await sleepMs(600);
        receipts.named = await typeIntoElement(client, { id: SITE_NAME }, "Cottage");
        await sleepMs(600);
        receipts.confirm = await clickUnverified(client, { id: HANDOFF_CONFIRM });
        await safeAction(() => screen.dismiss(client));
        await world.command(server, `item replace entity ${player} weapon.mainhand from entity ${player} weapon.offhand`);
        await world.command(server, `item replace entity ${player} weapon.offhand with minecraft:air`);
        for (let second = 0; second < 7; second++) {
          receipts.crew = [...(receipts.crew ?? []), await runColonyTask(server, DOING, { colony_id })];
          await sleepMs(1_000);
        }

        await turnTo(at(1, 8, -1), 900);
        await sleepMs(400);
        await leftClick(client);
        await sleepMs(500);
        await turnTo(at(12, 8, -1), 900);
        await sleepMs(300);
        await leftClick(client);
        await sleepMs(500);
        await turnTo(at(21, 8, -1), 700);
        await sleepMs(300);
        await leftClick(client);
        await sleepMs(900);
        receipts.road = await commissionRoad(client);
        await expect.poll(async () => {
          const cards = await runColonyTask(server, PLACARDS, { colony_id });
          return ["Cottage", "Road"].every(title => (cards.placards as any[]).some(one =>
            one.lines[0] === title && Number(String(one.lines[2]).split("/")[0]) > 0));
        }, { message: "both foreground hand-offs must be worked before the reveal", timeout: 60_000 }).toBe(true);

        // Re-file the background works against their existing blocks for the final wide shot and cards.
        receipts.background = await runColonyTask(server, RESTORE, { colony_id, orders: calledOff.orders });
        await expect.poll(async () => (await runColonyTask(server, PLACARDS, { colony_id })).placards.length,
          { timeout: 20_000 }).toBe(background.length + 2);

        await hideHud(client);
        receipts.fly = await safeAction(() => camera.fly(client, {
          poses: [
            { position: eye, yaw: look.yaw, pitch: look.pitch },
            { position: above(3, 22, 9), yaw: 176, pitch: 24 },
            { position: above(2, 40, 26), yaw: 178, pitch: 36 },
            { position: above(0, 54, 36), yaw: 180, pitch: 42 },
          ],
          durationMs: 14_000,
          fov: 70,
        }));
        await sleepMs(20_000);
      },
    });
    } finally {
      run.note("receipts", receipts);
    }
    run.note("shot", gates);
    const after = await runColonyTask(server, PLACARDS, { colony_id });
    run.note("after", after);
    const placed = (title: string) => {
      const card = (after.placards as any[]).find(one => one.lines[0] === title);
      return card ? Number(String(card.lines[2]).split("/")[0]) : -1;
    };
    expect(placed("Cottage"), "residents must have started the handed-off cottage").toBeGreaterThan(0);
    expect(placed("Road"), "residents must have started the road drawn with the book").toBeGreaterThan(0);
    run.note("shaders", shaderEvidence(clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function glide(client: any, from: Look, to: { yaw: number; pitch: number }, ms: number): Promise<Look> {
  const steps = Math.max(1, Math.round(ms / 30));
  let yawDelta = ((to.yaw - from.yaw + 540) % 360) - 180;
  for (let step = 1; step <= steps; step++) {
    const t = step / steps;
    const eased = t * t * (3 - 2 * t);
    await writeLook(client, { yaw: from.yaw + yawDelta * eased, pitch: from.pitch + (to.pitch - from.pitch) * eased });
    await sleepMs(30);
  }
  return { yaw: from.yaw + yawDelta, pitch: to.pitch };
}

async function leftClick(client: MinecraftClient) {
  await safeAction(() => input.button(client, { button: "left", action: "press" }));
  await sleepMs(60);
  await safeAction(() => input.button(client, { button: "left", action: "release" }));
}

async function tokens(client: any): Promise<string[]> {
  const elements = await softly(() => panelElements(client));
  return (Array.isArray(elements) ? elements : [])
    .filter((one: any) => one.width > 0 && one.height > 0 && one.displayed !== false)
    .map((one: any) => String(one.id ?? "")).filter(Boolean);
}

async function waitForToken(client: any, match: (id: string) => boolean, timeoutMs = 8_000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    const found = (await tokens(client)).find(match);
    if (found) return found;
    await sleepMs(150);
  }
  throw new Error(`no panel element matched; saw ${JSON.stringify(await tokens(client))}`);
}

async function commissionRoad(client: any) {
  const opened = await rightClickWorld(client);
  const offer = await waitForToken(client, id => id.startsWith("folkways.zoneconfig.") && id.includes("draft"));
  await sleepMs(700);
  await clickElement(client, { id: offer });
  await sleepMs(500);
  const proceed = await clickUnverified(client, { id: "folkways.zoneconfig.confirm" });
  const road = await waitForToken(client, id => id.startsWith("folkways.draft.pattern.") && id.endsWith("road"));
  await sleepMs(600);
  await clickElement(client, { id: road });
  await sleepMs(600);
  const named = await typeIntoElement(client, { id: SITE_NAME }, "Road");
  await sleepMs(600);
  const confirm = await clickUnverified(client, { id: "folkways.draft.confirm" });
  await sleepMs(300);
  await safeAction(() => screen.dismiss(client));
  return { opened, offer, proceed, road, named, confirm };
}

// Works the real ground rather than a flattened one: only the meadow's tall grass is cleared and the wheat field
// levelled. The beds lie sealed in rock under the meadow, so no row of them shows in the shot.
const GROUND = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-ground",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.Direction",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.server.level.ServerLevel",
      "net.minecraft.tags.BlockTags",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.ItemStack",
      "net.minecraft.world.item.Items",
      "net.minecraft.world.level.block.*",
      "net.minecraft.world.level.block.state.properties.BedPart",
      "net.minecraft.world.level.levelgen.Heightmap",
    ],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var o=(Map<?,?>)args.value("origin");int residents=(int) args.integer("residents");
int ox=((Number)o.get("x")).intValue(),oz=((Number)o.get("z")).intValue();
int oy=top(level,ox,oz)+1;
for(int x=-32;x<=26;x++)for(int z=-20;z<=24;z++){
  var ground=new BlockPos(ox+x,top(level,ox+x,oz+z),oz+z);
  for(int y=1;y<=2;y++){
    var plant=level.getBlockState(ground.above(y));
    if(plant.is(Blocks.SHORT_GRASS)||plant.is(Blocks.TALL_GRASS)||plant.is(Blocks.FERN)||plant.is(Blocks.LARGE_FERN))
      level.setBlock(ground.above(y),Blocks.AIR.defaultBlockState(),2);
  }
}
for(int x=-22;x<=-10;x++)for(int z=-8;z<=4;z++){
  var ground=new BlockPos(ox+x,oy-1,oz+z);
  for(int y=1;y<=4;y++)level.setBlock(ground.above(y),Blocks.AIR.defaultBlockState(),2);
  for(int y=1;y<=3;y++)if(!level.getBlockState(ground.below(y)).isSolid())level.setBlock(ground.below(y),Blocks.DIRT.defaultBlockState(),2);
  if(x==-16){level.setBlock(ground,Blocks.WATER.defaultBlockState(),2);continue;}
  level.setBlock(ground,Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7),2);
  level.setBlock(ground.above(),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),2);
}
var members=new ArrayList<Object>();var chests=new ArrayList<Object>();
for(int i=0;i<16;i++){
  var chest=new BlockPos(ox-6+i,top(level,ox-6+i,oz-16)+1,oz-16);
  level.setBlock(chest,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.SOUTH),2);
  var cell=Map.of("x",chest.getX(),"y",chest.getY(),"z",chest.getZ());members.add(cell);
  if(i<2){var box=(Container)level.getBlockEntity(chest);
    for(int slot=0;slot<box.getContainerSize();slot++)box.setItem(slot,new ItemStack(Items.BREAD,64));
    box.setChanged();}
  else chests.add(cell);
}
var yards=new ArrayList<Object>();
int[][] depots={{-31,-12},{-53,-2},{9,14},{9,-14}};
for(var depot:depots){
  var yard=new ArrayList<Object>();
  for(int i=0;i<6;i++){
    int x=ox+depot[0]+i,z=oz+depot[1];
    var chest=new BlockPos(x,top(level,x,z)+1,z);
    level.setBlock(chest,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.SOUTH),2);
    var cell=Map.of("x",chest.getX(),"y",chest.getY(),"z",chest.getZ());members.add(cell);yard.add(cell);
  }
  yards.add(yard);
}
int cellar=oy-10;
for(int x=-15;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=9;z<=15;z++)
  level.setBlock(new BlockPos(ox+x,cellar+y,oz+z),Blocks.STONE.defaultBlockState(),2);
for(int i=0;i<residents;i++){
  var foot=new BlockPos(ox-14+(i%8)*2,cellar,oz+11+(i/8)*3);
  level.setBlock(foot,Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.FOOT),3);
  level.setBlock(foot.north(),Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.HEAD),3);
  members.add(Map.of("x",foot.getX(),"y",foot.getY(),"z",foot.getZ()));
}
return Sync.stamp(Map.of("ok",true,"y",oy,"members",members,"chests",chests,"yards",yards));
`,
    members: `
static int top(ServerLevel level,int x,int z){
  var pos=new BlockPos.MutableBlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
  while(pos.getY()>level.getMinBuildHeight()&&level.getBlockState(pos).is(BlockTags.LOGS))pos.move(0,-1,0);
  return pos.getY();
}
`,
  }),
});

// The top block at each cell: where the river runs, the water is the top.
const SURFACE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-surface",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.level.levelgen.Heightmap",
    ],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var tops=new ArrayList<Object>();
for(var raw:args.list("cells")){var m=(Map<?,?>)raw;
  int x=((Number)m.get("x")).intValue(),z=((Number)m.get("z")).intValue();
  tops.add(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1);}
return Map.of("ok",true,"tops",tops);
`,
  }),
});

const SAVE_COTTAGE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-cottage",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.Direction",
      "net.minecraft.core.Vec3i",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.nbt.CompoundTag",
      "net.minecraft.nbt.NbtIo",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.level.block.*",
      "net.minecraft.world.level.block.state.BlockState",
      "net.minecraft.world.level.block.state.properties.DoubleBlockHalf",
      "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate",
      "java.nio.file.Path",
    ],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var a=(Map<?,?>)args.value("at");
var base=new BlockPos(((Number)a.get("x")).intValue(),((Number)a.get("y")).intValue(),((Number)a.get("z")).intValue());
int w=7,d=7,h=8;
var materials=new TreeMap<String,Integer>();
for(int x=0;x<w;x++)for(int y=0;y<h;y++)for(int z=0;z<d;z++){
  boolean edgeX=x==0||x==w-1,edgeZ=z==0||z==d-1,wall=edgeX||edgeZ,corner=edgeX&&edgeZ;
  BlockState state=Blocks.AIR.defaultBlockState();
  if(y==0)state=Blocks.MUD_BRICKS.defaultBlockState();
  else if(y<=3&&corner)state=Blocks.STRIPPED_OAK_LOG.defaultBlockState();
  else if(y<=3&&wall){
    boolean window=y==2&&((edgeX&&z==3)||(edgeZ&&(x==1||x==5)));
    state=window?Blocks.GLASS_PANE.defaultBlockState():Blocks.OAK_PLANKS.defaultBlockState();
  }
  else if(y>=4){
    int inset=y-4;
    if(x>=inset&&x<w-inset&&z>=0&&z<d&&inset<=3){
      boolean shell=x==inset||x==w-1-inset||y==7;
      if(shell||(z==0||z==d-1))state=Blocks.BRICKS.defaultBlockState();
    }
  }
  level.setBlock(base.offset(x,y,z),state,2);
  if(!state.isAir())materials.merge(BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString(),1,Integer::sum);
}
var door=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH);
level.setBlock(base.offset(3,1,d-1),door.setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER),2);
level.setBlock(base.offset(3,2,d-1),door.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),2);
materials.merge("minecraft:oak_planks",-2,Integer::sum);materials.merge("minecraft:oak_door",1,Integer::sum);
var template=new StructureTemplate();
template.fillFromWorld(level,base,new Vec3i(w,h,d),false,Blocks.STRUCTURE_VOID);
var saved=template.save(new CompoundTag());
NbtIo.writeCompressed(saved,Path.of(args.string("file")));
for(int x=0;x<w;x++)for(int y=0;y<h;y++)for(int z=0;z<d;z++)level.setBlock(base.offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
return Sync.stamp(Map.of("ok",true,"materials",materials,"size",Map.of("x",w,"y",h,"z",d)));
`,
  }),
});

const CREW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-crew",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.*",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.item.*",
      "net.minecraft.world.level.levelgen.Heightmap",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var o=(Map<?,?>)args.value("origin");
int ox=((Number)o.get("x")).intValue(),oy=((Number)o.get("y")).intValue(),oz=((Number)o.get("z")).intValue();
var front=ColonyFront.of(colony);
var zone=ZoneDrafts.zone(level,colony,ResourceLocation.parse("folkways:farm"),
  new BlockPos(ox-22,oy-1,oz-8),new BlockPos(ox-10,oy,oz+4),
  ColonySettings.empty().with("crop",new ColonySettings.Value.Choice(ResourceLocation.parse("minecraft:wheat")))).orElseThrow();
int i=0;var crew=new ArrayList<Object>();
for(var resident:colony.residents()){
  var entity=level.getEntity(resident.id());var body=Bodies.of(entity).orElseThrow();
  int index=i++;String role=index<3?"farming":index<13?"building":"hauling";
  for(var vocation:Vocations.all())body.licences().trade(vocation).ifPresent(trade->
    body.licences().allow(trade,vocation.id().getPath().equals(role)||vocation.id().getPath().equals("hauling")));
  body.pack().insert(new ItemStack(Items.BREAD,16));
  if(role.equals("farming")){body.pack().insert(new ItemStack(Items.IRON_HOE));}
  if(role.equals("building")){
    body.pack().insert(new ItemStack(Items.IRON_PICKAXE));body.pack().insert(new ItemStack(Items.IRON_AXE));
    body.pack().insert(new ItemStack(Items.IRON_SHOVEL));
  }
  double x=ox-2+(index%6)*1.5,z=oz-10+(index/6)*1.5;
  entity.teleportTo(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)Math.floor(x),(int)Math.floor(z))+0.1,z);
  crew.add(Map.of("id",resident.id().toString(),"role",role));
}
return Sync.stamp(Map.of("ok",true,"crew",crew,"zone",zone.id().toString()));
`,
  }),
});

const DOING = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-doing",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.api.notice.Doings",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var out=new ArrayList<Object>();
for(var resident:colony.residents()){
  var entity=level.getEntity(resident.id());if(entity==null)continue;
  var doing=Bodies.of(entity).flatMap(body->body.doing()).map(one->Doings.name(one).getString()).orElse("idle");
  out.add(entity.blockPosition().toShortString()+" "+doing);
}
return Map.of("ok",true,"tick",level.getGameTime(),"crew",out);
`,
  }),
});

const GATHER = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-gather",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.core.engine.labor.ColonyLabor",
      "io.github.izakyl.folkways.core.engine.labor.Labor",
      "net.minecraft.resources.ResourceKey",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.level.levelgen.Heightmap",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
// Empty yards do not invalidate tours already assigned during the background sprint.
// Use the relocation lifecycle to release gestures and runners and retire the planner
// on its own executor. The next tick replans the existing sites from their current state.
var site=Labor.class.getDeclaredMethod("site",UUID.class,ResourceKey.class);site.setAccessible(true);
var labor=(ColonyLabor)((Optional<?>)site.invoke(null,colony.id(),level.dimension())).orElseThrow();
labor.relocating();
var o=(Map<?,?>)args.value("origin");
int ox=((Number)o.get("x")).intValue(),oy=((Number)o.get("y")).intValue(),oz=((Number)o.get("z")).intValue();
int i=0;
for(var resident:colony.residents()){
  var entity=level.getEntity(resident.id());int index=i++;
  if(index<3||entity==null)continue;
  double x=ox-8+(index%5)*2.5,z=oz-11+(index/5)*2.0;
  entity.teleportTo(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)Math.floor(x),(int)Math.floor(z))+0.1,z);
}
return Sync.stamp(Map.of("ok",true,"assignments_released",true));
`,
  }),
});

const STOCK_ITEMS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-stock-items",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.ItemStack",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var stocked=new LinkedHashMap<String,Integer>();
var chests=new ArrayList<Container>();
for(var raw:args.list("chests")){var m=(Map<?,?>)raw;
  chests.add((Container)level.getBlockEntity(new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue())));}
var items=(Map<?,?>)args.value("items");
for(var entry:items.entrySet()){
  var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(entry.getKey())));
  int left=((Number)entry.getValue()).intValue()+8;
  for(var chest:chests){
    for(int slot=0;slot<chest.getContainerSize()&&left>0;slot++){
      if(!chest.getItem(slot).isEmpty())continue;
      int count=Math.min(left,item.getDefaultMaxStackSize());
      chest.setItem(slot,new ItemStack(item,count));left-=count;
      stocked.merge(entry.getKey().toString(),count,Integer::sum);
    }
    chest.setChanged();
    if(left<=0)break;
  }
}
return Sync.stamp(Map.of("ok",true,"stocked",stocked));
`,
  }),
});

const COMMISSION = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-commission",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.build.Commissions",
      "io.github.izakyl.folkways.plugins.build.draft.Hint",
      "io.github.izakyl.folkways.plugins.build.draft.Patterns",
      "io.github.izakyl.folkways.front.api.Schema",
      "io.github.izakyl.folkways.core.api.terms.ItemSpec",
      "net.minecraft.core.registries.Registries",
      "net.minecraft.tags.TagKey",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.Direction",
      "net.minecraft.nbt.CompoundTag",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var said=new ArrayList<Object>();boolean all=true;
for(var raw:args.list("sites")){
  var site=(Map<?,?>)raw;
  Hint hint=site.containsKey("points")
    ? new Hint.Path(((List<?>)site.get("points")).stream().map(Task::pos).toList())
    : new Hint.Zone(pos(site.get("min")),pos(site.get("max")));
  var id=ResourceLocation.parse(String.valueOf(site.get("pattern")));
  var result=Commissions.commission(level,colony,id,hint.save(),defaults(id,site.containsKey("knobs")?(Map<?,?>)site.get("knobs"):Map.of()),Direction.NORTH,"",Optional.empty(),"promo");
  all&=result.filed();
  said.add(Map.of("pattern",String.valueOf(site.get("pattern")),"filed",result.filed(),"said",result.message().getString()));
}
if(!all)throw new TaskException("refused","not every commission was filed",Map.of("said",said));
return Sync.stamp(Map.of("ok",true,"said",said));
`,
    members: `
static BlockPos pos(Object raw){var m=(Map<?,?>)raw;
  return new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue());}
static CompoundTag defaults(ResourceLocation id,Map<?,?> picked){
  var tag=new CompoundTag();
  for(var setting:Patterns.find(id).orElseThrow().knobs().settings()){
    if(setting instanceof Schema.Setting.Choice choice&&picked.containsKey(choice.key())){
      var word=String.valueOf(picked.get(choice.key()));
      choice.options().stream().filter(option->option.getPath().equals(word)).findFirst()
        .ifPresent(option->tag.putString(choice.key(),option.toString()));
      continue;
    }
    if(setting instanceof Schema.Setting.Count count&&picked.containsKey(count.key())){
      tag.putInt(count.key(),((Number)picked.get(count.key())).intValue());
      continue;
    }
    if(!(setting instanceof Schema.Setting.Items items))continue;
    var specs=new ArrayList<ItemSpec>();
    if(picked.get(items.key()) instanceof List<?> ids){
      for(var one:ids)specs.add(ItemSpec.of(ResourceLocation.parse(String.valueOf(one))));
    }else{
      for(var filter:items.byDefault())specs.add(filter.tag()?ItemSpec.of(TagKey.create(Registries.ITEM,filter.id())):ItemSpec.of(filter.id()));
    }
    if(specs.isEmpty())continue;
    tag.put(items.key(),ItemSpec.anyOf(specs).save());
  }
  return tag;
}
`,
  }),
});

const PLACARDS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-placards",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.api.Facing",
      "io.github.izakyl.folkways.front.api.Placard",
      "io.github.izakyl.folkways.front.api.notice.Line",
      "io.github.izakyl.folkways.front.api.notice.Sentence",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var view=colony.view(level);var out=new ArrayList<Object>();
// The building sites' cards only: stores and cookers carry cards of their own.
for(var facing:Facing.all(colony))if(facing.getClass().getSimpleName().equals("BuildPresence"))
for(var placard:facing.placards(view)){
  var lines=new ArrayList<Object>();
  for(var line:placard.lines())lines.add(switch(line){
    case Line.Said said->said.notice().component().getString();
    case Line.Literal literal->literal.text();
    case Line.Goods goods->goods.stacks().stream().map(s->s.item()+"x"+s.count()).toList();
    case Line.Told told->told.sentence().tokens().stream().filter(t->t instanceof Sentence.Token.Ware)
      .map(t->{var w=(Sentence.Token.Ware)t;return w.item()+"x"+w.count();}).toList();
    case Line.Bar bar->bar.value()+"/"+bar.max();
    default->line.toString();
  });
  out.add(Map.of("id",placard.id().toString(),"outline",placard.outline().getClass().getSimpleName(),"lines",lines));
}
return Map.of("ok",true,"placards",out);
`,
  }),
});

const STOCK = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-stock",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.api.Facing",
      "io.github.izakyl.folkways.front.api.Placard",
      "io.github.izakyl.folkways.front.api.notice.Line",
      "io.github.izakyl.folkways.front.api.notice.Sentence",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.ItemStack",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
double share=args.number("share");
var yards=new ArrayList<List<BlockPos>>();
for(var yard:args.list("yards"))yards.add(((List<?>)yard).stream().map(Task::pos).toList());
var stocked=new ArrayList<Object>();
for(var facing:Facing.all(colony))for(var placard:facing.placards(colony.view(level))){
  BlockPos first=switch(placard.outline()){
    case Placard.Outline.Box box->box.min();
    case Placard.Outline.Path path->path.points().getFirst();
  };
  var yard=yards.stream().min(Comparator.comparingDouble(one->one.getFirst().distSqr(first))).orElseThrow();
  var filled=new LinkedHashMap<String,Long>();
  for(var line:placard.lines())if(line instanceof Line.Told told)for(var token:told.sentence().tokens()){
    if(!(token instanceof Sentence.Token.Ware stack))continue;
    var item=BuiltInRegistries.ITEM.get(stack.item());long left=Math.round(stack.count()*share);
    for(var at:yard){
      var chest=(Container)level.getBlockEntity(at);
      for(int slot=0;slot<chest.getContainerSize()&&left>0;slot++){
        if(!chest.getItem(slot).isEmpty())continue;
        int count=(int)Math.min(left,item.getDefaultMaxStackSize());
        chest.setItem(slot,new ItemStack(item,count));left-=count;filled.merge(stack.item().toString(),(long)count,Long::sum);
      }
      chest.setChanged();
      if(left<=0)break;
    }
  }
  stocked.add(Map.of("placard",placard.id().toString(),"yard",yard.getFirst().toShortString(),"filled",filled));
}
return Sync.stamp(Map.of("ok",true,"stocked",stocked));
`,
    members: `
static BlockPos pos(Object raw){var m=(Map<?,?>)raw;
  return new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue());}
`,
  }),
});

const STAGE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-stage",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.build.BlueprintBlock",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var shares=(Map<?,?>)args.value("shares");
var presence=colony.service(ResourceLocation.parse("folkways:build"),Object.class).orElseThrow();
var field=presence.getClass().getDeclaredField("book");field.setAccessible(true);var book=field.get(presence);
var staged=new LinkedHashMap<String,Integer>();
for(var order:(List<?>)call(book,"allBuildOrders")){
  var blueprint=((Optional<?>)call(book,"blueprint",call(order,"blueprintId"))).orElse(null);
  if(blueprint==null)continue;
  var name=String.valueOf(call(blueprint,"name"));
  if(!shares.containsKey(name))continue;
  var anchor=(BlockPos)call(call(order,"anchor"),"cell");
  var blocks=new ArrayList<BlueprintBlock>();
  for(var block:(List<?>)call(blueprint,"blocks"))if(!((BlueprintBlock)block).state().isAir())blocks.add((BlueprintBlock)block);
  blocks.sort(Comparator.comparingInt(block->block.offset().getY()));
  int count=(int)Math.round(blocks.size()*((Number)shares.get(name)).doubleValue());
  for(int i=0;i<count;i++){var block=blocks.get(i);level.setBlock(block.worldPos(anchor),block.state(),2);}
  staged.merge(name,count,Integer::sum);
}
return Sync.stamp(Map.of("ok",true,"staged",staged));
`,
    members: `
static Object call(Object target,String name,Object... args) throws Exception {
  for(var method:target.getClass().getDeclaredMethods()){
    if(method.getName().equals(name)&&method.getParameterCount()==args.length){method.setAccessible(true);return method.invoke(target,args);}
  }
  throw new NoSuchMethodException(name);
}
`,
  }),
});

const CALL_OFF = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-call-off",
  side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.api.Facing",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.build.BlueprintBuildOrder",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var page=ResourceLocation.parse("folkways:build");
var facing=Facing.of(colony,page).orElseThrow();int stopped=0;
var presence=colony.service(page,Object.class).orElseThrow();
var field=presence.getClass().getDeclaredField("book");field.setAccessible(true);var book=field.get(presence);
var all=book.getClass().getDeclaredMethod("allBuildOrders");all.setAccessible(true);
var orders=new ArrayList<Object>();
for(var raw:(List<?>)all.invoke(book)){
  var order=(BlueprintBuildOrder)raw;var at=order.anchor().cell();
  orders.add(Map.of("blueprint",order.blueprintId().toString(),"name",order.name(),"x",at.getX(),"y",at.getY(),"z",at.getZ()));
}
if(orders.size()!=args.strings("ids").size())throw new IllegalStateException("Background drawings are not ready to retain");
for(var id:args.strings("ids")){
  var result=facing.act(page,"stop:"+id,Optional.empty(),colony.view(level));
  if(result.refused())result=facing.act(page,"cancel:"+id,Optional.empty(),colony.view(level));
  if(result.refused())throw new IllegalStateException("Could not call off background site "+id);
  stopped++;
}
return Sync.stamp(Map.of("ok",true,"stopped",stopped,"orders",orders));
`,
  }),
});

// Restore the exact blueprints, not the pattern generators: a partly built dock is no
// longer open water, and regenerating it would both refuse and lose the original drawing.
const RESTORE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-restore",
  side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.engine.colony.ColonyGround", "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation", "net.minecraft.server.level.ServerLevel"],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var presence=colony.service(ResourceLocation.parse("folkways:build"),Object.class).orElseThrow();
var field=presence.getClass().getDeclaredField("book");field.setAccessible(true);var book=field.get(presence);
var place=book.getClass().getDeclaredMethod("place",ServerLevel.class,UUID.class,String.class,BlockPos.class,Optional.class,String.class,long.class);
place.setAccessible(true);int restored=0;
for(var raw:args.list("orders")){
  var order=(Map<?,?>)raw;
  var at=new BlockPos(((Number)order.get("x")).intValue(),((Number)order.get("y")).intValue(),((Number)order.get("z")).intValue());
  var result=(Optional<?>)place.invoke(book,level,UUID.fromString(String.valueOf(order.get("blueprint"))),String.valueOf(order.get("name")),at,Optional.empty(),"promo",level.getGameTime());
  if(result.isEmpty())throw new IllegalStateException("Could not restore "+order.get("name"));
  restored++;
}
return Sync.stamp(Map.of("ok",true,"restored",restored));
`,
  }),
});

const CLEAR = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-clear",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.Container",
    ],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
int left=0;
for(var yard:args.list("yards"))for(var raw:(List<?>)yard){var m=(Map<?,?>)raw;
  var chest=(Container)level.getBlockEntity(new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue()));
  for(int slot=0;slot<chest.getContainerSize();slot++)left+=chest.getItem(slot).getCount();
  chest.clearContent();chest.setChanged();}
return Sync.stamp(Map.of("ok",true,"cleared",left));
`,
  }),
});
