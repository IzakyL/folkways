import { defineTask, javaTask, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { commandPos } from "../shared/harness/bw-helpers";
import { licenseCrewFast, setMaintainDemandsFast } from "./fast-setup";

type Worker = { uuid: string; floor: number; role: "cook" | "crafter" | "hauler" };
type Route = { item: string; source: Vec; output: Vec; purpose: string; count: number };
const kitchens = new Map<string, { at: Vec; smoker: Vec; cook: string; requested: number }>();

/** All orders use ordinary colony storage, crafting and transport. The three delivery workers
 * carry building supplies and food between floors; none of these tasks controls the elevator. */
export async function stageElevatorWorkshop(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec, crew: Worker[],
) {
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  const members: Vec[] = [];
  const install = async (pos: Vec, block: string) => {
    await world.command(server, `setblock ${commandPos(pos)} ${block}`);
    members.push(pos);
  };
  const stock = async (pos: Vec, material: string, stacks: number) => {
    const items = Array.from({ length: stacks }, (_, slot) =>
      `{Slot:${slot}b,id:"minecraft:${material}",count:64}`).join(",");
    await world.command(server, `data merge block ${commandPos(pos)} {Items:[${items}]}`);
  };
  // One registered station of each kind makes recipe selection local and unambiguous.
  const bench = at(-14, 1, 2), smoker = at(-12, 11, 4);
  const planks = at(-10, 1, 2), meals = at(-7, 11, 3), stone = at(-16, 1, 1), kelp = at(-9, 11, 4);
  await install(bench, "minecraft:crafting_table");
  await install(smoker, "minecraft:smoker[facing=north]{Items:[{Slot:1b,id:\"minecraft:coal\",count:8}]}");
  await install(planks, "minecraft:barrel[facing=up]");
  // Costs.reachable needs a drawable store lot even when the maker already carries input.
  await install(stone, "minecraft:barrel[facing=up]");
  await stock(stone, "andesite", 8);
  await install(kelp, "minecraft:barrel[facing=up]");
  await stock(kelp, "kelp", 1);
  await install(meals, "minecraft:barrel[facing=up]");
  const routes: Route[] = [
    { item: "cut_copper", source: at(-15, 1, -3), output: at(-5, 21, -2), purpose: "upper terrace copper roof", count: 512 },
    { item: "stripped_spruce_log", source: at(-9, 1, -3), output: at(-14, 21, -3), purpose: "upper terrace roof supports", count: 512 },
    { item: "coal", source: at(8, 1, -3), output: at(-14, 11, -3), purpose: "kitchen fuel", count: 512 },
    { item: "porkchop", source: at(14, 1, -3), output: at(-5, 11, -3), purpose: "kitchen food supply", count: 512 },
  ];
  for (const route of routes) {
    await install(route.source, "minecraft:barrel[facing=up]");
    await stock(route.source, route.item, 8);
    await install(route.output, "minecraft:barrel[facing=up]");
  }
  // Deliver upper-store sandstone to a ground-level reserve for future courtyard work.
  const paving: Route = { item: "smooth_sandstone", source: at(8, 21, 7), output: at(-18, 1, -9), purpose: "courtyard building material reserve", count: 512 };
  await install(paving.source, "minecraft:barrel[facing=up]");
  await stock(paving.source, paving.item, 8);
  await install(paving.output, "minecraft:barrel[facing=up]");
  routes.push(paving);
  // These supplies are the actual yields of the adjacent farms and pasture. Their source
  // barrels belong to the rural helper; only the upper storeroom is installed here.
  for (const route of [
    { item: "wheat", source: at(-18, 1, -8), output: at(8, 21, -3), purpose: "harvest into upper granary", count: 512 },
    { item: "white_wool", source: at(19, 1, -6), output: at(14, 21, -3), purpose: "pasture wool into upper textile store", count: 512 },
  ]) {
    routes.push(route);
    await install(route.output, "minecraft:barrel[facing=up]");
  }
  // A little local kitchen stock lets the first meal start while the lift brings reserves.
  await stock(routes[2].output, "coal", 1);
  await stock(routes[3].output, "porkchop", 1);
  await runColonyTask(server, MARK_MEMBERS, { player, colony_id, cells: members.flatMap(p => [p.x, p.y, p.z]) });
  const roster = crew.map(one => ({ ...one, trades: one.role === "hauler" ? ["hauling"] : one.role === "crafter" ? ["crafting", "farming", "hauling"] : ["crafting", "hauling"] }));
  const licensed = await licenseCrewFast(server, roster);
  const prepared = await runColonyTask(server, PREPARE_WORKERS, { crew: roster, origin });
  const demands = [
    ...routes.map(route => ({ at: route.output, item: `minecraft:${route.item}`, count: route.count, low: route.count, once: !["wheat", "white_wool"].includes(route.item) })),
    { at: planks, item: "minecraft:polished_andesite", count: 512, low: 512 },
    { at: meals, item: "minecraft:dried_kelp", count: 4, low: 4 },
  ];
  const orders = await setMaintainDemandsFast(server, player, demands);
  kitchens.set(colony_id, { at: meals, smoker, cook: crew.find(one => one.role === "cook")?.uuid ?? "", requested: 4 });
  return { roster, members, routes, stations: { bench, smoker }, outputs: { planks, meals }, licensed, prepared, orders };
}

/** Keep real meal production in batches of four. Completed meals stay in the colony pantry. */
export async function refreshElevatorWorkshop(server: MinecraftServer, player: string, colony_id: string) {
  const kitchen = kitchens.get(colony_id);
  if (!kitchen) return { updated: false };
  const inventory = await runColonyTask(server, COUNT_MEALS, { at: kitchen.at, smoker: kitchen.smoker, cook: kitchen.cook, colony_id });
  const count = Number(inventory.count ?? 0);
  if (count < kitchen.requested || kitchen.requested >= 192) return { updated: false, count, requested: kitchen.requested, kitchen: inventory };
  kitchen.requested += 4;
  const orders = await setMaintainDemandsFast(server, player, [{ at: kitchen.at, item: "minecraft:dried_kelp", count: kitchen.requested, low: kitchen.requested }]);
  return { updated: true, count, requested: kitchen.requested, orders, kitchen: inventory };
}

const PREPARE_WORKERS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.elevator-workshop-crew", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.core.api.resident.body.Keenness",
      "io.github.izakyl.folkways.core.api.vocation.Vocations", "net.minecraft.world.item.*"],
    body: `
var args=Args.of(ctx);var level=ctx.server().overworld();var o=args.requireObject("origin").raw();
double ox=((Number)o.get("x")).doubleValue(),oy=((Number)o.get("y")).doubleValue(),oz=((Number)o.get("z")).doubleValue();
var prepared=new ArrayList<Object>();
for(var raw:args.list("crew")){
 var one=(Map<?,?>)raw;var entity=level.getEntity(UUID.fromString(String.valueOf(one.get("uuid"))));
 var body=Bodies.of(entity).orElseThrow();String role=String.valueOf(one.get("role"));
 String preferred=role.equals("hauler")?"hauling":"crafting";
 for(var vocation:Vocations.all())body.licences().trade(vocation).ifPresent(trade->body.licences().setKeenness(trade,vocation.id().getPath().equals(preferred)?Keenness.FIRST:Keenness.FOURTH));
 body.pack().clearContent();
 if(role.equals("crafter")){
  body.pack().insert(new ItemStack(Items.IRON_HOE));
  body.pack().insert(new ItemStack(Items.WHEAT_SEEDS,64));
  for(int i=0;i<3;i++)body.pack().insert(new ItemStack(Items.ANDESITE,64));
  entity.teleportTo(ox-13.5,oy+1,oz+0.5);
 }else if(role.equals("cook")){
  body.pack().insert(new ItemStack(Items.KELP,64));
  entity.teleportTo(ox-10.5,oy+11,oz+4.5);
 }else {
  int floor=((Number)one.get("floor")).intValue();
  entity.teleportTo(ox-7.5,oy+floor*10+1,oz-3.5);
 }
 prepared.add(Map.of("uuid",entity.getUUID().toString(),"role",role,"preferred",preferred));
}
return Sync.stamp(Map.of("workers",prepared));`,
  }),
});

