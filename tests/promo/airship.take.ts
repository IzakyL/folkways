import { expect, test } from "@playwright/test";
import { defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { waitForGroundedPlayer } from "../e2e/colony-founding";
import { runColonyTask } from "../e2e/colony-tasks";
import { foundPromoColonyFast, admitPromoSettlersFast, setMaintainDemandsFast } from "./fast-setup";
import {
  cinematicWorld, hideHud, launchPromoPair, promoClip, PROMO_SKY_CONFIG, setFov, sleepMs, take, writeLook,
  type PromoPair,
} from "./promo-kit";
import { shaderEvidence } from "./shaders";
import { tryCommand, frames, safeAction } from "../shared/bw-helpers";

// Above the forested hills south of spawn in the promo-sky world; nothing stands above y=117 on this course,
// and the keel hangs 8 below the deck.
const ORIGIN = { x: -60, y: 128, z: 40 };
const at = (x: number, y: number, z: number) => ({ x: ORIGIN.x + x, y: ORIGIN.y + y, z: ORIGIN.z + z });
const SECONDS = 32;
// Fast enough that the whole ship, bowsprit to stern, sails through the frame within the clip.
const SPEED = 3.0;
// Close beside the ship's course, just above the deck and well inside the book's 32-block card range: the bow
// enters on the left as the clip opens and the stern leaves on the right before it ends.
const CAMERA = { x: 55, y: 10, z: 21 };
const AIM = { x: 51, y: 1, z: 0 };
const SAMPLES = 15;
// What a resident is up to, as the WORK task reports it: leisure does not count as a job.
const LEISURE = new Set(["idle", "folkways:walking", "folkways:waiting", "folkways:chatting", "folkways:eating", "folkways:sleeping"]);
const RESIDENTS = 12;
const WARMUP_TICKS = 200;

test("a sky ship carries a working colony past a fixed camera", async ({}, testInfo) => {
  const run = take("airship");
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair("airship", { config: PROMO_SKY_CONFIG });
    const { server, client, clientInstance } = pair;
    const player = (await waitForGroundedPlayer(server)).name;
    await tryCommand(server, `op ${player}`);
    await cinematicWorld(server);
    await world.command(server, "time set 10000");
    await world.command(server, `gamemode creative ${player}`);
    await world.command(server, "forceload add -112 0 112 112");
    const scene = await runColonyTask(server, SCENE, { origin: ORIGIN });
    run.note("scene", scene);
    await world.command(server, `tp ${player} ${ORIGIN.x} ${ORIGIN.y + 1} ${ORIGIN.z + 1}`);
    const members = scene.members as Array<{x:number;y:number;z:number}>;
    await world.command(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);
    const founded = await foundPromoColonyFast(server, player, members);
    const colony_id = founded.colony_id;
    await admitPromoSettlersFast(server, player, colony_id, RESIDENTS);
    run.note("crew", await runColonyTask(server, CREW, { colony_id, origin: ORIGIN }));
    await setMaintainDemandsFast(server, player, [
      { at: at(21, 3, 2), item: "minecraft:oak_planks", count: 256 },
      { at: at(1, 1, -6), item: "minecraft:cooked_beef", count: 256 },
      { at: at(21, 3, 3), item: "minecraft:stick", count: 128 },
    ]);
    // The book stays in hand through the shot, so each resident near the camera carries their card.
    await world.command(server, `execute if items entity ${player} weapon.mainhand folkways:colony_book`);
    await world.command(server, `gamemode spectator ${player}`);
    await world.command(server, `sable assemble area ${ORIGIN.x - 25} ${ORIGIN.y - 10} ${ORIGIN.z - 10} ${ORIGIN.x + 36} ${ORIGIN.y + 9} ${ORIGIN.z + 10}`);
    run.note("flight", await runColonyTask(server, FLIGHT, { colony_id, speed: SPEED }));
    await tick.sprint(server, WARMUP_TICKS);
    run.note("warmup", await runColonyTask(server, WORK, { colony_id }));
    const eye = at(CAMERA.x, CAMERA.y, CAMERA.z);
    const aim = at(AIM.x, AIM.y, AIM.z);
    const look = cameraLookingAt(eye, aim);
    await world.command(server, `tp ${player} ${eye.x} ${eye.y} ${eye.z} ${look.yaw} ${look.pitch}`);
    await safeAction(() => screen.dismiss(client));
    await hideHud(client);
    await setFov(client, 55);
    await writeLook(client, look);
    await safeAction(() => frames(client, 30));
    await sleepMs(4000);
    const samples: any[] = [];
    const gates = await promoClip(run, client, server, "01-airship", {
      subject: "Close beside its course, a wooden sky ship with a levitite keel sails through the frame bow first in warm afternoon light; the book in hand floats a card over each resident as they harvest wheat, fish from a deck pond, shear sheep, craft and cook under a striped awning and carry goods to the bow",
      worldState: {
        colony_id,
        camera: { eye, aim, ...look },
        motion: "unpaused Sable physics with a cinematic velocity controller",
      },
      seconds: SECONDS,
      during: async () => {
        await runColonyTask(server, START, {});
        for (let i = 0; i < SAMPLES; i++) {
          await sleepMs(2000);
          samples.push(await runColonyTask(server, WORK, { colony_id }));
        }
      },
    });
    run.note("shot", { ...gates, samples });
    run.note("shaders", shaderEvidence(clientInstance));
    expect(samples.every(s => s.aboard === RESIDENTS), "all residents must remain on the moving structure").toBe(true);
    expect(samples.at(-1).x - samples[0].x, "the ship must actually traverse the shot").toBeGreaterThan(60);
    expect(samples.some(s => s.wheat > 0), "farmers must harvest real crops aboard").toBe(true);
    expect(samples.at(-1).wood, "production must continue during flight").toBeGreaterThan(samples[0].wood);
    expect(samples.some(s => s.planks > 0), "residents must produce and deliver planks aboard").toBe(true);
    expect(samples.some(s => s.workers.some((w: any) => w.doing !== "idle")), "visible residents must do real colony work").toBe(true);
    const jobs = new Set(samples.flatMap(s => s.workers.map((w: any) => job(w.doing))).filter(j => !LEISURE.has(j)));
    run.note("jobs", [...jobs]);
    expect(jobs.size, `residents must spread over several trades, saw ${[...jobs].join(", ")}`).toBeGreaterThanOrEqual(4);
  } catch (error) {
    pair?.failing(error);
    run.note("failure", String(error));
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});


// "Doing[what=folkways:walking, toward=Optional[folkways:crafting], ...]" is walking toward crafting: the job is crafting.
function job(doing: string) {
  const toward = /toward=Optional\[([^\]]+)\]/.exec(doing)?.[1];
  return toward ?? /what=([^,\]]+)/.exec(doing)?.[1] ?? doing;
}

