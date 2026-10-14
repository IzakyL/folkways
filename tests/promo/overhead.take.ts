import { expect, test } from "@playwright/test";
import { defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeAction, tryCommand } from "../shared/harness/bw-helpers";
import { admitPromoSettlersFast, foundPromoColonyFast } from "./fast-setup";
import { CURSOR, holdKey, KEY, TOPS, View, type Cell, type Drawing, type Vec } from "./overhead-view";
import { cinematicWorld, launchPromoPair, promoClip, setTickRate, sleepMs, take, type PromoPair } from "./promo-kit";
import { FILM_LOOK, LENS_ON, OVERHEAD_LENS, MINIATURE_LOOK, shaderEvidence } from "../shared/capture/shaders";

const LAND_CONFIG = "blockwright.toml#promo-land";
const RESIDENTS = 10;

// The plain north of a lake in the promo-land world, under a hill that rises to its west and north. The patterns
// each have a shot of their own, on ground of their own: overhead-patterns.take.ts.
const BASE = { x: 34, z: -12 };
const FIELD = { minX: 14, maxX: 24, minZ: -32, maxZ: -22 };

/** A drawing, and where the view from above looks at it from: the spot in the middle of the picture, the heading, the distance. */
type Draft = Drawing & { centre: Vec; yaw: number; off: number };

// The road the residents build in the first shot.
const FIRST: Draft = { gesture: "line", cells: [[30, -28], [58, -24]], centre: { x: 44, y: 70, z: -26 }, yaw: 180, off: 30 };

const STOCK: Record<string, number> = {
  "minecraft:andesite": 256, "minecraft:cobblestone": 256, "minecraft:polished_andesite": 128,
  "minecraft:stone_bricks": 256, "minecraft:dirt": 256, "minecraft:spruce_fence": 64, "minecraft:lantern": 24,
};

