import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { licenseCrewFast } from "./fast-setup";

type Worker = { uuid: string; floor: number };

/** File real canopy orders. All subsequent block changes are resident building work. */
export async function stageElevatorConstruction(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec, crew: Worker[],
) {
  const licensed = await licenseCrewFast(server, crew.filter(one => one.floor === 2)
    .map(one => ({ ...one, trades: ["building", "hauling"] })));
  const staged = await runColonyTask(server, STAGE, { player, colony_id, origin, crew: crew.filter(one => one.floor === 2) });
  const canopy = await runColonyTask(server, START_CANOPY, { player, colony_id, origin });
  return { ...staged, canopy, licensed };
}

export async function readElevatorConstruction(server: MinecraftServer, colony_id: string, origin: Vec) {
  return runColonyTask(server, READ, { colony_id, origin });
}

const IMPORTS = [
  "io.github.izakyl.folkways.front.engine.colony.*",
  "io.github.izakyl.folkways.core.api.resident.body.Bodies",
  "io.github.izakyl.folkways.core.api.resident.body.Keenness",
  "io.github.izakyl.folkways.core.api.vocation.Vocations",
  "io.github.izakyl.folkways.plugins.build.*",
  "io.github.izakyl.folkways.core.api.terms.ItemFilter",
  "net.minecraft.core.BlockPos", "net.minecraft.world.level.block.Blocks",
  "net.minecraft.world.level.block.state.BlockState", "net.minecraft.world.item.*",
  "net.minecraft.world.Container", "net.minecraft.server.level.ServerLevel",
  "net.minecraft.resources.ResourceLocation",
];
const STAGE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.elevator-construction-stage", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: IMPORTS,
    body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var m=args.requireObject("origin").raw();var origin=new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue());
// Replace the completed display pergola with a real construction site, keeping the terrace itself.
for(var p:BlockPos.betweenClosed(origin.offset(8,21,0),origin.offset(14,25,6)))level.setBlock(p,Blocks.AIR.defaultBlockState(),3);
// The ground courtyard stays as built by the scenery; no competing excavation orders.
var stores=new ArrayList<Object>();
var roster=new ArrayList<Object>();int n=0;
for(var raw:args.list("crew")){
 var one=(Map<?,?>)raw;var entity=level.getEntity(UUID.fromString(String.valueOf(one.get("uuid"))));var body=Bodies.of(entity).orElseThrow();
 int floor=((Number)one.get("floor")).intValue();
 for(var vocation:Vocations.all())body.licences().trade(vocation).ifPresent(trade->body.licences().setKeenness(trade,vocation.id().getPath().equals("building")?Keenness.FIRST:Keenness.FOURTH));
 body.pack().clearContent();body.pack().insert(new ItemStack(Items.IRON_PICKAXE));body.pack().insert(new ItemStack(Items.IRON_AXE));body.pack().insert(new ItemStack(Items.IRON_SHOVEL));
 body.pack().insert(new ItemStack(Items.CUT_COPPER,16));body.pack().insert(new ItemStack(Items.STRIPPED_SPRUCE_LOG,16));entity.teleportTo(origin.getX()+9.5+(n%2)*3,origin.getY()+21,origin.getZ()-3.5);
 roster.add(Map.of("uuid",one.get("uuid"),"floor",floor,"role","canopy_builder"));n++;
}
return Sync.stamp(Map.of("stores",stores,"roster",roster));
`, members: `static Map<String,Integer> point(BlockPos p){return Map.of("x",p.getX(),"y",p.getY(),"z",p.getZ());}`,
  }),
});

const START_CANOPY = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.elevator-construction-start-canopy", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: IMPORTS,
    body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var m=args.requireObject("origin").raw();var origin=new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue());
var presence=colony.service(ResourceLocation.parse("folkways:build"),Object.class).orElseThrow();
var file=presence.getClass().getDeclaredMethod("file",ServerLevel.class,Blueprint.class,BlockPos.class,String.class,Optional.class,String.class);file.setAccessible(true);
var sites=new ArrayList<Object>();
for(int part=0;part<2;part++){
 int start=part==0?8:11,end=part==0?10:14;var blocks=new ArrayList<BlueprintBlock>();
 for(int x=start;x<=end;x++)for(int z=0;z<=6;z++){
  // The roof is a solid copper canopy; ring beams meet all four timber posts.
  blocks.add(new BlueprintBlock(new BlockPos(x-start,3,z),Blocks.CUT_COPPER.defaultBlockState()));
  if(x==8||x==14||z==0||z==6)blocks.add(new BlueprintBlock(new BlockPos(x-start,2,z),Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState()));
  if((x==8||x==14)&&(z==0||z==6))for(int y=0;y<2;y++)blocks.add(new BlueprintBlock(new BlockPos(x-start,y,z),Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState()));
 }
 String name=part==0?"Terrace copper canopy — west":"Terrace copper canopy — east";
 var volume=BlueprintVolume.around(blocks.stream().map(BlueprintBlock::offset).toList()).orElseThrow();
 var blueprint=new Blueprint(UUID.randomUUID(),name,blocks,volume,level.getGameTime());
 var anchor=origin.offset(start,21,0);
 var filed=(Optional<?>)file.invoke(presence,level,blueprint,anchor,name,Optional.empty(),"promo");
 if(filed.isEmpty())throw new TaskException("refused","construction blueprint was not filed",Map.of("name",name));
 sites.add(Map.of("name",name,"blocks",blocks.size(),"anchor",point(anchor)));
}
return Sync.stamp(Map.of("sites",sites));
`, members: `static Map<String,Integer> point(BlockPos p){return Map.of("x",p.getX(),"y",p.getY(),"z",p.getZ());}`,
  }),
});

const READ = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.elevator-construction-read", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: IMPORTS,
    body: `
var args=Args.of(ctx);var level=ctx.server().overworld();var m=args.requireObject("origin").raw();var o=new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue());
int copper=0,timber=0,paved=0,rough=0,air=0,stone=0,cobblestone=0;
for(var p:BlockPos.betweenClosed(o.offset(8,21,0),o.offset(14,24,6))){var state=level.getBlockState(p);if(state.is(Blocks.CUT_COPPER))copper++;if(state.is(Blocks.STRIPPED_SPRUCE_LOG))timber++;}
for(var p:BlockPos.betweenClosed(o.offset(-17,0,-17),o.offset(-7,0,-9))){var state=level.getBlockState(p);if(state.is(Blocks.SMOOTH_SANDSTONE))paved++;else rough++;if(state.isAir())air++;if(state.is(Blocks.STONE))stone++;if(state.is(Blocks.COBBLESTONE))cobblestone++;}
return Sync.stamp(Map.of("copper",copper,"timber",timber,"paved",paved,"rough",rough,"air",air,"stone",stone,"cobblestone",cobblestone,"built",copper+timber+paved));
`,
  }),
});