const SCENE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.airship-scene",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.entity.EntityType",
      "net.minecraft.world.entity.MobSpawnType",
      "net.minecraft.world.level.block.*",
      "net.minecraft.world.level.block.state.BlockState",
      "net.minecraft.world.level.block.state.properties.*",
      "net.minecraft.core.Direction",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.*",
    ],
    body: `
var level=ctx.server().overworld();
var args=Args.of(ctx); var o=(Map<?,?>)args.value("origin");
var base=new BlockPos(((Number)o.get("x")).intValue(),((Number)o.get("y")).intValue(),((Number)o.get("z")).intValue());
SubLevelPhysicsSystem.require(level).setPaused(true);
var placed=new ArrayList<BlockPos>();
java.util.function.BiConsumer<BlockPos,BlockState> put=(p,s)->{level.setBlock(p,s,3);placed.add(p.immutable());};
java.util.function.BiConsumer<BlockPos,Block> set=(p,b)->put.accept(p,b.defaultBlockState());
// the hull: a keel of levitite under a spruce hull that tapers to the bow and narrows with depth
int DEPTH=8;int[] INSET={0,0,0,1,1,2,3,5,7};
int MINX=-25,MAXX=36,MINY=-10,MAXY=9,Z=10;
java.util.function.IntBinaryOperator hw=(x,d)->{
  if(d<0||d>DEPTH)return -1;
  int bow=26-(int)Math.round(d*1.4),stern=-22+Math.max(0,d-4)*2;
  if(x>bow||x<stern)return -1;
  double w=8;
  if(x>6){double t=(x-6.0)/(bow-6.0+1);w=8*Math.sqrt(Math.max(0,1-t*t));}
  if(x<-19)w=7;
  int r=(int)Math.round(w)-INSET[d];return r<0?-1:r;
};
Block keel=modded("aeronautics:levitite",Blocks.AMETHYST_BLOCK);
for(int d=0;d<=DEPTH;d++)for(int x=-24;x<=28;x++) {
  int w=hw.applyAsInt(x,d);if(w<0)continue;
  for(int z=-w;z<=w;z++) {
    int az=Math.abs(z);
    boolean fore=az<=hw.applyAsInt(x+1,d),aft=az<=hw.applyAsInt(x-1,d),under=az<=hw.applyAsInt(x,d+1);
    // thick enough that each layer of the shell meets the one above it face to face, not only at an edge
    int inner=Math.min(Math.min(hw.applyAsInt(x,d+1),Math.min(hw.applyAsInt(x+1,d),hw.applyAsInt(x-1,d))),
      Math.min(hw.applyAsInt(x+1,d+1),hw.applyAsInt(x-1,d+1)));
    boolean shell=az==w||az>=inner||d==DEPTH;
    if(d>0&&!shell)continue;
    Block b;
    if(d==0)b=az==w||!fore||!aft?Blocks.STRIPPED_DARK_OAK_WOOD:Blocks.SPRUCE_PLANKS;
    else if(d==1)b=Blocks.DARK_OAK_PLANKS;
    else if(d==2)b=az==w&&Math.floorMod(x,4)==0&&x>-18&&x<18?Blocks.GLASS:Blocks.SPRUCE_PLANKS;
    else if(d==3)b=Blocks.STRIPPED_BIRCH_WOOD;
    else if(d>=DEPTH-1)b=keel;
    else b=Blocks.SPRUCE_PLANKS;
    set.accept(base.offset(x,-d,z),b);
  }
}
// lamps behind the portholes
for(int x=-16;x<18;x+=4) {
  int w=hw.applyAsInt(x,2);
  set.accept(base.offset(x,-2,w-1),Blocks.OCHRE_FROGLIGHT);set.accept(base.offset(x,-2,1-w),Blocks.OCHRE_FROGLIGHT);
}
// rail wherever the deck edge shows past the neighbouring columns
for(int x=-22;x<=26;x++) {
  int w=hw.applyAsInt(x,0);if(w<0)continue;
  for(int z=0;z<=w;z++)if(z==w||z>hw.applyAsInt(x+1,0)||z>hw.applyAsInt(x-1,0)) {
    set.accept(base.offset(x,1,z),Blocks.SPRUCE_FENCE);set.accept(base.offset(x,1,-z),Blocks.SPRUCE_FENCE);
  }
}
// forecastle: a raised bow deck from x=18, reached by a short stair, and a bowsprit
for(int x=18;x<=26;x++) {
  int w=hw.applyAsInt(x,0);if(w<0)continue;
  for(int z=-w;z<=w;z++) {
    boolean edge=Math.abs(z)==w||Math.abs(z)>hw.applyAsInt(x+1,0)||x==18;
    if(x==18&&Math.abs(z)<=1) {
      level.setBlock(base.offset(x,1,z),Blocks.AIR.defaultBlockState(),3);
      put.accept(base.offset(x-1,1,z),facing(Blocks.SPRUCE_STAIRS,Direction.EAST));put.accept(base.offset(x,2,z),facing(Blocks.SPRUCE_STAIRS,Direction.EAST));
      continue;
    }
    set.accept(base.offset(x,1,z),edge?Blocks.DARK_OAK_PLANKS:Blocks.SPRUCE_PLANKS);
    set.accept(base.offset(x,2,z),edge?Blocks.STRIPPED_DARK_OAK_WOOD:Blocks.SPRUCE_PLANKS);
    if(edge&&x>18)set.accept(base.offset(x,3,z),Blocks.SPRUCE_FENCE);
  }
}
for(int i=0;i<9;i++) {
  var log=Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.X);
  put.accept(base.offset(26+i,2+i/3,0),log);
  // each step up rests on the log below it, so the spar stays one connected piece
  if(i%3==0&&i>0)put.accept(base.offset(26+i,1+i/3,0),log);
}
set.accept(base.offset(21,3,-3),Blocks.BARREL);set.accept(base.offset(22,3,-3),Blocks.BARREL);set.accept(base.offset(21,4,-3),Blocks.BARREL);
set.accept(base.offset(23,3,-1),Blocks.HAY_BLOCK);
// stern cabin x -22..-13 with a gallery of windows and a railed roof deck
for(int x=-22;x<=-13;x++)for(int z=-7;z<=7;z++) {
  boolean wall=x==-22||x==-13||Math.abs(z)==7;
  if(wall)for(int y=1;y<=4;y++) {
    boolean corner=(x==-22||x==-13)&&Math.abs(z)==7;
    if(x==-13&&Math.abs(z)<=1&&y<=2)continue;
    boolean window=!corner&&(y==2||y==3)&&(Math.abs(z)==7&&Math.floorMod(x,3)==0||x==-22&&Math.floorMod(z,2)==0);
    set.accept(base.offset(x,y,z),corner?Blocks.STRIPPED_DARK_OAK_LOG:window?Blocks.GLASS_PANE:y==4?Blocks.DARK_OAK_PLANKS:Blocks.SPRUCE_PLANKS);
  }
  set.accept(base.offset(x,5,z),wall?Blocks.STRIPPED_DARK_OAK_WOOD:Blocks.SPRUCE_PLANKS);
  if(x==-22||Math.abs(z)==7)set.accept(base.offset(x,6,z),Blocks.DARK_OAK_FENCE);
}
for(int[] c:new int[][]{{-22,-7},{-22,7},{-13,-7},{-13,7}})set.accept(base.offset(c[0],7,c[1]),Blocks.LANTERN);
set.accept(base.offset(-18,6,0),Blocks.CARTOGRAPHY_TABLE);
var members=new ArrayList<Map<String,Integer>>();
java.util.function.Consumer<BlockPos> member=p->members.add(Map.of("x",p.getX(),"y",p.getY(),"z",p.getZ()));
// a row of bunks inside the cabin
Block[] beds={Blocks.RED_BED,Blocks.WHITE_BED,Blocks.YELLOW_BED,Blocks.LIGHT_BLUE_BED};
for(int z=-6;z<=6;z++) {
  var p=base.offset(-20,1,z);Block bed=beds[Math.floorMod(z,beds.length)];
  put.accept(p,bed.defaultBlockState().setValue(BedBlock.FACING,Direction.WEST));
  put.accept(p.west(),bed.defaultBlockState().setValue(BedBlock.FACING,Direction.WEST).setValue(BedBlock.PART,BedPart.HEAD));
  member.accept(p);
}
// stern propellers turned by motors in the hull
Block motor=modded("create:creative_motor",Blocks.AIR),propeller=modded("aeronautics:wooden_propeller",Blocks.AIR);
boolean engine=motor!=Blocks.AIR&&propeller!=Blocks.AIR;
if(engine)for(int z:new int[]{-4,4}) {
  put.accept(base.offset(-22,-3,z),pointing(motor,Direction.WEST));
  put.accept(base.offset(-23,-3,z),pointing(propeller,Direction.WEST));
}
// workshop under a striped awning along the far rail, facing the camera
for(int x=-11;x<=9;x++)for(int z=-7;z<=-4;z++)
  set.accept(base.offset(x,z==-7?5:4,z),Math.floorMod(x,2)==0?Blocks.RED_WOOL:Blocks.WHITE_WOOL);
for(int x:new int[]{-11,-1,9}) {
  for(int y=1;y<=3;y++)set.accept(base.offset(x,y,-4),Blocks.DARK_OAK_FENCE);
  for(int y=x==-1?2:1;y<=4;y++)set.accept(base.offset(x,y,-7),Blocks.DARK_OAK_FENCE);
}
put.accept(base.offset(-10,1,-6),Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING,Direction.UP));
var supplyAt=base.offset(-9,1,-6);put.accept(supplyAt,facing(Blocks.CHEST,Direction.SOUTH));member.accept(supplyAt);
put.accept(base.offset(-8,1,-6),Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING,Direction.UP));
var tableAt=base.offset(-6,1,-6);set.accept(tableAt,Blocks.CRAFTING_TABLE);member.accept(tableAt);
var benchAt=base.offset(-5,1,-6);set.accept(benchAt,Blocks.CRAFTING_TABLE);member.accept(benchAt);
set.accept(base.offset(-4,1,-6),Blocks.FLETCHING_TABLE);
set.accept(base.offset(-3,1,-6),Blocks.CAMPFIRE);
var smokerAt=base.offset(0,1,-6);put.accept(smokerAt,facing(Blocks.SMOKER,Direction.SOUTH));member.accept(smokerAt);
var kitchenAt=base.offset(1,1,-6);put.accept(kitchenAt,facing(Blocks.CHEST,Direction.SOUTH));member.accept(kitchenAt);
put.accept(base.offset(2,1,-6),Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING,Direction.UP));
set.accept(base.offset(3,1,-6),Blocks.GRINDSTONE);
put.accept(base.offset(4,1,-6),facing(Blocks.BLAST_FURNACE,Direction.SOUTH));
put.accept(base.offset(6,1,-6),facing(Blocks.ANVIL,Direction.EAST));
set.accept(base.offset(7,1,-6),Blocks.LOOM);
set.accept(base.offset(8,1,-6),Blocks.BARREL);
for(int x=-10;x<=8;x+=3)set.accept(base.offset(x,1,-7),Blocks.BARREL);
// wheat plot on the camera side, framed in pale wood, with bread and hay beside it
for(int x=-11;x<=-4;x++)for(int z=1;z<=6;z++) {
  boolean frame=x==-11||x==-4||z==1||z==6;
  if(frame)set.accept(base.offset(x,0,z),Blocks.STRIPPED_OAK_WOOD);
  else {
    put.accept(base.offset(x,0,z),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));
    put.accept(base.offset(x,1,z),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,Math.floorMod(x+z,2)==0?7:2));
  }
}
set.accept(base.offset(-11,1,1),Blocks.COMPOSTER);
set.accept(base.offset(-12,1,6),Blocks.HAY_BLOCK);set.accept(base.offset(-12,2,6),Blocks.HAY_BLOCK);
var breadAt=base.offset(-12,1,3);put.accept(breadAt,facing(Blocks.CHEST,Direction.SOUTH));member.accept(breadAt);
// chicken pen before the bow, fenced against the rail with a gap for the herders
for(int x=3;x<=10;x++)for(int z=1;z<=6;z++) {
  boolean edge=x==3||x==10||z==1;
  if(edge&&!(x==6&&z==1))set.accept(base.offset(x,1,z),Blocks.OAK_FENCE);
}
for(int x=4;x<=9;x++)for(int z=2;z<=7;z++)set.accept(base.offset(x,0,z),Blocks.GRASS_BLOCK);
set.accept(base.offset(9,1,6),Blocks.HAY_BLOCK);
put.accept(base.offset(4,1,6),Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL,3));
// a fishing pond midships on the camera side, sunk into the deck on a plank floor
for(int x=-3;x<=2;x++)for(int z=3;z<=7;z++) {
  boolean rim=x==-3||x==2||z==3||z==7;
  if(rim)set.accept(base.offset(x,0,z),Blocks.STRIPPED_OAK_WOOD);
  else {set.accept(base.offset(x,-1,z),Blocks.SPRUCE_PLANKS);set.accept(base.offset(x,0,z),Blocks.WATER);}
}
set.accept(base.offset(-1,1,5),Blocks.LILY_PAD);
set.accept(base.offset(2,1,7),Blocks.BARREL);
// finished goods on the forecastle, the far end from the supply chest at the stern
var planksAt=base.offset(21,3,2);put.accept(planksAt,facing(Blocks.CHEST,Direction.WEST));member.accept(planksAt);
var partsAt=base.offset(21,3,3);put.accept(partsAt,facing(Blocks.CHEST,Direction.WEST));member.accept(partsAt);
// lanterns along both rails and a planter toward the bow
for(int x=-10;x<=16;x+=6) {
  int w=hw.applyAsInt(x,0);
  set.accept(base.offset(x,2,w),Blocks.LANTERN);set.accept(base.offset(x,2,-w),Blocks.LANTERN);
}
Block[] flowers={Blocks.POPPY,Blocks.DANDELION,Blocks.CORNFLOWER,Blocks.ALLIUM,Blocks.AZURE_BLUET};
for(int x=12;x<=16;x++) {
  int w=hw.applyAsInt(x,0)-1;
  set.accept(base.offset(x,1,w),Blocks.MOSS_BLOCK);set.accept(base.offset(x,2,w),flowers[Math.floorMod(x,flowers.length)]);
}
for(var p:placed) {
  var state=level.getBlockState(p);
  if(state.getBlock() instanceof FenceBlock||state.getBlock() instanceof StairBlock) {
    var shaped=Block.updateFromNeighbourShapes(state,level,p);
    if(shaped!=state)level.setBlock(p,shaped,2);
  }
}
var supply=(Container)level.getBlockEntity(supplyAt);
for(int i=0;i<8;i++) supply.setItem(i,new ItemStack(Items.OAK_LOG,64));
supply.setItem(8,new ItemStack(Items.BEEF,64));supply.setItem(9,new ItemStack(Items.COAL,64));
supply.setItem(10,new ItemStack(Items.WHEAT_SEEDS,64));supply.setItem(11,new ItemStack(Items.WHEAT_SEEDS,64));
((Container)level.getBlockEntity(smokerAt)).setItem(1,new ItemStack(Items.COAL,64));
for(int i=0;i<6;i++)((Container)level.getBlockEntity(breadAt)).setItem(i,new ItemStack(Items.BREAD,64));
int sheep=0;
DyeColor[] fleeces={DyeColor.WHITE,DyeColor.BROWN,DyeColor.LIGHT_GRAY,DyeColor.WHITE};
for(int i=0;i<fleeces.length;i++) {
  var one=EntityType.SHEEP.spawn(level,base.offset(5+(i%2)*3,1,3+(i/2)*3),MobSpawnType.COMMAND);
  if(one!=null){one.setColor(fleeces[i]);sheep++;}
}
var connected=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();
connected.add(base);queue.add(base);
while(!queue.isEmpty()) {
  var pos=queue.removeFirst();
  for(var dir:Direction.values()) {
    var next=pos.relative(dir);var rel=next.subtract(base);
    if(rel.getX()<MINX||rel.getX()>MAXX||rel.getY()<MINY||rel.getY()>MAXY||Math.abs(rel.getZ())>Z)continue;
    if(!level.isEmptyBlock(next)&&connected.add(next))queue.add(next);
  }
}
int detached=0;
for(var pos:BlockPos.betweenClosed(base.offset(MINX,MINY,-Z),base.offset(MAXX,MAXY,Z)))
  if(!level.isEmptyBlock(pos)&&!connected.contains(pos)){level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);detached++;}
return Sync.stamp(Map.of("ok",true,"members",members,"keel",BuiltInRegistries.BLOCK.getKey(keel).toString(),
  "engine",engine,"sheep",sheep,"detached_removed",detached));
`,
    members: `
static BlockState facing(Block block,Direction dir) {
  return block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,dir);
}
static BlockState pointing(Block block,Direction dir) {
  return block.defaultBlockState().setValue(BlockStateProperties.FACING,dir);
}
static Block modded(String id,Block fallback) {
  var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
  return block==Blocks.AIR?fallback:block;
}
`,
  }),
});


