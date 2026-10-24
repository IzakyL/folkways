import { expect, test } from "@playwright/test";
import { camera, defineTask, javaTask, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { cinematicWorld, launchPromoPair, sleepMs, take, type PromoPair } from "./promo-kit";
import { admitPromoSettlersFast, foundPromoColonyFast } from "./fast-setup";

test("residents construct bundled patterns from real commissions in accelerated simulation", async ({}, testInfo) => {
 test.setTimeout(90 * 60_000);
 const scope=process.env.FOLKWAYS_PATTERN_SCOPE ?? "pattern-construction";
 const run=take(scope); let pair: PromoPair | undefined;
 run.note("configuration",{workers:Number(process.env.FOLKWAYS_PATTERN_WORKERS??6),tickRate:Number(process.env.FOLKWAYS_PATTERN_TICK_RATE??160),groundDepth:Number(process.env.FOLKWAYS_PATTERN_GROUND_DEPTH??32),mineDefaults:process.env.FOLKWAYS_PATTERN_MINE_DEFAULTS==="1"});
 try {
  pair=await launchPromoPair(scope,{config:"blockwright.toml"}); const {server,client}=pair;
  const player=(await waitForGroundedPlayer(server)).name;
  await world.command(server,`op ${player}`); await cinematicWorld(server);await world.command(server,"gamerule doDaylightCycle false");await world.command(server,"time set noon");
  await world.command(server,`gamemode spectator ${player}`);
  await world.command(server,"forceload add -32 -32 96 48");
  const setup=await runColonyTask(server,HEIGHT,{depth:Number(process.env.FOLKWAYS_PATTERN_GROUND_DEPTH??32)});const y=Number(setup.y);
  await world.command(server,`tp ${player} -4 ${y} 0`);
  await world.command(server,`item replace entity ${player} weapon.mainhand with folkways:colony_book`);
  const {colony_id}=await foundPromoColonyFast(server,player,setup.members);
  await admitPromoSettlersFast(server,player,colony_id,Number(process.env.FOLKWAYS_PATTERN_WORKERS??6));
  run.note("crew",await runColonyTask(server,CREW,{colony_id,y,stageX:0}));
  const chests=Array.from({length:12},(_,i)=>({x:-15+i,y,z:-10}));
  for(const cell of chests)await world.command(server,`setblock ${cell.x} ${cell.y} ${cell.z} minecraft:chest`);
  await runColonyTask(server,MARK_MEMBERS,{player,colony_id,cells:chests.flatMap(p=>[p.x,p.y,p.z])});
  await world.command(server,`item replace entity ${player} weapon.mainhand with minecraft:air`);await camera.hud(client,false);
  const results:any[]=[];
  const selected=process.env.FOLKWAYS_PATTERN_CASES?.split(",");
  for(const one of cases(y).filter(one=>!selected||selected.includes(one.name))){
   await runColonyTask(server,RESET,{colony_id,y,terrain:one.terrain??"flat",chests,depth:Number(process.env.FOLKWAYS_PATTERN_GROUND_DEPTH??32)});
   const args={colony_id,name:one.name,y};
   const receipt=await runColonyTask(server,COMMISSION,{colony_id,sites:[one.site]});
   run.note(`${one.name}-commission`,receipt);
   await tick.sprint(server,2);
   const initial=await runColonyTask(server,AUDIT,args);run.note(`${one.name}-initial`,initial);
   const stock=await runColonyTask(server,STOCK_ALL,{colony_id,chests});run.note(`${one.name}-stock`,stock);
   expect.soft(stock.unstocked,`${one.name}: the stores hold all the order owes`).toEqual({});
   await runColonyTask(server,STOCK_ITEMS,{chests,items:{"minecraft:scaffolding":512,"minecraft:iron_pickaxe":12,"minecraft:iron_axe":6,"minecraft:iron_shovel":6,"minecraft:bread":256}});
   const eye={x:-4,y:y+14,z:-4};const look={x:12,y:y+2,z:14};
   const pose={position:eye,...cameraLookingAt(eye,look),fov:65};
   await world.command(server,`tp ${player} ${eye.x} ${eye.y} ${eye.z}`);
   await world.command(server,`tick rate ${Number(process.env.FOLKWAYS_PATTERN_TICK_RATE??160)}`);
   const samples:any[]=[];let last=initial;
   await run.clip(client,server,one.name,{subject:`Real resident construction of bundled ${one.name}; requested ${Number(process.env.FOLKWAYS_PATTERN_TICK_RATE??160)} TPS; no target staging`,worldState:{...one,initial},camera:pose,seconds:0,fps:30,
    during:async()=>{
     const started=Date.now();
     for(let round=0;round<Number(process.env.FOLKWAYS_PATTERN_ROUNDS??60);round++){
      await sleepMs(Number(process.env.FOLKWAYS_PATTERN_SAMPLE_MS??2000));last=await runColonyTask(server,AUDIT,args);samples.push({...last,elapsedMs:Date.now()-started});run.note(`${one.name}-latest`,last);
      if(one.name==="mine"||round%8===7)run.note(`${one.name}-stock`,await runColonyTask(server,STOCK_ALL,{colony_id,chests}));
      if(Number(last.expected)>0&&Number(last.scaffolds)===0&&(last.sites as unknown[]).length===0&&Number(last.orders)===0&&Number(last.growing)===0){await sleepMs(1500);break;}
     }
    }});
   await world.command(server,"tick rate 20");
   const final=await runColonyTask(server,AUDIT,args);
   const crewSamples=samples.flatMap(sample=>(sample.crew as any[]).map(actor=>({...actor,tick:sample.tick})));
   run.note(`${one.name}-height-summary`,{groundY:y,maximumCrewY:Math.max(y,...crewSamples.map(actor=>Number(actor.y))),upperFloorSamples:crewSamples.filter(actor=>Number(actor.y)>=y+2&&actor.grounded&&!String(actor.below).includes("scaffolding")&&!String(actor.below).includes("minecraft:air")),elevatedScaffoldSamples:crewSamples.filter(actor=>Number(actor.y)>=y+2&&String(actor.below).includes("scaffolding"))});
   results.push({name:one.name,initial,samples,final});run.note("results",results);
   await run.shot(client,`${one.name}-finished`,{subject:`Audited final state of ${one.name}`,worldState:final,camera:pose,hard:true});
   expect.soft(final.remaining,`${one.name}: all blueprint states must match`).toBe(0);
   expect.soft(final.scaffolds,`${one.name}: recover temporary scaffolding`).toBe(0);
   expect.soft(final.sites,`${one.name}: finished site retires`).toHaveLength(0);
   if(one.name==="mine"&&final.inspection){const eye=final.inspection as Vec;const interior={position:eye,yaw:0,pitch:15,fov:75};await run.shot(client,"mine-underground",{subject:"Unmodified underground interior of the resident-built mine",worldState:final,camera:interior,hard:true});await run.clip(client,server,"mine-interior-inspection",{subject:"Camera inspection inside the unmodified mine; separate from construction timing",worldState:final,camera:interior,seconds:6,fps:30});}
   if(process.env.FOLKWAYS_PATTERN_LAYERS&&(final.sites as unknown[]).length>0)run.note(`${one.name}-layers`,await runColonyTask(server,LAYERS,{y}));
   if((final.sites as unknown[]).length>0||Number(final.growing)>0){run.note(`${one.name}-cancelled-after-timeout`,await runColonyTask(server,CANCEL,args));await tick.sprint(server,1200);}
  }
 }catch(error){pair?.failing(error);throw error;}finally{await pair?.teardown(testInfo);}
});

type Vec={x:number;y:number;z:number};
function cases(y:number):any[]{
 const p=(x:number,z:number,dy=0):Vec=>({x,y:y+dy,z});
 return [
  {name:"arch_bridge",terrain:"channel",site:{pattern:"folkways:arch_bridge",points:[p(2,14),p(24,14)],knobs:{width:3}}},
  {name:"dock",terrain:"shore",site:{pattern:"folkways:dock",points:[p(2,14),p(16,14)],knobs:{width:3}}},
  {name:"arcade",site:{pattern:"folkways:arcade",points:[p(1,13),p(13,13)],knobs:{width:3,bay:4,height:3,pavilions:false}}},
  {name:"city_wall",site:{pattern:"folkways:city_wall",points:[p(1,8),p(21,8)],knobs:{height:5,thickness:3,spacing:0,gate:false}}},
  {name:"road",site:{pattern:"folkways:road",points:[p(1,8),p(13,8),p(25,18)]}},
  {name:"terraced_field",terrain:"slope",site:{pattern:"folkways:terraced_field",min:p(2,5),max:p(11,16)}},
  {name:"quarry",site:{pattern:"folkways:quarry",min:p(3,5),max:p(12,14),knobs:{depth:3}}},
  {name:"mine",site:{pattern:"folkways:mine",min:p(3,5),max:p(13,15),knobs:process.env.FOLKWAYS_PATTERN_MINE_DEFAULTS==="1"?{}:{y:y-15,branch:4,pairs:1,spacing:2,chase:false}}},
  {name:"railway",site:{pattern:"folkways:railway",points:[p(1,5),p(21,5),p(21,25)]}},
 ];
}
const HEIGHT = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.construction.height", side: "server", source: javaTask({
    imports: ["net.minecraft.world.level.levelgen.Heightmap", "net.minecraft.core.BlockPos", "net.minecraft.core.Direction",
      "net.minecraft.world.level.block.*", "net.minecraft.world.level.block.state.properties.BedPart",
      "net.minecraft.world.Container", "net.minecraft.world.item.*"],
    body: `
var level=ctx.server().overworld();int y=64;int depth=(int)Args.of(ctx).integer("depth");
// A deep level work yard lets the excavation extend below grade even in the bedrock-only test world.
for(int x=-32;x<=48;x++)for(int z=-24;z<=40;z++)for(int h=y-depth;h<y;h++)
 level.setBlock(new BlockPos(x,h,z),(h==y-1?Blocks.GRASS_BLOCK:Blocks.DIRT).defaultBlockState(),2);
var members=new ArrayList<Object>();
for(int x:new int[]{-6,-8}){var at=new BlockPos(x,y,0);level.setBlock(at,Blocks.CHEST.defaultBlockState(),2);
 members.add(Map.of("x",x,"y",y,"z",0));
 if(x==-8){var chest=(Container)level.getBlockEntity(at);for(int slot=0;slot<27;slot++)chest.setItem(slot,new ItemStack(Items.BREAD,64));chest.setChanged();}}
for(int i=0;i<6;i++){var at=new BlockPos(-8+i*3,y,-5);
 level.setBlock(at,Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.FOOT),3);
 level.setBlock(at.north(),Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.HEAD),3);
 members.add(Map.of("x",at.getX(),"y",y,"z",at.getZ()));}
return Sync.stamp(Map.of("y",y,"members",members));`,
  }),
});

