import { defineTask, javaTask, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { licenseCrewFast } from "./fast-setup";

const K = "kaleidoscope_cookery:";

function layout(origin: Vec) {
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  const fields = [
    { crop: K + "tomato_crop", seed: K + "tomato_seed", maxAge: 7, soil: "farmland[moisture=7]", min: at(6, -1, -6), max: at(7, -1, -3) },
    { crop: K + "chili_crop", seed: K + "chili_seed", maxAge: 7, soil: "farmland[moisture=7]", min: at(8, -1, -6), max: at(9, -1, -3) },
    { crop: K + "lettuce_crop", seed: K + "lettuce_seed", maxAge: 7, soil: "farmland[moisture=7]", min: at(10, -1, -6), max: at(11, -1, -3) },
    { crop: "minecraft:wheat", seed: "minecraft:wheat_seeds", maxAge: 7, soil: "farmland[moisture=7]", min: at(6, -1, 1), max: at(7, -1, 4) },
    { crop: "minecraft:carrots", seed: "minecraft:carrot", maxAge: 7, soil: "farmland[moisture=7]", min: at(8, -1, 1), max: at(9, -1, 4) },
    { crop: K + "tea_tree", seed: K + "tea_seed", maxAge: 5, soil: "dirt", min: at(10, -1, 1), max: at(11, -1, 4) },
  ].map((field, index) => {
    // A repeatable shuffled planting makes different takes comparable, with half the plants
    // ready to pick and the rest at varied native ages. Growth proceeds normally afterwards.
    let random = 0xc00cfeed ^ (index * 0x9e3779b9);
    const next = () => {
      random ^= random << 13; random ^= random >>> 17; random ^= random << 5;
      return (random >>> 0) / 0x100000000;
    };
    const ages = Array.from({ length: 8 }, (_, i) => i < 4 ? field.maxAge : Math.floor(next() * field.maxAge));
    for (let i = ages.length - 1; i > 0; i--) {
      const j = Math.floor(next() * (i + 1));
      [ages[i], ages[j]] = [ages[j], ages[i]];
    }
    return { ...field, ages };
  });
  const members = [at(5, 0, -5), at(5, 0, 3)];
  const workcells = fields.flatMap(field => {
    const cells: Vec[] = [];
    for (let x = field.min.x; x <= field.max.x; x++) for (let z = field.min.z; z <= field.max.z; z++) {
      cells.push({ x, y: origin.y, z });
    }
    return cells;
  });
  return { fields, members, workcells, post: at(5, 0, -3) };
}

/** Stage once, after kitchen/dining warm-up and before the frozen camera composition.
 * One new farmer picks or harvests the mixed plants, replants and carries the produce.
 * No crops are reset or force-grown, and no actors teleport or receive manual work commits during recording. */
export async function stageCookerySideWork(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  if (workersUUIDs.length !== 1) throw new Error("Cookery side work requires exactly one new farmer for its 50-second workload");
  const plan = layout(origin);
  for (const field of plan.fields) {
    await tryCommand(server, `fill ${commandPos(field.min)} ${commandPos(field.max)} minecraft:${field.soil}`);
    const cropMin = { ...field.min, y: origin.y };
    const cropMax = { ...field.max, y: origin.y };
    await tryCommand(server, `fill ${commandPos(cropMin)} ${commandPos({ ...cropMax, y: origin.y + 2 })} minecraft:air`);
    let index = 0;
    for (let x = cropMin.x; x <= cropMax.x; x++) for (let z = cropMin.z; z <= cropMax.z; z++) {
      await world.command(server, `setblock ${commandPos({ x, y: origin.y, z })} ${field.crop}[age=${field.ages[index++]}]`);
    }
  }
  // These service barrels are outside the plots. The pool between the two fields stays intact.
  for (let i = 0; i < plan.members.length; i++) {
    const seeds = plan.fields.slice(i * 3, i * 3 + 3).map(field => field.seed);
    const items = [
      `{Slot:0b,id:"minecraft:iron_hoe",count:1}`,
      ...seeds.map((seed, slot) => `{Slot:${slot + 1}b,id:"${seed}",count:64}`),
      `{Slot:4b,id:"minecraft:cooked_beef",count:32}`,
    ];
    await world.command(server, `setblock ${commandPos(plan.members[i])} minecraft:barrel[facing=up]{Items:[${items.join(",")}]}`);
  }
  const marked = await runColonyTask(server, MARK_MEMBERS, {
    player, colony_id, cells: plan.members.flatMap(at => [at.x, at.y, at.z]),
  });
  const licensed = await licenseCrewFast(server, workersUUIDs.map(uuid => ({ uuid, trades: ["farming", "hauling"] })));
  const rigged = await runColonyTask(server, STAGE, {
    colony_id, fields: plan.fields, workers: workersUUIDs, post: plan.post,
  });
  const initial = await readCookerySideWork(server, colony_id, origin, workersUUIDs);
  if (initial.mature !== 24 || initial.growing !== 24 || initial.crops.length !== 6) {
    throw new Error(`Mixed native crops did not retain their staged growth: ${JSON.stringify(initial)}`);
  }
  return { ...plan, workers: workersUUIDs, marked, licensed, rigged,
    initialMature: plan.fields.reduce((total, field) => total + field.ages.filter(age => age === field.maxAge).length, 0),
    initial, via: "native-farming" };
}

export async function readCookerySideWork(
  server: MinecraftServer, colony_id: string, origin: Vec, workersUUIDs: string[] = [],
) {
  return runColonyTask(server, READ, { colony_id, origin, workers: workersUUIDs, ...layout(origin) });
}

const STAGE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cookery-side-work-stage", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.engine.colony.*",
    "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "io.github.izakyl.folkways.core.api.resident.body.Keenness",
    "io.github.izakyl.folkways.core.api.vocation.Vocations",
    "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation", "net.minecraft.world.item.*",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var colony = ColonyGround.of(ctx.server(), UUID.fromString(args.string("colony_id"))).orElseThrow();
var zones = new ArrayList<Object>();
for (var raw : args.list("fields")) {
    var field = (Map<?, ?>) raw;
    var a = (Map<?, ?>) field.get("min");
    var b = (Map<?, ?>) field.get("max");
    var min = new BlockPos(((Number) a.get("x")).intValue(), ((Number) a.get("y")).intValue(), ((Number) a.get("z")).intValue());
    var max = new BlockPos(((Number) b.get("x")).intValue(), ((Number) b.get("y")).intValue(), ((Number) b.get("z")).intValue());
    var settings = ColonySettings.empty().with("crop", new ColonySettings.Value.Choice(ResourceLocation.parse(String.valueOf(field.get("crop")))));
    var zone = ZoneDrafts.zone(level, colony, ResourceLocation.parse("folkways:farm"), min, max, settings)
        .orElseThrow(() -> new IllegalStateException("Side-work farm refused: " + field));
    zones.add(Map.of("id", zone.id().toString(), "crop", field.get("crop"), "min", min.toShortString(), "max", max.toShortString()));
}
var post = (Map<?, ?>) args.value("post");
var crew = new ArrayList<Object>();
for (var id : args.strings("workers")) {
    var entity = level.getEntity(UUID.fromString(id));
    var body = Bodies.of(entity).orElseThrow();
    for (var vocation : Vocations.all()) body.licences().trade(vocation).ifPresent(trade -> {
        body.licences().allow(trade, vocation.id().getPath().equals("farming") || vocation.id().equals(Vocations.HAULING));
        body.licences().setKeenness(trade, vocation.id().getPath().equals("farming") ? Keenness.FIRST : Keenness.FOURTH);
    });
    body.pack().clearContent();
    body.pack().insert(new ItemStack(Items.IRON_HOE));
    for (var raw : args.list("fields")) {
        var field = (Map<?, ?>) raw;
        var seed = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(field.get("seed"))));
        body.pack().insert(new ItemStack(seed, 32));
    }
    body.pack().insert(new ItemStack(Items.COOKED_BEEF, 24));
    // Initial actor placement only. From here the ordinary scheduler owns every movement and action.
    entity.teleportTo(((Number) post.get("x")).doubleValue() + 0.5, ((Number) post.get("y")).doubleValue(), ((Number) post.get("z")).doubleValue() + 0.5);
    crew.add(Map.of("uuid", id, "trade", "farming", "doing", body.doing().map(Object::toString).orElse("idle")));
}
return Sync.stamp(Map.of("zones", zones, "workers", crew));
` }),
});

const READ = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cookery-side-work-read", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
    "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries",
    "net.minecraft.world.level.block.state.properties.IntegerProperty", "net.minecraft.world.Container",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var colony = ColonyGround.of(ctx.server(), UUID.fromString(args.string("colony_id"))).orElseThrow();
int mature = 0, growing = 0, empty = 0, harvested = 0;
var crops = new ArrayList<Object>();
for (var raw : args.list("fields")) {
    var field = (Map<?, ?>) raw;
    var min = (Map<?, ?>) field.get("min");
    var max = (Map<?, ?>) field.get("max");
    var initialAges = (List<?>) field.get("ages");
    int maxAge = ((Number) field.get("maxAge")).intValue();
    int ripe = 0, young = 0, bare = 0, picked = 0, index = 0;
    var ages = new ArrayList<Integer>();
    for (int x = ((Number) min.get("x")).intValue(); x <= ((Number) max.get("x")).intValue(); x++) {
        for (int z = ((Number) min.get("z")).intValue(); z <= ((Number) max.get("z")).intValue(); z++) {
            var at = new BlockPos(x, ((Number) min.get("y")).intValue() + 1, z);
            var state = level.getBlockState(at);
            boolean wasRipe = ((Number) initialAges.get(index++)).intValue() == maxAge;
            if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(field.get("crop"))) {
                var age = state.getProperties().stream().filter(p -> p instanceof IntegerProperty && p.getName().equals("age"))
                    .map(p -> (IntegerProperty) p).findFirst().orElseThrow();
                int value = state.getValue(age);
                ages.add(value);
                if (value == maxAge) ripe++; else { young++; if (wasRipe) picked++; }
            } else if (state.isAir()) { bare++; ages.add(-1); if (wasRipe) picked++; }
            else throw new IllegalStateException("Unexpected crop at " + at + ": " + state);
        }
    }
    mature += ripe; growing += young; empty += bare; harvested += picked;
    crops.add(Map.of("crop", field.get("crop"), "mature", ripe, "growing", young, "empty", bare, "ages", ages));
}
var workers = new ArrayList<Object>();
var ids = args.strings("workers");
for (var resident : colony.residents()) {
    var entity = level.getEntity(resident.id());
    if (entity == null || (!ids.isEmpty() && !ids.contains(entity.getUUID().toString()))) continue;
    var body = Bodies.of(entity).orElse(null);
    if (body == null) continue;
    workers.add(Map.of("uuid", entity.getUUID().toString(), "position", List.of(entity.getX(), entity.getY(), entity.getZ()),
        "doing", body.doing().map(doing -> doing.toward().orElse(doing.what()).getPath()).orElse("idle"),
        "mounted", entity.isPassenger(), "walking", !body.mob().getNavigation().isDone()));
}
var stores = new ArrayList<Object>();
for (var raw : args.list("members")) {
    var cell = (Map<?, ?>) raw;
    var at = new BlockPos(((Number) cell.get("x")).intValue(), ((Number) cell.get("y")).intValue(), ((Number) cell.get("z")).intValue());
    var items = new LinkedHashMap<String, Integer>();
    if (level.getBlockEntity(at) instanceof Container inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()) items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
    }
    stores.add(Map.of("position", List.of(at.getX(), at.getY(), at.getZ()), "items", items));
}
return Sync.stamp(Map.of("time", level.getGameTime(), "mature", mature, "growing", growing, "empty", empty,
    // A conservative count: only initially ripe plants that have visibly been picked or cut.
    // Growing plants never count as work merely because they were staged below maximum age.
    "harvested", harvested, "crops", crops, "workers", workers, "stores", stores));
` }),
});
