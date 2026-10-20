import { defineTask, javaTask, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { commandPos } from "../shared/harness/bw-helpers";
import { licenseCrewFast } from "./fast-setup";

export type RuralWorker = { uuid: string; floor: number; role: "forester" | "farmer" | "herder" | "fisher" };
type RuralZone = { kind: string; min: Vec; max: Vec; crop?: string; animal?: string; target?: number };

/** Working fields, small timber lots, a sheep pasture and a sealed fishing pond.
 * All harvesting, replanting, animal care and fishing are ordinary colony work. */
export async function stageElevatorRural(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec, crew: RuralWorker[],
) {
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  const fill = async (a: number[], b: number[], state: string) => {
    const command = `fill ${commandPos(at(a[0], a[1], a[2]))} ${commandPos(at(b[0], b[1], b[2]))} ${state}`;
    const result = await world.tryCommand(server, command);
    if (!result.success && !result.output.some(line => /No blocks were filled/.test(line))) {
      throw new Error(`Rural scenery command failed: ${command}: ${JSON.stringify(result)}`);
    }
  };
  const zones: RuralZone[] = [];
  const members: Vec[] = [];
  const stocks: Array<{ at: Vec; items: string[] }> = [];
  const stock = async (x: number, z: number, items: string[]) => {
    const pos = at(x, 1, z);
    const content = items.flatMap((id, n) => Array.from({ length: 3 }, (_, i) =>
      `{Slot:${n * 3 + i}b,id:"minecraft:${id}",count:${["iron_axe", "iron_hoe", "fishing_rod", "shears"].includes(id) ? 1 : 64}}`)).join(",");
    await world.command(server, `setblock ${commandPos(pos)} minecraft:barrel[facing=up]{Items:[${content}]}`);
    members.push(pos); stocks.push({ at: pos, items });
  };

  // The original wheat field remains, with a separate carrot garden further west.
  for (const [lo, hi] of [[-24, -23], [-21, -20]]) {
    zones.push({ kind: "folkways:farm", min: at(lo, 0, -14), max: at(hi, 0, -7), crop: "minecraft:wheat" });
  }
  await stock(-18, -8, ["wheat_seeds", "iron_hoe"]);
  await fill([-32, -2, -5], [-24, -1, 6], "minecraft:dirt");
  await fill([-32, 0, -5], [-24, 0, 6], "minecraft:smooth_sandstone");
  await fill([-31, 0, -4], [-25, 0, 5], "minecraft:farmland[moisture=7]");
  await fill([-31, 1, -4], [-25, 4, 5], "minecraft:air");
  await fill([-28, 0, -4], [-28, 0, 5], "minecraft:water");
  await fill([-28, 0, -4], [-28, 0, 5], "minecraft:spruce_slab[type=top,waterlogged=true]");
  for (const [lo, hi] of [[-31, -29], [-27, -25]]) {
    await fill([lo, 1, -4], [hi, 1, 5], "minecraft:carrots[age=7]");
    zones.push({ kind: "folkways:farm", min: at(lo, 0, -4), max: at(hi, 0, 5), crop: "minecraft:carrots" });
  }
  await stock(-24, -3, ["carrot", "iron_hoe"]);

  // Short, separate oak and birch lots provide visible felling without obscuring the lift.
  // Each carries a distinct sapling, so the planner can bind work to both foresters.
  const woods = [
    { tree: "oak", min: [-30, 0, -26], max: [-26, 0, -18], stock: [-31, -20], trees: [[-29, -25], [-26, -25], [-29, -22], [-26, -22], [-29, -19], [-26, -19]] },
    { tree: "birch", min: [-24, 0, -26], max: [-20, 0, -18], stock: [-19, -20], trees: [[-23, -25], [-20, -25], [-23, -22], [-20, -22], [-23, -19], [-20, -19]] },
  ];
  await fill([-32, -2, -28], [-18, -1, -17], "minecraft:dirt");
  await fill([-32, 0, -28], [-18, 0, -17], "minecraft:grass_block");
  await fill([-32, 1, -28], [-18, 8, -18], "minecraft:air");
  for (const wood of woods) {
    zones.push({ kind: "folkways:farm", min: at(...wood.min as [number, number, number]),
      max: at(...wood.max as [number, number, number]), crop: `minecraft:${wood.tree}_sapling` });
    for (const [x, z] of wood.trees) {
      await fill([x - 1, 3, z - 1], [x + 1, 4, z + 1], `minecraft:${wood.tree}_leaves[persistent=true,distance=1]`);
      await fill([x, 1, z], [x, 4, z], `minecraft:${wood.tree}_log[axis=y]`);
      await fill([x, 5, z], [x, 5, z], `minecraft:${wood.tree}_leaves[persistent=true,distance=1]`);
    }
    await stock(wood.stock[0], wood.stock[1], [`${wood.tree}_sapling`, "iron_axe"]);
  }
  // A short connector stops the expanded garden/woodlot edge becoming a terrain step.
  await fill([-26, -1, -18], [-19, -1, 5], "minecraft:dirt");
  await fill([-26, 0, -17], [-25, 0, -6], "minecraft:grass_block");

  // A low pasture to the east, clear of the pond and the terrace entrances.
  await fill([17, -2, -18], [26, -1, -6], "minecraft:dirt");
  await fill([17, 0, -18], [26, 0, -6], "minecraft:grass_block");
  await fill([17, 1, -18], [26, 5, -7], "minecraft:air");
  for (const x of [17, 26]) await fill([x, 1, -18], [x, 1, -7], "minecraft:spruce_fence");
  for (const z of [-18, -7]) await fill([17, 1, z], [26, 1, z], "minecraft:spruce_fence");
  await fill([18, 1, -7], [18, 1, -7], "minecraft:spruce_fence_gate[facing=south,open=true]");
  await stock(19, -6, ["wheat", "shears"]);
  for (let i = 0; i < 12; i++) {
    const sheep = at(18.5 + (i % 4) * 1.8, 1, -16.5 + Math.floor(i / 4) * 2.7);
    await world.command(server, `summon minecraft:sheep ${sheep.x} ${sheep.y} ${sheep.z} {Color:${[0, 0, 8, 12][i % 4]}b,Age:0,PersistenceRequired:1b}`);
  }
  zones.push({ kind: "folkways:pasture", min: at(18, 0, -17), max: at(25, 2, -8), animal: "minecraft:sheep", target: 20 });

  // A three-block-deep pond has a continuous stone bottom and all four retaining walls.
  await fill([8, -3, -14], [16, 0, -8], "minecraft:cut_sandstone");
  await fill([9, -2, -13], [15, 0, -9], "minecraft:water");
  await fill([12, 1, -11], [12, 1, -11], "minecraft:lily_pad");
  await fill([14, 1, -10], [14, 1, -10], "minecraft:lily_pad");
  zones.push({ kind: "folkways:fish", min: at(8, 1, -11), max: at(8, 1, -11) });
  await stock(7, -10, ["fishing_rod"]);

  const counts = new Map<string, number>();
  const roster = crew.map(one => {
    const index = counts.get(one.role) ?? 0; counts.set(one.role, index + 1);
    const vocation = one.role === "forester" || one.role === "farmer" ? "farming" : one.role === "herder" ? "herding" : "fishing";
    const crop = one.role === "forester" ? ["oak_sapling", "birch_sapling"][index % 2] : ["wheat_seeds", "carrot"][index % 2];
    const post = one.role === "forester" ? at(index % 2 ? -23 : -29, 1, -18)
      : one.role === "farmer" ? at(index % 2 ? -29 : -22, 1, index % 2 ? -5 : -6)
        : one.role === "herder" ? at(18, 1, -8) : at(8, 1, -11);
    return { ...one, vocation, crop, post, trades: [vocation, "hauling"] };
  });
  await runColonyTask(server, MARK_MEMBERS, { player, colony_id, cells: members.flatMap(p => [p.x, p.y, p.z]) });
  const licensed = await licenseCrewFast(server, roster);
  const rigged = await runColonyTask(server, RIG_RURAL, { colony_id, crew: roster, zones });
  return { roster, zones, members, stocks, licensed, rigged, pasture: { adults: 12, target: 20 }, pond: { floor: at(12, -3, -11), depth: 3 } };
}

const RIG_RURAL = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.elevator-rural-rig", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.engine.colony.*", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.resident.body.Keenness", "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation", "net.minecraft.world.item.*", "net.minecraft.core.registries.BuiltInRegistries"],
    body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var made=new ArrayList<Object>();
for(var raw:args.list("zones")){
 var z=(Map<?,?>)raw;var a=(Map<?,?>)z.get("min");var b=(Map<?,?>)z.get("max");
 var min=new BlockPos(((Number)a.get("x")).intValue(),((Number)a.get("y")).intValue(),((Number)a.get("z")).intValue());
 var max=new BlockPos(((Number)b.get("x")).intValue(),((Number)b.get("y")).intValue(),((Number)b.get("z")).intValue());
 var settings=ColonySettings.empty();
 if(z.containsKey("crop"))settings=settings.with("crop",new ColonySettings.Value.Choice(ResourceLocation.parse(String.valueOf(z.get("crop")))));
 if(z.containsKey("animal"))settings=settings.with("animal",new ColonySettings.Value.Choice(ResourceLocation.parse(String.valueOf(z.get("animal")))))
   .with("target",new ColonySettings.Value.Count(((Number)z.get("target")).intValue()));
 var zone=ZoneDrafts.zone(level,colony,ResourceLocation.parse(String.valueOf(z.get("kind"))),min,max,settings)
   .orElseThrow(()->new IllegalStateException("Rural zone refused: "+z));
 made.add(Map.of("id",zone.id().toString(),"kind",z.get("kind"),"min",min.toShortString(),"max",max.toShortString()));
}
var prepared=new ArrayList<Object>();
for(var raw:args.list("crew")){
 var one=(Map<?,?>)raw;var entity=level.getEntity(UUID.fromString(String.valueOf(one.get("uuid"))));var body=Bodies.of(entity).orElseThrow();
 String vocation=String.valueOf(one.get("vocation")),role=String.valueOf(one.get("role"));
 for(var v:Vocations.all())body.licences().trade(v).ifPresent(t->{
  body.licences().allow(t,v.id().getPath().equals(vocation)||v.id().equals(Vocations.HAULING));
  body.licences().setKeenness(t,v.id().getPath().equals(vocation)?Keenness.FIRST:Keenness.FOURTH);
 });
 body.pack().clearContent();
 if(role.equals("forester")){body.pack().insert(new ItemStack(Items.IRON_AXE));body.pack().insert(new ItemStack(Items.IRON_HOE));}
 if(role.equals("farmer"))body.pack().insert(new ItemStack(Items.IRON_HOE));
 if(role.equals("forester")||role.equals("farmer")){
  var seed=BuiltInRegistries.ITEM.get(ResourceLocation.parse("minecraft:"+one.get("crop")));
  for(int n=0;n<3;n++)body.pack().insert(new ItemStack(seed,64));
 }
 if(role.equals("herder")){body.pack().insert(new ItemStack(Items.SHEARS));body.pack().insert(new ItemStack(Items.WHEAT,64));}
 if(role.equals("fisher"))body.pack().insert(new ItemStack(Items.FISHING_ROD));
 var post=(Map<?,?>)one.get("post");entity.teleportTo(((Number)post.get("x")).doubleValue()+0.5,((Number)post.get("y")).doubleValue(),((Number)post.get("z")).doubleValue()+0.5);
 prepared.add(Map.of("uuid",entity.getUUID().toString(),"role",role,"vocation",vocation,"crop",one.get("crop"),
  "doing",body.doing().map(Object::toString).orElse("idle")));
}
return Sync.stamp(Map.of("zones",made,"crew",prepared));`,
  }),
});