test("the view from above: the book worked by mouse from a flying camera, and a road drawn from it built by the residents", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo);
  const run = take("overhead");
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair("overhead", { config: LAND_CONFIG,
      look: { options: { ...FILM_LOOK, ...MINIATURE_LOOK }, patches: OVERHEAD_LENS } });
    const { server, client, clientInstance } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    await tryCommand(server, `op ${player}`);
    await cinematicWorld(server);
    await world.command(server, "time set 6500");

    const site = await runColonyTask(server, STAGE, { base: BASE, field: FIELD, residents: RESIDENTS });
    run.note("stage", site);
    const cells = [BASE, ...FIRST.cells.map(([x, z]) => ({ x, z }))];
    const surveyed = await runColonyTask(server, TOPS, { cells });
    run.note("felled", surveyed.felled);
    const tops = new Map(cells.map((cell, index) => [`${cell.x},${cell.z}`, Number((surveyed.tops as number[])[index])]));
    const ground = ([x, z]: Cell): Vec => ({ x: x + 0.5, y: tops.get(`${x},${z}`)! + 1, z: z + 0.5 });
    const standing = (at: { x: number; z: number }) => `${at.x + 0.5} ${tops.get(`${at.x},${at.z}`)! + 1} ${at.z + 0.5}`;

    await world.command(server, `tp ${player} ${standing(BASE)} 180 14`);
    await world.command(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);
    const founded = await foundPromoColonyFast(server, player, site.members as Vec[]);
    const colony_id = founded.colony_id;
    run.note("admitted", await admitPromoSettlersFast(server, player, colony_id, RESIDENTS));
    run.note("crew", await runColonyTask(server, CREW, { colony_id, base: BASE, field: FIELD, y: Number(site.field_y) }));
    run.note("stocked", await runColonyTask(server, STOCK_ITEMS, { chests: site.chests, items: STOCK }));
    await tryCommand(server, "kill @e[type=minecraft:item]");
    // In creative the book raises a pattern at once; a survival player's is handed to the residents.
    await world.command(server, `gamemode survival ${player}`);
    await world.command(server, `tp ${player} ${standing(BASE)} 180 14`);
    await tick.sprint(server, 200);

    await safeAction(() => screen.dismiss(client));
    await client.run(CURSOR, {});
    await safeAction(() => frames(client, 30));
    await sleepMs(3_000);

    const view = new View(client, ground);
    const receipts: any = {};
    let started = 0;
    const now = () => Math.round((Date.now() - started) / 100) / 10;
    const marks: Record<string, number> = {};

    let first: any;
    try {
      first = await promoClip(run, client, server, "01-overhead", {
        subject: "The book's view from above: V lifts the camera off the player, the walking keys fly it, the wheel "
          + "moves it nearer and a middle drag turns it, each key lighting in the hints as it is pressed; cards follow "
          + "the mouse and the layer buttons put markings away; a road is drawn with two clicks, handed to the road "
          + "pattern, and built by the residents",
        worldState: { colony_id, base: BASE, field: FIELD, road: FIRST },
        seconds: 1,
        during: async () => {
          started = Date.now();
          await sleepMs(1_800);
          await holdKey(client, KEY.V);
          await tryCommand(server, `attribute ${player} ${LENS_ON}`);
          await sleepMs(1_500);
          marks.above = now();

          // The controls, one after another: fly, pull back, look around.
          await view.fly([KEY.W], 0.7);
          await sleepMs(500);
          await view.dolly(-3);
          await sleepMs(500);
          await view.turn(-45, 0, 1_100);
          await sleepMs(400);
          await view.turn(45, 6, 1_100);
          await sleepMs(500);

          // What the colony has marked answers the mouse, and each kind of marking can be put away.
          await view.goTo({ x: (FIELD.minX + FIELD.maxX) / 2, y: Number(site.field_y), z: (FIELD.minZ + FIELD.maxZ) / 2 }, 180, 26);
          await view.hover({ x: 19.5, y: Number(site.field_y) + 1, z: -26.5 }, 700);
          await sleepMs(2_000);
          // One press puts the zones' cards away, the next their outlines too, the third brings both back.
          for (const pause of [1_100, 1_100, 600]) {
            await view.press("folkways.overhead.layer.zones");
            await sleepMs(pause);
          }
          const farmer = ((await runColonyTask(server, RESIDENTS_AT, { colony_id })).residents as any[])
            .find(one => one.x >= FIELD.minX - 4 && one.x <= FIELD.maxX + 4 && one.z >= FIELD.minZ - 4 && one.z <= FIELD.maxZ + 4);
          if (farmer) {
            await view.hover({ x: farmer.x, y: farmer.y + 1.0, z: farmer.z }, 500);
            await sleepMs(1_600);
          }
          marks.cards = now();

          // A line of two clicks on the plain, handed to the road pattern.
          receipts.first = await view.draft("road", FIRST, 1.0);
          await view.goTo(FIRST.centre, FIRST.yaw, FIRST.off);
          marks.handed = now();

          // The residents fetch what the road is made of and build it, with the game running faster than the film.
          await setTickRate(server, 60);
          let built = { placed: 0, of: 1 };
          for (let wait = 0; wait < 300 && built.placed < built.of; wait++) {
            await sleepMs(1_000);
            const sites = (await runColonyTask(server, SITES, { colony_id })).sites as any[];
            if (!sites.length && built.placed > 0) built = { placed: built.of, of: built.of };
            else if (sites.length) built = { placed: Number(sites[0].placed), of: Number(sites[0].of) };
          }
          await setTickRate(server, 20);
          receipts.built = built;
          marks.built = now();
          await view.turn(20, 0, 2_600);
          await sleepMs(1_500);
          marks.end = now();
        },
      });
    } finally {
      await setTickRate(server, 20);
      run.note("receipts", receipts);
    }
    run.note("shot", { ...first, marks });
    expect(receipts.built.placed, "the residents must finish the road handed off from above").toBe(receipts.built.of);

    await holdKey(client, KEY.V);
    run.note("shaders", shaderEvidence(clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

// A small holding on the plain: chests by where the player stands, a wheat field, and the beds sealed in rock under
// the meadow, out of the picture.
const STAGE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.overhead-stage",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.Direction",
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
var base=(Map<?,?>)args.value("base");var field=(Map<?,?>)args.value("field");int residents=(int)args.integer("residents");
int bx=n(base,"x"),bz=n(base,"z");int oy=top(level,bx,bz)+1;
int x0=n(field,"minX"),x1=n(field,"maxX"),z0=n(field,"minZ"),z1=n(field,"maxZ");
int fy=top(level,(x0+x1)/2,(z0+z1)/2);
for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++){
  var ground=new BlockPos(x,fy,z);
  for(int y=1;y<=5;y++)level.setBlock(ground.above(y),Blocks.AIR.defaultBlockState(),2);
  for(int y=1;y<=3;y++)if(!level.getBlockState(ground.below(y)).isSolid())level.setBlock(ground.below(y),Blocks.DIRT.defaultBlockState(),2);
  if(x==(x0+x1)/2&&z>z0&&z<z1){level.setBlock(ground,Blocks.WATER.defaultBlockState(),2);continue;}
  level.setBlock(ground,Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7),2);
  level.setBlock(ground.above(),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,(x+z)%3==0?5:7),2);
}
for(int x=-16;x<=16;x++)for(int z=-14;z<=10;z++){
  var ground=new BlockPos(bx+x,top(level,bx+x,bz+z),bz+z);
  for(int y=1;y<=2;y++){
    var plant=level.getBlockState(ground.above(y));
    if(plant.is(Blocks.SHORT_GRASS)||plant.is(Blocks.TALL_GRASS)||plant.is(Blocks.FERN)||plant.is(Blocks.LARGE_FERN))
      level.setBlock(ground.above(y),Blocks.AIR.defaultBlockState(),2);
  }
}
var members=new ArrayList<Object>();var chests=new ArrayList<Object>();
for(int i=0;i<12;i++){
  int x=bx-12+i,z=bz+5;
  var chest=new BlockPos(x,top(level,x,z)+1,z);
  level.setBlock(chest,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH),2);
  var cell=Map.of("x",chest.getX(),"y",chest.getY(),"z",chest.getZ());members.add(cell);
  if(i<2){var box=(Container)level.getBlockEntity(chest);
    for(int slot=0;slot<box.getContainerSize();slot++)box.setItem(slot,new ItemStack(Items.BREAD,64));
    box.setChanged();}
  else chests.add(cell);
}
int cellar=oy-10;
for(int x=6;x<=22;x++)for(int y=-1;y<=1;y++)for(int z=-9;z<=-3;z++)
  level.setBlock(new BlockPos(bx+x,cellar+y,bz+z),Blocks.STONE.defaultBlockState(),2);