const CREW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.airship-crew",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.*",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "io.github.izakyl.folkways.core.api.work.*",
      "io.github.izakyl.folkways.core.api.terms.*",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.item.*",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var o=(Map<?,?>)args.value("origin");int x=((Number)o.get("x")).intValue(),y=((Number)o.get("y")).intValue(),z=((Number)o.get("z")).intValue();
ZoneDrafts.zone(level,colony,ResourceLocation.parse("folkways:farm"),new BlockPos(x-10,y,z+2),new BlockPos(x-5,y,z+5),
  ColonySettings.empty().with("crop",new ColonySettings.Value.Choice(ResourceLocation.parse("minecraft:wheat")))).orElseThrow();
ZoneDrafts.zone(level,colony,ResourceLocation.parse("folkways:pasture"),new BlockPos(x+4,y,z+2),new BlockPos(x+9,y,z+7),
  ColonySettings.empty().with("animal",new ColonySettings.Value.Choice(ResourceLocation.parse("minecraft:sheep")))
  .with("target",new ColonySettings.Value.Count(8))).orElseThrow();
// one spot per fisher, each on its own side of the pond, so all three cast at once and their cards stay apart
for(int[] spot:new int[][]{{-3,5},{-1,3},{2,5}})
  ZoneDrafts.zone(level,colony,ResourceLocation.parse("folkways:fish"),new BlockPos(x+spot[0],y,z+spot[1]),new BlockPos(x+spot[0],y,z+spot[1]),ColonySettings.empty()).orElseThrow();
