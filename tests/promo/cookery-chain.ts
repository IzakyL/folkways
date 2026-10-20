import { defineTask, javaTask, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { WIRE_NETWORK } from "../shared/colony/integration-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { licenseCrewFast, setMaintainDemandsFast } from "./fast-setup";
import { sleepMs } from "./promo-kit";

// Use an ingredient absent from every other local pantry: an ordinary order can then
// compare genuine chain transport with carrying it, without a nearer duplicate source.
const ITEM = "minecraft:sugar";
const WATCH_KEY = "folkways.promo.cookery-chain.observers";
function layout(origin: Vec) {
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  return {
    packager: at(16, 0, -14), send: at(16, 1, -14), source: at(17, 0, -14),
    receive: at(-16, 0, -14), target: at(-15, 0, -12),
    chains: [16, 0, -16].map(x => at(x, 4, -14)),
    // A separate logistics link delegates the net without automating the hand packager.
    link: at(19, 0, -12), ticker: at(20, 0, -12),
    posts: [at(15, 0, -13), at(-15, 0, -13)],
  };
}

/** Set dressing and raw stock only. Ordinary hauling work packs, sends, receives and stores the order. */
export async function stageCookeryChain(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  if (workersUUIDs.length !== 2 || new Set(workersUUIDs).size !== 2) throw new Error("Chain supply needs two distinct new residents");
  const plan = layout(origin);
  const set = (at: Vec, state: string) => world.command(server, `setblock ${commandPos(at)} ${state}`);
  for (const [index, hub] of plan.chains.entries()) {
    if (index === 1) {
      // Only the middle hub has a shaft: endpoint shafts would intersect the ports.
      await world.command(server, `fill ${commandPos({ ...hub, y: origin.y - 1 })} ${commandPos({ ...hub, y: hub.y - 1 })} create:shaft[axis=y]`);
      await set({ ...hub, y: origin.y - 2 }, "create:creative_motor[facing=up]{ScrollValue:256}");
    } else {
      const post = { ...hub, x: hub.x + (index === 0 ? 2 : -2), y: origin.y };
      await world.command(server, `fill ${commandPos(post)} ${commandPos({ ...post, y: origin.y + 3 })} minecraft:stripped_spruce_log[axis=y]`);
      const a = { ...hub, x: Math.min(hub.x, post.x), y: origin.y + 3 };
      const b = { ...hub, x: Math.max(hub.x, post.x), y: origin.y + 3 };
      await world.command(server, `fill ${commandPos(a)} ${commandPos(b)} minecraft:stripped_spruce_log[axis=x]`);
    }
    await set(hub, "create:chain_conveyor");
  }
  for (const [at, state] of [
    [plan.packager, "create:packager[facing=south]"], [plan.send, "create:package_frogport"],
    [plan.receive, "create:package_frogport"],
    [plan.source, `minecraft:barrel[facing=up]{Items:[{Slot:0b,id:"${ITEM}",count:32}]}`],
    [plan.target, "minecraft:barrel[facing=up]"],
    [plan.link, "create:stock_link[face=wall,facing=north]"], [plan.ticker, "create:stock_ticker[facing=east]"],
  ] as Array<[Vec, string]>) {
    // Port and stock blocks retain a solid ground support; the drive stays below ground.
    await tryCommand(server, `setblock ${commandPos({ ...at, y: origin.y - 1 })} minecraft:stone_bricks`);
    await set(at, state);
  }
  await set({ ...plan.link, z: plan.link.z + 1 }, "minecraft:stripped_spruce_log[axis=y]");
  const members = [plan.source, plan.target];
  const marked = await runColonyTask(server, MARK_MEMBERS, {
    player, colony_id, cells: members.flatMap(p => [p.x, p.y, p.z]),
  });
  const licensed = await licenseCrewFast(server, workersUUIDs.map(uuid => ({ uuid, trades: ["hauling"] })));
  const staged = await runColonyTask(server, STAGE, { colony_id, workers: workersUUIDs, ...plan });
  const wired = await runColonyTask(server, WIRE_NETWORK, {
    player, colony_id, ...plan, send_chains: [0], receive_chain: 2, edges: [[0, 1], [1, 2]], assign_crew: false,
  });
  // Dispatch publishes Counter on its normal sweep. Filing the order before that first
  // sweep commits a direct carry while no chain workshop exists yet.
  let ready: Record<string, any> = {};
  // A sprint while frozen still makes runsNormally() false: colony services skip it,
  // and Create deliberately reports zero actual speed. Let normal ticks publish the route.
  await tick.freeze(server, false);
  try {
    const started = Date.now();
    do {
      ready = await readCookeryChain(server, colony_id, origin, workersUUIDs);
      if (ready.counterPublished && ready.route.reachable && ready.route.ticks >= 0) break;
      await sleepMs(250);
    } while (Date.now() - started < 15_000);
  } finally { await tick.freeze(server, true); }
  if (!ready.counterPublished || !ready.route?.reachable || !(ready.route.ticks >= 0)) {
    throw new Error(`Native chain counter did not publish before ordering: ${JSON.stringify(ready)}`);
  }
  const order = await setMaintainDemandsFast(server, player, [{ at: plan.target, item: ITEM, count: 32, low: 32, once: true }]);
  return { ...plan, members, workers: workersUUIDs, marked, licensed, staged, wired, ready, order,
    item: ITEM, count: 32, extraBeds: 2, via: "native-resident-packing-chain-and-collection" };
}

/** Pure observation; the tick observer retains brief genuine parcel/work sightings between reads. */
export async function readCookeryChain(
  server: MinecraftServer, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  return runColonyTask(server, READ, { colony_id, workers: workersUUIDs, ...layout(origin) });
}

const IMPORTS = [
  "io.github.izakyl.folkways.front.engine.colony.*", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
  "io.github.izakyl.folkways.core.engine.colony.ColonyWorks",
  "io.github.izakyl.folkways.core.api.terms.ItemFilter", "io.github.izakyl.folkways.plugins.orders.OrdersContent",
  "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity",
  "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity",
  "com.simibubi.create.content.logistics.box.PackageItem", "io.github.izakyl.folkways.create.dispatch.PackageNetworks",
  "io.github.izakyl.folkways.create.dispatch.DispatchContent", "io.github.izakyl.folkways.create.dispatch.DispatchPresence",
  "net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation",
  "net.minecraft.world.Container", "net.minecraft.world.item.ItemStack", "net.minecraft.server.level.ServerLevel",
  "net.neoforged.neoforge.common.NeoForge", "net.neoforged.bus.api.EventPriority",
  "net.neoforged.neoforge.event.tick.ServerTickEvent",
];
const MEMBERS = `
static BlockPos pos(Object raw) {
    var p = (Map<?,?>)raw;
    return new BlockPos(((Number)p.get("x")).intValue(), ((Number)p.get("y")).intValue(), ((Number)p.get("z")).intValue());
}
static int count(ServerLevel level, BlockPos at) {
    if (!(level.getBlockEntity(at) instanceof Container inventory)) return 0;
    int found = 0;
    for (int i=0;i<inventory.getContainerSize();i++) {
        var stack=inventory.getItem(i);
        if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("${ITEM}")) found+=stack.getCount();
    }
    return found;
}
static Map<String,Object> parcel(ItemStack stack) {
    var contents = PackageItem.getContents(stack);
    var items = new ArrayList<Object>();
    for (int i=0;i<contents.getSlots();i++) {
        var one=contents.getStackInSlot(i);
        if (!one.isEmpty()) items.add(Map.of("item",BuiltInRegistries.ITEM.getKey(one.getItem()).toString(),"count",one.getCount()));
    }
    return Map.of("address",PackageItem.getAddress(stack),"items",items);
}
static Map<String,Object> observe(ServerLevel level, UUID colonyId, List<BlockPos> hubs, List<BlockPos> ports, BlockPos target) {
    var colony=ColonyGround.of(level.getServer(),colonyId).orElseThrow();
    var workers=new ArrayList<Object>(); boolean packing=false, collecting=false;
    for (var resident:colony.residents()) {
        var body=Bodies.of(level.getEntity(resident.id())).orElse(null);
        if(body==null)continue;
        var doing=body.doing().map(d->d.toward().orElse(d.what()).getPath()).orElse("idle");
        // Restrict evidence to this scene's endpoints, rather than unrelated delivery work.
        var mob=body.mob();
        boolean here=ports.stream().anyMatch(p->p.distToCenterSqr(mob.position())<100);
        packing|=here&&doing.equals("packing"); collecting|=here&&doing.equals("collecting");
        workers.add(Map.of("uuid",resident.id().toString(),"doing",doing,"position",List.of(mob.getX(),mob.getY(),mob.getZ()),
            "balk",body.balk().map(b->b.why().translationKey()).orElse("")));
    }
    var chains=new ArrayList<Object>();var parcels=new ArrayList<Object>();
    for(var at:hubs) {
        if(!(level.getBlockEntity(at) instanceof ChainConveyorBlockEntity chain))continue;
        var packages=new ArrayList<>(chain.getLoopingPackages());
        chain.getTravellingPackages().values().forEach(packages::addAll);
        for(var p:packages)parcels.add(parcel(p.item));
        chains.add(Map.of("at",at.toShortString(),"speed",chain.getSpeed(),"theoreticalSpeed",chain.getTheoreticalSpeed(),
            "overstressed",chain.isOverStressed(),"packages",packages.size()));
    }
    var portViews=new ArrayList<Object>();
    for(var at:ports) {
        var held=new ArrayList<Object>();
        if(level.getBlockEntity(at) instanceof PackagePortBlockEntity port)
            for(int i=0;i<port.inventory.getSlots();i++) {
                var stack=port.inventory.getStackInSlot(i);
                if(PackageItem.isPackage(stack))held.add(parcel(stack));
            }
        portViews.add(Map.of("at",at.toShortString(),"parcels",held));
    }
    return Map.of("time",level.getGameTime(),"workers",workers,"packing",packing,"collecting",collecting,
        "chains",chains,"chainParcels",parcels,"ports",portViews,"targetCount",count(level,target));
}
`;
const STAGE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.cookery-chain-stage", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: IMPORTS, members: MEMBERS, body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
var colonyId=UUID.fromString(args.string("colony_id"));
var colony=ColonyGround.of(ctx.server(),colonyId).orElseThrow();
var target=pos(args.value("target"));
var filter=ColonyBoards.setItemFilters(colony,OrdersContent.ID,Optional.of(target),"filter",
    List.of(ItemFilter.item(ResourceLocation.parse("${ITEM}"))),ColonyViews.forBoard(colony,level,Optional.of(target))).orElseThrow();
if(filter.refusal().isPresent())throw new IllegalStateException("Chain shelf filter refused: "+filter.refusal());
var mode=ColonyBoards.act(level,colony,OrdersContent.ID,Optional.of(target),"filter_mode").orElseThrow();
if(mode.refusal().isPresent())throw new IllegalStateException("Chain shelf mode refused: "+mode.refusal());
var ids=args.strings("workers");var posts=args.list("posts");
for(int i=0;i<ids.size();i++) {
    var body=Bodies.of(level.getEntity(UUID.fromString(ids.get(i)))).orElseThrow();
    var at=pos(posts.get(i));body.mob().teleportTo(at.getX()+0.5,at.getY(),at.getZ()+0.5);
}
var hubs=args.list("chains").stream().map(one->pos(one)).toList();
// Newly placed frozen hubs have not run their first tick. The public native initializer
// prepares connection geometry before addConnectionTo; no simulation or field patching.
for(var at:hubs) {
    var chain=(ChainConveyorBlockEntity)level.getBlockEntity(at);
    if(chain==null)throw new IllegalStateException("Missing chain hub at "+at);
    chain.prepareStats();
}
var ports=List.of(pos(args.value("send")),pos(args.value("receive")));
var props=System.getProperties();
Map<String,Object> observers;
if(props.get("${WATCH_KEY}") instanceof Map<?,?> existing) observers=(Map<String,Object>)existing;
else {observers=new ConcurrentHashMap<>();props.put("${WATCH_KEY}",observers);}
if(observers.containsKey(colonyId.toString()))throw new IllegalStateException("Chain observer already staged for colony");
var history=new ConcurrentHashMap<String,Object>();
history.put("packingSeen",false);history.put("collectingSeen",false);history.put("chainParcelSeen",false);
history.put("maxTargetCount",0);history.put("firstParcel",Map.of());
observers.put(colonyId.toString(),history);
NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,false,ServerTickEvent.Post.class,event->{
    if(event.getServer()!=level.getServer())return;
    var sample=observe(level,colonyId,hubs,ports,target);
    if(Boolean.TRUE.equals(sample.get("packing")))history.put("packingSeen",true);
    if(Boolean.TRUE.equals(sample.get("collecting")))history.put("collectingSeen",true);
    var parcels=(List<?>)sample.get("chainParcels");
    if(!parcels.isEmpty()){
        history.put("chainParcelSeen",true);
        if(((Map<?,?>)history.get("firstParcel")).isEmpty())history.put("firstParcel",parcels.getFirst());
    }
    history.put("maxTargetCount",Math.max((Integer)history.get("maxTargetCount"),(Integer)sample.get("targetCount")));
});
return Map.of("observer",true,"item","${ITEM}","initialTargetCount",count(level,target));
` }),
});
const READ = defineTask<Record<string, any>, any>({
  name: "folkways.promo.cookery-chain-read", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: IMPORTS, members: MEMBERS, body: `
