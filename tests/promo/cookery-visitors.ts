import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { licenseCrewFast } from "./fast-setup";

const OWNER = "folkways:promo_visitors";

function layout(origin: Vec) {
  const at = (x: number, z: number, y = 0): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  return {
    kitchen: at(-4, -1), kitchenStand: at(-5, -1),
    visitors: [
      { start: at(-37, -16), depot: at(-39, -14), depotStand: at(-39, -15), inbound: "minecraft:coal", inboundCount: 16 },
      { start: at(27, -16), depot: at(29, -14), depotStand: at(29, -15), inbound: "minecraft:charcoal", inboundCount: 16 },
    ],
    cookPosts: [at(-13, -3), at(-7, -3)],
    returnItem: "minecraft:glass_bottle", returnCount: 4,
  };
}

/** Call once with four newly admitted workers while ticks are frozen, immediately before recording.
 * Two road visitors deliver kitchen fuel and take empty bottles back to their off-camera depots.
 * Two additional cooks join the existing native kitchen orders. All subsequent movement, transfers,
 * hunger interrupts and elevator journeys belong to normal colony work; READ never drives actors. */
export async function stageCookeryVisitors(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  if (workersUUIDs.length !== 4 || new Set(workersUUIDs).size !== 4) {
    throw new Error("Cookery visitors need four distinct new residents: two visitors and two cooks");
  }
  const plan = layout(origin);
  const visitors = workersUUIDs.slice(0, 2), cooks = workersUUIDs.slice(2);
  const licensed = await licenseCrewFast(server, [
    ...visitors.map(uuid => ({ uuid, trades: ["hauling"] })),
    ...cooks.map(uuid => ({ uuid, trades: ["crafting", "hauling"] })),
  ]);
  const prepared = await runColonyTask(server, STAGE, { colony_id, origin, workers: workersUUIDs, ...plan });
  const marked = await runColonyTask(server, MARK_MEMBERS, {
    player, colony_id, cells: [plan.kitchen, ...plan.visitors.map(one => one.depot)]
      .flatMap(cell => [cell.x, cell.y, cell.z]),
  });
  const queued = await runColonyTask(server, QUEUE, { colony_id, origin, workers: workersUUIDs, ...plan });
  return { ...plan, workers: workersUUIDs, visitors, cooks, licensed, prepared, marked, queued,
    initial: await readCookeryVisitors(server, colony_id, origin, workersUUIDs),
    via: "native-worker-bound-hauling-and-hunger" };
}

/** A strictly read-only production sample. Call during the take, without commands or task advancement. */
export async function readCookeryVisitors(
  server: MinecraftServer, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  return runColonyTask(server, READ, { colony_id, origin, workers: workersUUIDs, ...layout(origin) });
}

/** Require ordered, camera-time observations rather than treating staged positions as an entrance.
 * A completed visit includes an observed walk from the off-camera road into the inn, a new real
 * dining experience there, then an observed return toward the original side and completed delivery. */
export function summarizeCookeryVisitors(samples: Array<{ seconds: number; [key: string]: any }>, workersUUIDs: string[]) {
  return workersUUIDs.slice(0, 2).map(uuid => {
    let outside: number | undefined, entered: number | undefined, ate: number | undefined, departed: number | undefined;
    let diningAtEntry = -24000;
    let targetMet = false;
    for (const sample of samples) {
      const person = sample.people?.find((one: any) => one.uuid === uuid);
      if (!person) continue;
      if (outside === undefined && person.offscreen) { outside = sample.seconds; diningAtEntry = person.dined; }
      if (outside !== undefined && entered === undefined && sample.seconds > outside && person.insideInn) entered = sample.seconds;
      if (entered !== undefined && ate === undefined && person.dined > diningAtEntry && person.dined >= sample.started) ate = sample.seconds;
      if (ate !== undefined && sample.seconds > ate && person.outboundOnRoad && person.offscreen) departed ??= sample.seconds;
      if (person.returnDelivered && person.returnStock >= 4) targetMet = true;
    }
    return { uuid, outside, entered, ate, departed, targetMet,
      complete: outside !== undefined && entered !== undefined && ate !== undefined && departed !== undefined && targetMet };
  });
}

const STAGE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cookery-visitors-stage", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "io.github.izakyl.folkways.core.api.resident.body.Keenness", "io.github.izakyl.folkways.core.api.vocation.Vocations",
    "io.github.izakyl.folkways.core.api.work.WorkExertion", "io.github.izakyl.folkways.effect.Stats",
    "net.minecraft.core.BlockPos", "net.minecraft.nbt.CompoundTag", "net.minecraft.resources.ResourceLocation",
    "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.world.Container", "net.minecraft.world.item.*",
    "net.minecraft.world.level.block.Blocks", "io.github.izakyl.folkways.core.api.terms.Footing",
  ], body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