// each trade starts at its own post, spread along the deck from stern to bow
String[] roles={"farming","farming","herding","herding","fishing","fishing","fishing","crafting","crafting","crafting","hauling","hauling"};
double[][] posts={{-9,0},{-6,0},{5,4},{8,4},{-3,5},{-1,3},{2,5},{-6,-3},{-4,-3},{0,-3},{-11,-2},{15,0}};
int i=0;var crew=new ArrayList<Object>();
for(var resident:colony.residents()) {
  var entity=level.getEntity(resident.id());var body=Bodies.of(entity).orElseThrow();
  int index=i++;String role=roles[index%roles.length];
  for(var vocation:Vocations.all()) body.licences().trade(vocation).ifPresent(trade->
    body.licences().allow(trade,vocation.id().getPath().equals(role)||vocation.id().getPath().equals("hauling")));
  body.pack().insert(new ItemStack(Items.BREAD,8));
  if(role.equals("farming")){body.pack().insert(new ItemStack(Items.IRON_HOE));body.pack().insert(new ItemStack(Items.WHEAT_SEEDS,64));}
  if(role.equals("herding")){body.pack().insert(new ItemStack(Items.SHEARS));body.pack().insert(new ItemStack(Items.WHEAT,32));}
  if(role.equals("fishing"))body.pack().insert(new ItemStack(Items.FISHING_ROD));
  var post=posts[index%posts.length];
  entity.teleportTo(x+post[0]+0.5,y+1.1,z+post[1]+0.5);
  crew.add(Map.of("id",resident.id().toString(),"role",role));
}
return Sync.stamp(Map.of("ok",true,"crew",crew));
`,
  }),
});

const FLIGHT = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.airship-flight",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "dev.ryanhcode.sable.api.sublevel.SubLevelContainer",
      "dev.ryanhcode.sable.sublevel.ServerSubLevel",
      "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem",
      "dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent",
      "net.neoforged.neoforge.common.NeoForge",
      "net.minecraft.server.MinecraftServer",
      "org.joml.Vector3d",
    ],
    body: `