const CREW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.construction.crew", side: "server", source: javaTask({
    imports: ["io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "net.minecraft.world.item.*"],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();int index=0;
for(var resident:colony.residents()){
  var entity=level.getEntity(resident.id());var body=Bodies.of(entity).orElseThrow();
  for(var vocation:Vocations.all())body.licences().trade(vocation).ifPresent(trade->
    body.licences().allow(trade,Set.of("building","hauling").contains(vocation.id().getPath())));
  body.pack().insert(new ItemStack(Items.BREAD,32));
  body.pack().insert(new ItemStack(Items.IRON_PICKAXE));body.pack().insert(new ItemStack(Items.IRON_AXE));
  body.pack().insert(new ItemStack(Items.IRON_SHOVEL));
  entity.teleportTo(args.number("stageX")-3+index++*2,args.number("y"),0.5);
}
return Sync.stamp(Map.of("residents",index));`,
  }),
});

const imports = ["io.github.izakyl.folkways.front.engine.colony.ColonyGround",
  "io.github.izakyl.folkways.plugins.build.*", "net.minecraft.core.BlockPos",
  "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation",
  "net.minecraft.world.item.ItemStack", "net.minecraft.world.level.block.Blocks", "net.minecraft.world.Container",
  "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.front.api.notice.Doings",
  "io.github.izakyl.folkways.core.api.work.Node"];
const members = `
static BlockPos pos(Map<?,?> raw){return new BlockPos(((Number)raw.get("x")).intValue(),((Number)raw.get("y")).intValue(),((Number)raw.get("z")).intValue());}
static Object field(Object target,String name) throws Exception {var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
static Object call(Object target,String name,Object... args) throws Exception {
  for(var method:target.getClass().getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){
    method.setAccessible(true);return method.invoke(target,args);}
  throw new NoSuchMethodException(name);
}`;
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
      "java.util.concurrent.CompletableFuture",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var said=new ArrayList<Object>();boolean[] all={true};
// Each site is drawn off the server's thread and filed once it comes back; the next is asked for after it.
CompletableFuture<Void> filed=CompletableFuture.completedFuture(null);
for(var raw:args.list("sites")){
  var site=(Map<?,?>)raw;
  Hint hint=site.containsKey("points")
    ? new Hint.Path(((List<?>)site.get("points")).stream().map(Task::pos).toList())
    : new Hint.Zone(pos(site.get("min")),pos(site.get("max")));
  var id=ResourceLocation.parse(String.valueOf(site.get("pattern")));
  filed=filed.thenCompose(done->Commissions.commission(level,colony,id,hint.save(),defaults(id,site.containsKey("knobs")?(Map<?,?>)site.get("knobs"):Map.of()),Direction.NORTH,"",Optional.empty(),"pattern-construction"))
    .thenAccept(result->{
      all[0]&=result.filed();
      said.add(Map.of("pattern",String.valueOf(site.get("pattern")),"filed",result.filed(),"said",result.message().getString()));
    });
}
return filed.thenApply(done->{
  if(!all[0])throw new TaskException("refused","not every commission was filed",Map.of("said",said));
  return Sync.stamp(Map.of("ok",true,"said",said));
});
`,
    members: `
static BlockPos pos(Object raw){var m=(Map<?,?>)raw;
  return new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue());}
static CompoundTag defaults(ResourceLocation id,Map<?,?> picked){
  var tag=new CompoundTag();
  for(var setting:Patterns.find(id).orElseThrow().knobs().settings()){
    if(setting instanceof Schema.Setting.Flag flag&&picked.containsKey(flag.key())){tag.putBoolean(flag.key(),Boolean.TRUE.equals(picked.get(flag.key())));continue;}
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


const RESET=defineTask<Record<string,any>,Record<string,any>>({name:"folkways.patternfilm.reset",side:"server",timeoutMs:30_000,source:javaTask({imports:["net.minecraft.core.BlockPos","net.minecraft.world.level.block.Blocks","io.github.izakyl.folkways.front.engine.colony.ColonyGround"],body:`
var level=ctx.server().overworld();var args=Args.of(ctx);int y=(int)args.integer("y");String terrain=args.string("terrain");
for(var raw:args.list("chests")){var m=(Map<?,?>)raw;var chest=(net.minecraft.world.Container)level.getBlockEntity(new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue()));chest.clearContent();chest.setChanged();}
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();int residentIndex=0;
for(var resident:colony.residents()){var entity=level.getEntity(resident.id());if(entity!=null)entity.teleportTo(-10+residentIndex++*2,y,-2.5);}
for(int x=-2;x<=36;x++)for(int z=1;z<=31;z++)for(int h=y-(int)args.integer("depth");h<=y+24;h++){
 int top=y-1;if(terrain.equals("slope")&&x>=2&&x<=22&&z>=3&&z<=24)top+=(24-z)/5;
 var state=h<=top?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState();
 if(h==top)state=Blocks.GRASS_BLOCK.defaultBlockState();
 if(terrain.equals("channel")&&x>=6&&x<=19&&z>=3&&z<=27){state=h<y-5?Blocks.STONE.defaultBlockState():h<y-1?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();}
 if(terrain.equals("shore")&&x>=6&&z>=3&&z<=27){state=h<y-5?Blocks.STONE.defaultBlockState():h<y-1?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();}
 level.setBlock(new BlockPos(x,h,z),state,2);
}
return Sync.stamp(Map.of("terrain",terrain,"y",y));`})});

// A diagnostic: the mine's ground layer by layer, one character a cell.
const LAYERS=defineTask<Record<string,any>,Record<string,any>>({name:"folkways.patternfilm.layers",side:"server",source:javaTask({imports:["net.minecraft.core.BlockPos","net.minecraft.core.registries.BuiltInRegistries"],body:`
var level=ctx.server().overworld();int y=(int)Args.of(ctx).integer("y");var out=new LinkedHashMap<String,Object>();var legend=new LinkedHashMap<String,String>();
String keys="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
for(int h=y+2;h>=y-17;h--){var rows=new ArrayList<String>();
 for(int z=4;z<=16;z++){var row=new StringBuilder();
  for(int x=2;x<=14;x++){var st=level.getBlockState(new BlockPos(x,h,z));String id=BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
   String c=st.isAir()?".":id.equals("stone")?"#":null;
   if(c==null){c=legend.entrySet().stream().filter(e->e.getValue().equals(id)).map(java.util.Map.Entry::getKey).findFirst().orElse(null);
    if(c==null){c=String.valueOf(keys.charAt(legend.size()%keys.length()));legend.put(c,id);}}
   row.append(c);}
  rows.add(row.toString());}
 out.put("y"+h,rows);}
out.put("legend",legend);out.put("x","2..14 left to right; z 4..16 top to bottom");
return Sync.stamp(out);`})});

const AUDIT=defineTask<Record<string,any>,Record<string,any>>({name:"folkways.patternfilm.audit",side:"server",source:javaTask({imports:[...imports,"net.minecraft.world.level.block.state.BlockState","net.minecraft.world.level.pathfinder.Path"],members:members+`
static String caseName="";
static Map<BlockPos,BlockState> expected=new LinkedHashMap<>();
static Map<BlockPos,BlockPos> expectedJoints=new LinkedHashMap<>();
// Cells the build set as the target has them: what the world makes of them afterwards, a gate walked open or a
// track turned by its neighbour, is the world's, not the build's.
static Set<BlockPos> placed=new HashSet<>();
static io.github.izakyl.folkways.core.api.ground.BlockChanges.Heard heard;
static int heardFor=-1;
static boolean matches(BlockState actual,BlockState wanted){return wanted.isAir()?actual.isAir():wanted.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock?actual.equals(wanted):actual==wanted;}
`,body:`
var level=ctx.server().overworld();var args=Args.of(ctx);var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
if(!caseName.equals(args.string("name"))){caseName=args.string("name");expected.clear();expectedJoints.clear();placed.clear();if(heard!=null)heard.close();heard=null;heardFor=-1;}
var presence=colony.service(ResourceLocation.parse("folkways:build"),Object.class).orElseThrow();var book=field(presence,"book");
var drawings=(Map<UUID,Blueprint>)field(book,"blueprints");var orders=(Map<UUID,BlueprintBuildOrder>)field(book,"orders");
for(var order:orders.values()){
 var drawing=drawings.get(order.blueprintId());if(drawing==null)continue;
 for(var block:drawing.blocks())expected.put(order.anchor().cell().offset(block.offset()),block.state());for(var joint:drawing.joints())expectedJoints.put(order.anchor().cell().offset(joint.from()),order.anchor().cell().offset(joint.to()));
}
if(!expected.isEmpty()&&heardFor!=expected.size()){
 if(heard!=null)heard.close();
 var box=net.minecraft.world.level.levelgen.structure.BoundingBox.encapsulatingPositions(expected.keySet()).orElseThrow();
 heard=io.github.izakyl.folkways.core.api.ground.BlockChanges.listen(level,box,pos->{var wanted=expected.get(pos);if(wanted!=null&&matches(level.getBlockState(pos),wanted))placed.add(pos);});
 heardFor=expected.size();
}
int correct=0;var mismatch=new ArrayList<Object>();
for(var entry:expected.entrySet()){
 var actual=level.getBlockState(entry.getKey());var wanted=entry.getValue();if(matches(actual,wanted))placed.add(entry.getKey());if(placed.contains(entry.getKey()))correct++;
 else if(mismatch.size()<64)mismatch.add(Map.of("at",entry.getKey().toShortString(),"wanted",entry.getValue().toString(),"actual",actual.toString()));
}
var sites=new ArrayList<Object>();
for(var entry:((Map<?,?>)field(presence,"sites")).entrySet()){
 var site=entry.getValue();var live=(Map<?,?>)field(site,"live");var kinds=new LinkedHashMap<String,Integer>();var pieces=new ArrayList<Object>();
 for(var piece:live.values()){var work=call(piece,"work");kinds.merge(String.valueOf(call(work,"kind")),1,Integer::sum);if(live.size()<20)pieces.add(Map.of("kind",String.valueOf(call(work,"kind")),"at",String.valueOf(call(work,"focus")),"before",call(site,"before",piece).toString(),"doing",String.valueOf(field(piece,"doing")),"restUntil",field(piece,"restUntil"),"starvedSince",field(piece,"starvedSince"),"tries",call(piece,"tries")));}
 sites.add(Map.of("id",entry.getKey().toString(),"live",live.size(),"held",((Map<?,?>)field(site,"held")).size(),"stays",((Map<?,?>)field(site,"stays")).size(),"kinds",kinds,"refusals",call(site,"refusals").toString(),"joints",field(site,"joints").toString(),"pieces",pieces));
}
var crew=new ArrayList<Object>();for(var resident:colony.residents()){var entity=level.getEntity(resident.id());if(entity!=null){var body=Bodies.of(entity).orElseThrow();crew.add(Map.of("id",resident.id().toString(),"x",entity.getX(),"y",entity.getY(),"z",entity.getZ(),"grounded",entity.onGround(),"below",level.getBlockState(entity.blockPosition().below()).toString(),"doing",body.doing().map(one->Doings.name(one).getString()).orElse("idle"),"pack",body.pack().contents().stream().filter(stack->!stack.isEmpty()).map(Object::toString).toList()));}}
int scaffolds=0;for(int x=-3;x<=37;x++)for(int z=0;z<=32;z++)for(int h=(int)args.integer("y")-32;h<=(int)args.integer("y")+24;h++)if(level.getBlockState(new BlockPos(x,h,z)).is(Blocks.SCAFFOLDING))scaffolds++;
var result=new LinkedHashMap<String,Object>();result.putAll(Map.of("tick",level.getGameTime(),"expected",expected.size(),"correct",correct,"remaining",expected.size()-correct,"mismatch",mismatch,"sites",sites,"orders",orders.size(),"scaffolds",scaffolds,"crew",crew));result.put("expectedJoints",expectedJoints.size());result.put("joined",expectedJoints.entrySet().stream().filter(e->Joints.joiner().joined(level,e.getKey(),e.getValue())).count());result.put("growing",((Map<?,?>)field(presence,"growing")).size());result.put("growth",field(presence,"growing").toString());
var entrySpecs=new ArrayList<Object>();for(var site:((Map<?,?>)field(presence,"sites")).values())for(var stay:((Map<?,?>)field(site,"stays")).values()){
 var spec=((Node)stay).spec();var details=new LinkedHashMap<String,Object>();details.put("id",spec.id().toString());details.put("stances",spec.stances().toString());details.put("needs",spec.needs().toString());details.put("tools",spec.tools().toString());
 for(String key:List.of("who","asked","batch","waitingSince","aim","toward","repathAt","walking"))details.put(key,String.valueOf(field(stay,key)));var walking=field(stay,"walking");if(walking instanceof Path path){details.put("walkingCanReach",path.canReach());details.put("walkingDone",path.isDone());details.put("walkingNext",path.getNextNodeIndex());details.put("walkingNodes",path.getNodeCount());var end=path.getEndNode();if(end!=null)details.put("walkingEnd",Map.of("x",end.x,"y",end.y,"z",end.z));}
var toward=field(stay,"toward");if(toward!=null)details.put("towardFocus",String.valueOf(call(call(toward,"work"),"focus")));entrySpecs.add(details);
}result.put("entrySpecs",entrySpecs);result.put("stalls",colony.stallsIn(level.dimension()).toString());
expected.entrySet().stream().filter(e->e.getValue().isAir()&&e.getKey().getY()<args.integer("y")-5&&level.getBlockState(e.getKey()).isAir()&&level.getBlockState(e.getKey().above()).isAir()).findFirst().ifPresent(e->result.put("inspection",Map.of("x",e.getKey().getX()+0.5,"y",e.getKey().getY()+1.4,"z",e.getKey().getZ()+0.5)));return result;
`})});
const STOCK_ALL = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.patternfilm.stock-all",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.ItemStack",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var stocked=new LinkedHashMap<String,Integer>();
var unstocked=new LinkedHashMap<String,Integer>();
var chests=new ArrayList<Container>();
for(var raw:args.list("chests")){var m=(Map<?,?>)raw;
  chests.add((Container)level.getBlockEntity(new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue())));}
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var presence=colony.service(ResourceLocation.parse("folkways:build"),Object.class).orElseThrow();
var f=presence.getClass().getDeclaredField("owed");f.setAccessible(true);
var items=new LinkedHashMap<String,Integer>();
for(var owed:((Map<?,?>)f.get(presence)).values())for(var need:((Map<?,?>)owed).entrySet())items.merge(need.getKey().toString(),((Number)need.getValue()).intValue(),Integer::sum);
for(var entry:items.entrySet()){
  var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(entry.getKey())));
  int left=((Number)entry.getValue()).intValue()+16;
  for(var chest:chests)for(int slot=0;slot<chest.getContainerSize();slot++)if(chest.getItem(slot).is(item))left-=chest.getItem(slot).getCount();
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
  if(left>0)unstocked.merge(entry.getKey().toString(),left,Integer::sum);
}
return Sync.stamp(Map.of("ok",true,"stocked",stocked,"unstocked",unstocked));
`,
  }),
});



const CANCEL=defineTask<Record<string,any>,Record<string,any>>({name:"folkways.patternfilm.cancel",side:"server",source:javaTask({imports,members,body:`
var level=ctx.server().overworld();var args=Args.of(ctx);var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var presence=colony.service(ResourceLocation.parse("folkways:build"),Object.class).orElseThrow();var book=field(presence,"book");
var grown=new ArrayList<>(((Map<?,?>)field(presence,"growing")).keySet());var orders=new ArrayList<>(((Map<?,?>)field(book,"orders")).keySet());
for(var id:grown)call(presence,"act",ResourceLocation.parse("folkways:build"),"stop:"+id,Optional.empty(),colony.view(level));
for(var id:orders)call(presence,"cancel",id);
return Sync.stamp(Map.of("cancelledAfterFailure",orders.size(),"stoppedGrowth",grown.size()));
`})});