if(!level.tickRateManager().isFrozen())throw new IllegalStateException("Visitors must be staged while ticks are frozen");
var colony=Colonies.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var ids=args.strings("workers");var visitors=args.list("visitors");
var kitchen=args.blockPos("kitchen");var kitchenStand=args.blockPos("kitchenStand");
if(!Footing.withFeetAt(level,kitchenStand))throw new IllegalStateException("Visitor kitchen approach is obstructed: "+kitchenStand);
if(!level.getBlockState(kitchen).isAir())throw new IllegalStateException("Visitor service barrel would replace the set: "+kitchen);
level.setBlockAndUpdate(kitchen,Blocks.BARREL.defaultBlockState());
var stock=(Container)level.getBlockEntity(kitchen);
var bottle=BuiltInRegistries.ITEM.get(ResourceLocation.parse(args.string("returnItem")));
stock.setItem(0,new ItemStack(bottle,(int)args.integer("returnCount")*2));stock.setChanged();
var saved=new CompoundTag();saved.putLong("started",level.getGameTime());
for(int i=0;i<ids.size();i++){
 var body=Bodies.of(level.getEntity(UUID.fromString(ids.get(i)))).orElseThrow();
 if(!colony.residents().contains(body.resident()))throw new IllegalStateException("Visitor is not a colony resident: "+ids.get(i));
 body.pack().clearContent();
 BlockPos post;
 if(i<2){
  var plan=(Map<?,?>)visitors.get(i);var one=Args.of((Map)plan,"visitor");
  post=one.blockPos("start");var depot=one.blockPos("depot");var stand=one.blockPos("depotStand");
  if(!Footing.withFeetAt(level,post)||!Footing.withFeetAt(level,stand))throw new IllegalStateException("Road/depot footing is not ready: "+post+" / "+stand);
  if(!level.getBlockState(depot).isAir())throw new IllegalStateException("Visitor depot would replace scenery: "+depot);
  level.setBlockAndUpdate(depot,Blocks.BARREL.defaultBlockState());
  var fuel=BuiltInRegistries.ITEM.get(ResourceLocation.parse(one.string("inbound")));
  body.pack().insert(new ItemStack(fuel,(int)one.integer("inboundCount")));
  // Set the appetite once through the real survival system. With no edible pocket food, the
  // normal dining urge can interrupt the fuel delivery when a table is within its search radius.
  // A displayed whole Dongpo pork item restores 20 nutrition; the no-waste rule needs food=0.
  WorkExertion.performed(body.mob(),4000/Math.max(0.1,body.effects().value(Stats.APPETITE)),0,0);
 }else{
  post=Args.of(Map.of("at",args.list("cookPosts").get(i-2)),"cook").blockPos("at");
  if(!Footing.withFeetAt(level,post))throw new IllegalStateException("New cook post is obstructed: "+post);
  body.pack().insert(new ItemStack(Items.COOKED_BEEF,24));
  body.licences().trade(Vocations.required(ResourceLocation.parse("folkways:crafting"))).ifPresent(trade->body.licences().setKeenness(trade,Keenness.FIRST));
  body.licences().trade(Vocations.required(Vocations.HAULING)).ifPresent(trade->body.licences().setKeenness(trade,Keenness.FOURTH));
 }
 // Initial scene blocking only. No helper moves the body or changes its supplies after this stage.
 body.mob().teleportTo(post.getX()+0.5,post.getY(),post.getZ()+0.5);
 body.mob().setInvulnerable(true);
 saved.putString("worker_"+i,ids.get(i));
}
colony.keep(ResourceLocation.parse("${OWNER}"),saved);
return Sync.stamp(Map.of("started",level.getGameTime(),"workers",ids,"stagedOnlyWhileFrozen",true));
` }),
});

const QUEUE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cookery-visitors-queue", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.terms.*",
    "io.github.izakyl.folkways.core.api.work.*", "io.github.izakyl.folkways.core.engine.plan.Manifest",
    "io.github.izakyl.folkways.core.engine.plan.haul.TransferNode",
    "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation",
    "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.world.item.ItemStack",
  ], body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
if(!level.tickRateManager().isFrozen())throw new IllegalStateException("Visitor work must be queued before recording");
var colony=Colonies.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var owner=ResourceLocation.parse("${OWNER}");var kitchen=args.blockPos("kitchen");
var kitchenStore=new TransferNode.Endpoint.Store(new Stash(WorldPos.of(level,kitchen)));
var kitchenStance=Stances.at(level,args.blockPos("kitchenStand"));
var filed=new ArrayList<Object>();var ids=args.strings("workers");
for(int i=0;i<2;i++){
 final int index=i;var id=UUID.fromString(ids.get(i));var plan=Args.of((Map)args.list("visitors").get(i),"visitor");
 var pack=new TransferNode.Endpoint.Pack(Optional.of(id));
 var fuel=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(plan.string("inbound"))),(int)plan.integer("inboundCount"));
 var bottles=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(args.string("returnItem"))),(int)args.integer("returnCount"));
 var inbound=new TransferNode(UUID.randomUUID(),pack,kitchenStore,new Manifest(List.of(fuel),List.of()),kitchenStance,WorkSite.at(level,kitchen));
 var collect=new TransferNode(UUID.randomUUID(),kitchenStore,pack,new Manifest(List.of(bottles),List.of()),kitchenStance,WorkSite.at(level,kitchen));
 var depot=plan.blockPos("depot");
 var outbound=new TransferNode(UUID.randomUUID(),pack,new TransferNode.Endpoint.Store(new Stash(WorldPos.of(level,depot))),
  new Manifest(List.of(bottles),List.of()),Stances.at(level,plan.blockPos("depotStand")),WorkSite.at(level,depot));
 var graph=new Grown(List.of(inbound,collect,outbound),List.of(new Before(inbound.id(),collect.id()),new Before(collect.id(),outbound.id())));
 colony.submit(owner,level.dimension(),graph,(node,ending)->{
  var tag=colony.kept(owner).copy();
  String prefix=node.equals(inbound.id())?"inbound_":node.equals(collect.id())?"collected_":"return_";
  tag.putString(prefix+index+"_ending",ending.toString());
  if(ending instanceof Ending.Done)tag.putBoolean(prefix+index+"_done",true);
  colony.keep(owner,tag);
 });
 filed.add(Map.of("uuid",id.toString(),"inbound",inbound.id().toString(),"collect",collect.id().toString(),"outbound",outbound.id().toString(),"workerBound",true));
}
return Sync.stamp(Map.of("via","native-TransferNode-Grown","chains",filed));
` }),
});