for(int i=0;i<residents;i++){
  var foot=new BlockPos(bx+7+(i%8)*2,cellar,bz-7+(i/8)*3);
  level.setBlock(foot,Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.FOOT),3);
  level.setBlock(foot.north(),Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.HEAD),3);
  members.add(Map.of("x",foot.getX(),"y",foot.getY(),"z",foot.getZ()));
}
return Sync.stamp(Map.of("ok",true,"y",oy,"field_y",fy,"members",members,"chests",chests));
`,
    members: `
static int n(Map<?,?> map,String key){return ((Number)map.get(key)).intValue();}
static int top(ServerLevel level,int x,int z){
  var pos=new BlockPos.MutableBlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
  while(pos.getY()>level.getMinBuildHeight()&&level.getBlockState(pos).is(BlockTags.LOGS))pos.move(0,-1,0);
  return pos.getY();
}
`,
  }),
});


// Two residents farm the field and the rest build; each carries its tools and stands by the chests.
const CREW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.overhead-crew",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.*",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.item.*",
      "net.minecraft.world.level.levelgen.Heightmap",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var base=(Map<?,?>)args.value("base");var field=(Map<?,?>)args.value("field");int fy=(int)args.integer("y");
int bx=((Number)base.get("x")).intValue(),bz=((Number)base.get("z")).intValue();
var zone=ZoneDrafts.zone(level,colony,ResourceLocation.parse("folkways:farm"),
  new BlockPos(((Number)field.get("minX")).intValue(),fy,((Number)field.get("minZ")).intValue()),
  new BlockPos(((Number)field.get("maxX")).intValue(),fy+1,((Number)field.get("maxZ")).intValue()),
  ColonySettings.empty().with("crop",new ColonySettings.Value.Choice(ResourceLocation.parse("minecraft:wheat")))).orElseThrow();
int i=0;var crew=new ArrayList<Object>();
for(var resident:colony.residents()){
  var entity=level.getEntity(resident.id());var body=Bodies.of(entity).orElseThrow();
  int index=i++;String role=index<2?"farming":"building";
  for(var vocation:Vocations.all())body.licences().trade(vocation).ifPresent(trade->
    body.licences().allow(trade,vocation.id().getPath().equals(role)||vocation.id().getPath().equals("hauling")));
  body.pack().insert(new ItemStack(Items.BREAD,16));
  if(role.equals("farming"))body.pack().insert(new ItemStack(Items.IRON_HOE));
  else{
    body.pack().insert(new ItemStack(Items.IRON_PICKAXE));body.pack().insert(new ItemStack(Items.IRON_AXE));
    body.pack().insert(new ItemStack(Items.IRON_SHOVEL));
  }
  double x=bx-10+(index%4)*2.0,z=bz+1-(index/4)*2.0;
  entity.teleportTo(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)Math.floor(x),(int)Math.floor(z))+0.1,z);
  crew.add(Map.of("id",resident.id().toString(),"role",role));
}
return Sync.stamp(Map.of("ok",true,"crew",crew,"zone",zone.id().toString()));
`,
  }),
});

const RESIDENTS_AT = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.overhead-residents",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.engine.colony.ColonyGround"],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var out=new ArrayList<Object>();
for(var resident:colony.residents()){
  var entity=level.getEntity(resident.id());if(entity==null)continue;
  out.add(Map.of("x",entity.getX(),"y",entity.getY(),"z",entity.getZ()));
}
return Map.of("ok",true,"residents",out);
`,
  }),
});

const STOCK_ITEMS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.overhead-stock",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
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
  int left=((Number)entry.getValue()).intValue();
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

// The colony's building sites as their cards tell them: the name, and how much of each is placed.
const SITES = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.overhead-sites",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.api.Facing",
      "io.github.izakyl.folkways.front.api.notice.Line",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var view=colony.view(level);var out=new ArrayList<Object>();
for(var facing:Facing.all(colony))if(facing.getClass().getSimpleName().equals("BuildPresence"))
for(var placard:facing.placards(view)){
  String name="";int placed=0,of=0;
  for(var line:placard.lines()){
    if(line instanceof Line.Said said&&name.isEmpty())name=said.notice().component().getString();
    if(line instanceof Line.Literal literal&&name.isEmpty())name=literal.text();
    if(line instanceof Line.Text text&&name.isEmpty())name=text.component().getString();
    if(line instanceof Line.Bar bar){placed=(int)bar.value();of=(int)bar.max();}
  }
  out.add(Map.of("id",placard.id().toString(),"name",name,"placed",placed,"of",of));
}
return Map.of("ok",true,"sites",out);
`,
  }),
});
