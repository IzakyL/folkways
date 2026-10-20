import { defineTask, javaTask, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { licenseCrewFast } from "./fast-setup";

function layout(origin: Vec) {
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  const fields = [
    { crop: "minecraft:carrots", seed: "minecraft:carrot", min: at(6, -1, -6), max: at(11, -1, -3) },
    { crop: "minecraft:potatoes", seed: "minecraft:potato", min: at(6, -1, 1), max: at(11, -1, 4) },
  ];
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
 * One new farmer performs all 48 real harvests and replants, plus walking and storage work.
 * Nothing regrows, teleports, or issues manual work commits during the recording. */
export async function stageCookerySideWork(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  if (workersUUIDs.length !== 1) throw new Error("Cookery side work requires exactly one new farmer for its 50-second workload");
  const plan = layout(origin);
  for (const field of plan.fields) {
    await tryCommand(server, `fill ${commandPos(field.min)} ${commandPos(field.max)} minecraft:farmland[moisture=7]`);
    const cropMin = { ...field.min, y: origin.y };
    const cropMax = { ...field.max, y: origin.y };
    await tryCommand(server, `fill ${commandPos(cropMin)} ${commandPos({ ...cropMax, y: origin.y + 2 })} minecraft:air`);
    await world.command(server, `fill ${commandPos(cropMin)} ${commandPos(cropMax)} ${field.crop}[age=7]`);
  }
  // These service barrels are outside the plots. The pool between the two fields stays intact.
  for (let i = 0; i < plan.members.length; i++) {
    const seed = plan.fields[i].seed;
    const items = [
      `{Slot:0b,id:"minecraft:iron_hoe",count:1}`,
      `{Slot:1b,id:"${seed}",count:64}`,
      `{Slot:2b,id:"${seed}",count:64}`,
      `{Slot:3b,id:"minecraft:cooked_beef",count:32}`,
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
  return { ...plan, workers: workersUUIDs, marked, licensed, rigged, initialMature: 48, via: "native-farming" };
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
    body.pack().insert(new ItemStack(Items.CARROT, 64));
    body.pack().insert(new ItemStack(Items.POTATO, 64));
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
    "net.minecraft.world.level.block.CropBlock", "net.minecraft.world.Container",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var colony = ColonyGround.of(ctx.server(), UUID.fromString(args.string("colony_id"))).orElseThrow();
int mature = 0, growing = 0, empty = 0;
for (var raw : args.list("workcells")) {
    var cell = (Map<?, ?>) raw;
    var at = new BlockPos(((Number) cell.get("x")).intValue(), ((Number) cell.get("y")).intValue(), ((Number) cell.get("z")).intValue());
    var state = level.getBlockState(at);
    if (state.getBlock() instanceof CropBlock crop) {
        if (crop.isMaxAge(state)) mature++; else growing++;
    } else if (state.isAir()) empty++;
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
    "harvested", 48 - mature, "workers", workers, "stores", stores));
` }),
});