var args=Args.of(ctx);var level=ctx.server().overworld();var colonyId=UUID.fromString(args.string("colony_id"));
var hubs=args.list("chains").stream().map(one->pos(one)).toList();
var ports=List.of(pos(args.value("send")),pos(args.value("receive")));
var result=new LinkedHashMap<String,Object>(observe(level,colonyId,hubs,ports,pos(args.value("target"))));
var colony=(ColonyWorks)ColonyGround.of(ctx.server(),colonyId).orElseThrow();
var packager=pos(args.value("packager"));
var workshops=colony.workshopsIn(level.dimension());
// Public workshop contracts survive WorkDecorators; Java implementation names do not.
result.put("counterPublished",workshops.stream().anyMatch(shop->shop.id().equals(ResourceLocation.parse("folkways:dispatch"))
    && shop.carries() && shop.site().where().block(level).equals(packager)));
result.put("dispatchWorkshops",workshops.stream().filter(shop->shop.id().equals(ResourceLocation.parse("folkways:dispatch")))
    .map(shop->Map.of("id",shop.id().toString(),"site",shop.site().where().cell().toShortString(),"carries",shop.carries())).toList());
result.put("sourceCount",count(level,pos(args.value("source"))));
result.put("item","${ITEM}");
var observers=(Map<?,?>)System.getProperties().get("${WATCH_KEY}");
result.put("evidence",observers==null?Map.of():new LinkedHashMap<>((Map<?,?>)observers.get(colonyId.toString())));
var network=PackageNetworks.get();
var dispatch=(DispatchPresence)colony.service(DispatchContent.ID,Object.class).orElseThrow();
result.put("delegation",Map.of("chainHeld",dispatch.holdsChain(level,hubs.getFirst()),
    "nativeFrogports",network.frogportsOn(level,new LinkedHashSet<>(hubs)).stream().map(BlockPos::toShortString).toList()));
result.put("route",Map.of("reachable",network.reachedFrom(level,ports.getFirst(),id->true).contains(ports.getLast()),
    "ticks",network.handTicks(level,ports.getFirst(),ports.getLast(),id->true).orElse(-1),
    "manualPackager",network.packerAt(level,ports.getFirst()).toString()));
return Sync.stamp(result);
` }),
});