const READ = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cookery-visitors-read", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "io.github.izakyl.folkways.plugins.person.living.LivingContent", "io.github.izakyl.folkways.core.api.terms.Goods",
    "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation", "net.minecraft.nbt.CompoundTag",
    "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.world.Container", "net.minecraft.world.item.ItemStack",
  ], body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
var colony=Colonies.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var life=colony.kept(LivingContent.ID);var history=colony.kept(ResourceLocation.parse("${OWNER}"));
var origin=args.blockPos("origin");var people=new ArrayList<Object>();var ids=args.strings("workers");
var kitchen=level.getBlockEntity(args.blockPos("kitchen"));
var kitchenItems=kitchen instanceof Container pack?inventory(pack):List.of();
for(int i=0;i<ids.size();i++){
 var entity=level.getEntity(UUID.fromString(ids.get(i)));if(entity==null)continue;
 var body=Bodies.of(entity).orElseThrow();double x=entity.getX()-origin.getX(),z=entity.getZ()-origin.getZ(),y=entity.getY()-origin.getY();
 long dined=-24000;int food=-1;
 for(var raw:life.getList("moods",10)){var mood=(CompoundTag)raw;
  if(mood.hasUUID("resident")&&mood.getUUID("resident").equals(entity.getUUID()))dined=mood.getCompound("times").getLong("dined");}
 for(var raw:life.getList("hunger",10)){var hunger=(CompoundTag)raw;
  if(hunger.hasUUID("resident")&&hunger.getUUID("resident").equals(entity.getUUID()))food=hunger.getInt("FoodLevel");}
 var one=new LinkedHashMap<String,Object>();one.put("uuid",ids.get(i));one.put("role",i<2?"visitor":"cook");
 one.put("position",List.of(entity.getX(),entity.getY(),entity.getZ()));one.put("local",List.of(x,y,z));
 one.put("doing",body.doing().map(doing->doing.toward().orElse(doing.what()).getPath()).orElse("idle"));
 one.put("pack",inventory(body.pack()));one.put("food",food);one.put("dined",dined);
 one.put("insideInn",x>=-18&&x<=-3&&z>=-6&&z<=3&&y>=-0.5&&y<=8);
 one.put("offscreen",i==0?x<=-34:i==1&&x>=24);
 one.put("outboundOnRoad",i<2&&z>=-18&&z<=-13&&(i==0?x<=-25:x>=17));
 if(i<2){
  var plan=Args.of((Map)args.list("visitors").get(i),"visitor");var block=level.getBlockEntity(plan.blockPos("depot"));
  int stock=0;var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(args.string("returnItem")));
  if(block instanceof Container container)for(int slot=0;slot<container.getContainerSize();slot++){var stack=container.getItem(slot);if(stack.is(item))stock+=stack.getCount();}
  one.put("returnStock",stock);one.put("returnDelivered",history.getBoolean("return_"+i+"_done"));
  one.put("inboundDelivered",history.getBoolean("inbound_"+i+"_done"));
  one.put("inboundEnding",history.getString("inbound_"+i+"_ending"));one.put("returnEnding",history.getString("return_"+i+"_ending"));
 }
 people.add(one);
}
return Map.of("time",level.getGameTime(),"started",history.getLong("started"),"people",people,"kitchenItems",kitchenItems);
`, members: `
static List<Object> inventory(Container container){
 var items=new ArrayList<Object>();
 for(int slot=0;slot<container.getContainerSize();slot++){var stack=container.getItem(slot);if(!stack.isEmpty())items.add(Map.of("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount()));}
 return items;
}
` }),
});