var level=ctx.server().overworld();
ServerSubLevel sub=null;long biggest=0;
for(var candidate:SubLevelContainer.getContainer(level).getAllSubLevels()){
  var b=candidate.getPlot().getBoundingBox();long size=(long)(b.maxX()-b.minX()+1)*(b.maxY()-b.minY()+1)*(b.maxZ()-b.minZ()+1);
  if(size>biggest){biggest=size;sub=(ServerSubLevel)candidate;}
}
final ServerSubLevel ship=sub;
double height=sub.logicalPose().position().y();
double speed=Args.of(ctx).number("speed");
if(sub.getUserDataTag()==null)sub.setUserDataTag(new net.minecraft.nbt.CompoundTag());
sub.getUserDataTag().putBoolean("filmActor",true);
NeoForge.EVENT_BUS.addListener((ForgeSablePrePhysicsTickEvent event)->{
  if(event.getPhysicsSystem().getLevel()!=level||ship.isRemoved())return;
  var handle=event.getPhysicsSystem().getPhysicsHandle(ship);if(handle==null||!handle.isValid())return;
  double vx=ship.getUserDataTag().getBoolean("filmMoving")?speed:0.0;
  var velocity=new Vector3d(vx,Math.clamp((height-ship.logicalPose().position().y())*4.0,-5,5),0);
  velocity.sub(handle.getLinearVelocity());
  handle.addLinearAndAngularVelocity(velocity,new Vector3d(handle.getAngularVelocity()).negate());
});
SubLevelPhysicsSystem.require(level).setPaused(false);
return Sync.stamp(Map.of("ok",true,"structure",sub.getUniqueId().toString(),"height",height,"physics_paused",false));
`,
  }),
});
const START = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.airship-start",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "dev.ryanhcode.sable.api.sublevel.SubLevelContainer",
      "dev.ryanhcode.sable.sublevel.ServerSubLevel",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var level=ctx.server().overworld();
ServerSubLevel sub=null;
for(var candidate:SubLevelContainer.getContainer(level).getAllSubLevels())
  if(candidate instanceof ServerSubLevel ship&&ship.getUserDataTag()!=null&&ship.getUserDataTag().getBoolean("filmActor"))sub=ship;
sub.getUserDataTag().putBoolean("filmMoving",true);return Sync.stamp(Map.of("ok",true));
`,
  }),
});
const WORK = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.airship-work",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "dev.ryanhcode.sable.api.sublevel.SubLevelContainer",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.terms.*",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.Items",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
dev.ryanhcode.sable.sublevel.ServerSubLevel sub=null;
for(var candidate:SubLevelContainer.getContainer(level).getAllSubLevels())
  if(candidate instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel ship&&ship.getUserDataTag()!=null&&ship.getUserDataTag().getBoolean("filmActor"))sub=ship;