const COUNT_MEALS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.elevator-workshop-meals", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.world.Container", "net.minecraft.world.item.Items", "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.terms.WorldPos", "io.github.izakyl.folkways.plugins.wares.WaresContent", "net.minecraft.world.level.block.state.properties.BlockStateProperties"],
    body: `
var at=Args.of(ctx).requireObject("at").raw();var cell=new BlockPos(((Number)at.get("x")).intValue(),((Number)at.get("y")).intValue(),((Number)at.get("z")).intValue());
int count=0;if(ctx.server().overworld().getBlockEntity(cell) instanceof Container box)for(int slot=0;slot<box.getContainerSize();slot++){var stack=box.getItem(slot);if(stack.is(Items.DRIED_KELP))count+=stack.getCount();}
var smoker=Args.of(ctx).requireObject("smoker").raw();var stove=new BlockPos(((Number)smoker.get("x")).intValue(),((Number)smoker.get("y")).intValue(),((Number)smoker.get("z")).intValue());
var level=ctx.server().overworld();var state=level.getBlockState(stove);var slots=new ArrayList<Object>();
if(level.getBlockEntity(stove) instanceof Container box)for(int slot=0;slot<box.getContainerSize();slot++)slots.add(box.getItem(slot).toString());
var colony=Colonies.of(ctx.server(),UUID.fromString(Args.of(ctx).string("colony_id"))).orElseThrow();
boolean registered=colony.view(level).blocks(WaresContent.STATION).contains(stove);boolean hosted=false;int hosts=0;
var presence=colony.service(WaresContent.ID,Object.class).orElseThrow();
var field=presence.getClass().getDeclaredField("stations");field.setAccessible(true);var stations=(Map<?,?>)field.get(presence);hosts=stations.size();hosted=stations.containsKey(WorldPos.of(level,stove));
var chef=level.getEntity(UUID.fromString(Args.of(ctx).string("cook")));var pack=new ArrayList<Object>();String doing="missing";
if(chef!=null){var body=Bodies.of(chef).orElseThrow();for(var stack:body.pack().contents())if(!stack.isEmpty())pack.add(stack.toString());doing=body.doing().map(Object::toString).orElse("idle");}
return Sync.stamp(Map.of("count",count,"smoker",state.toString(),"slots",slots,"lit",state.hasProperty(BlockStateProperties.LIT)&&state.getValue(BlockStateProperties.LIT),"registered",registered,"hosted",hosted,"hosts",hosts,"cookPack",pack,"cookDoing",doing));`,
  }),
});