int aboard=0,planks=0,sticks=0,cooked=0,wheat=0;var workers=new ArrayList<Object>();
for(var resident:colony.residents()){
  var entity=level.getEntity(resident.id());if(entity==null)continue;
  var body=Bodies.of(entity).orElseThrow();var pos=WorldSpaces.at(entity);
  wheat+=body.pack().count(Items.WHEAT);
  if(pos.realm() instanceof Realm.Frame frame&&frame.structure().equals(sub.getUniqueId()))aboard++;
  workers.add(Map.of("id",resident.id().toString(),"at",pos.toString(),
    "doing",body.doing().map(Object::toString).orElse("idle"),
    "pack",body.pack().contents().stream().map(Object::toString).toList()));
}
for(var view:colony.views(server))for(var pos:view.blocks(ResourceLocation.parse("folkways:store")))if(level.getBlockEntity(pos) instanceof Container chest)
  for(int i=0;i<chest.getContainerSize();i++){var stack=chest.getItem(i);if(stack.is(Items.OAK_PLANKS))planks+=stack.getCount();if(stack.is(Items.STICK))sticks+=stack.getCount();if(stack.is(Items.COOKED_BEEF))cooked+=stack.getCount();if(stack.is(Items.WHEAT))wheat+=stack.getCount();}
var said=new LinkedHashMap<String,Object>();
said.put("ok",true);said.put("aboard",aboard);said.put("workers",workers);said.put("planks",planks);said.put("sticks",sticks);
said.put("wood",planks+sticks/2);said.put("cooked",cooked);said.put("wheat",wheat);
said.put("x",sub.logicalPose().position().x());said.put("y",sub.logicalPose().position().y());
return said;
`,
  }),
});
