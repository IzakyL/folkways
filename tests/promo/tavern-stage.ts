import { defineTask, javaTask, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { commandPos } from "../shared/harness/bw-helpers";
import { licenseCrewFast } from "./fast-setup";

const T = "kaleidoscope_tavern:";

// Pressing tub on the left, the barrel and its tap on the right, the grapes and the wine store between them.
// The barrel and the tap face north, toward the camera.
function layout(origin: Vec) {
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  return {
    stations: { tub: at(-3, 0, 0), barrel: at(2, 0, 0) },
    tap: at(2, 1, -2), bottle: at(2, 0, -2),
    source: at(-5, 0, 1), target: at(-1, 0, 1),
    posts: [at(-3, 0, -1), at(2, 0, -3)],
  };
}

/** Raw grapes enter the native pressing, fermentation and tap chain; dining requests supply the demand. */
export async function stageTavern(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  if (workersUUIDs.length !== 2) throw new Error("The tavern requires two new workers");
  const plan = layout(origin);
  await world.command(server, `setblock ${commandPos(plan.stations.tub)} ${T}pressing_tub`);
  const ingredients: Array<[string, number]> = [
    ...Array.from({ length: 8 }, (): [string, number] => [T + "grape", 64]),
    ["minecraft:bucket", 16],
    ...Array.from({ length: 4 }, (): [string, number] => [T + "empty_bottle", 16]),
  ];
  const items = ingredients.map(([id, count], slot) => `{Slot:${slot}b,id:"${id}",count:${count}}`).join(",");
  await world.command(server, `setblock ${commandPos(plan.source)} minecraft:barrel[facing=up]{Items:[${items}]}`);
  await world.command(server, `setblock ${commandPos(plan.target)} minecraft:barrel[facing=up]`);
  const prepared = await runColonyTask(server, PREPARE, { colony_id, workers: workersUUIDs, ...plan });
  const members = [...Object.values(plan.stations), plan.source, plan.target];
  const marked = await runColonyTask(server, MARK_MEMBERS, {
    player, colony_id, cells: members.flatMap(cell => [cell.x, cell.y, cell.z]),
  });
  const licensed = await licenseCrewFast(server, workersUUIDs.map(uuid => ({ uuid, trades: ["crafting", "hauling"] })));
  return { ...plan, members, workers: workersUUIDs, ingredients, prepared, marked, licensed,
    initialProducts: 0, product: T + "wine", via: "native-pressing-fermentation-and-tap" };
}

export async function readTavern(
  server: MinecraftServer, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  return runColonyTask(server, READ, { colony_id, workers: workersUUIDs, ...layout(origin) });
}

const PREPARE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.tavern-prepare", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "com.github.ysbbbbbb.kaleidoscopetavern.init.ModBlocks",
    "com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock",
    "com.github.ysbbbbbb.kaleidoscopetavern.block.brew.TapBlock",
    "com.github.ysbbbbbb.kaleidoscopetavern.api.blockentity.IBarrel",
    "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "io.github.izakyl.folkways.core.api.resident.body.Keenness",
    "io.github.izakyl.folkways.plugins.wares.WaresContent",
    "io.github.izakyl.folkways.core.api.vocation.Vocations",
    "net.minecraft.core.Direction", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
    "net.minecraft.world.level.block.state.properties.AttachFace",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var origin = args.requireObject("stations").blockPos("barrel");
var state = ModBlocks.BARREL.get().defaultBlockState().setValue(BarrelBlock.FACING, Direction.NORTH)
    .setValue(BarrelBlock.LAYER, AttachFace.FLOOR).setValue(BarrelBlock.INDEX, 4);
level.setBlockAndUpdate(origin, state);
state.getBlock().setPlacedBy(level, origin, state, null, new ItemStack(ModBlocks.BARREL.get()));
level.setBlockAndUpdate(args.blockPos("tap"), ModBlocks.TAP.get().defaultBlockState().setValue(TapBlock.FACING, Direction.NORTH));
if (!(level.getBlockEntity(origin) instanceof IBarrel barrel) || !barrel.getOutput().getStackInSlot(0).isEmpty())
    throw new IllegalStateException("The native tavern barrel must start empty");
var crew = new ArrayList<Object>();
var ids = args.strings("workers");
var posts = args.list("posts");
for (int i = 0; i < ids.size(); i++) {
    var body = Bodies.of(level.getEntity(UUID.fromString(ids.get(i)))).orElseThrow();
    body.pack().clearContent();
    body.pack().insert(new ItemStack(Items.COOKED_BEEF, 24));
    body.licences().trade(WaresContent.trade()).ifPresent(trade -> body.licences().setKeenness(trade, Keenness.FIRST));
    body.licences().trade(Vocations.required(Vocations.HAULING)).ifPresent(trade -> body.licences().setKeenness(trade, Keenness.FOURTH));
    var post = Args.of(Map.of("at", posts.get(i)), "post").blockPos("at");
    body.mob().teleportTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5);
    body.mob().setInvulnerable(true);
    crew.add(Map.of("uuid", ids.get(i), "position", post.toShortString()));
}
return Sync.stamp(Map.of("workers", crew, "barrelEmpty", true, "initialProducts", 0));
` }),
});

const READ = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.tavern-read", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "com.github.ysbbbbbb.kaleidoscopetavern.api.blockentity.IPressingTub",
    "com.github.ysbbbbbb.kaleidoscopetavern.api.blockentity.IBarrel",
    "com.github.ysbbbbbb.kaleidoscopetavern.block.brew.TapBlock",
    "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries",
    "net.minecraft.world.Container", "net.minecraft.world.item.ItemStack", "net.minecraft.server.level.ServerLevel",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var stations = args.requireObject("stations");
var tub = (IPressingTub) level.getBlockEntity(stations.blockPos("tub"));
var barrel = (IBarrel) level.getBlockEntity(stations.blockPos("barrel"));
var workers = new ArrayList<Object>();
for (var id : args.strings("workers")) {
    var body = Bodies.of(level.getEntity(UUID.fromString(id))).orElseThrow();
    var mob = body.mob();
    workers.add(Map.of("uuid", id,
        "action", body.doing().map(doing -> doing.what().getPath()).orElse("idle"),
        "toward", body.doing().map(doing -> doing.toward().orElse(doing.what()).getPath()).orElse("idle"),
        "position", List.of(mob.getX(), mob.getY(), mob.getZ()),
        "pack", body.pack().contents().stream().filter(stack -> !stack.isEmpty()).map(Task::item).toList(),
        "walking", !mob.getNavigation().isDone()));
}
var tap = level.getBlockState(args.blockPos("tap"));
var barrelState = new LinkedHashMap<String, Object>();
barrelState.put("brewLevel", barrel.getBrewLevel());
barrelState.put("brewTime", barrel.getBrewTime());
barrelState.put("open", barrel.isOpen());
barrelState.put("fluid", BuiltInRegistries.FLUID.getKey(barrel.getFluid().getFluid().getFluid()).toString());
barrelState.put("fluidAmount", barrel.getFluid().getFluidAmount());
barrelState.put("output", item(barrel.getOutput().getStackInSlot(0)));
barrelState.put("recipe", String.valueOf(barrel.getRecipeId()));
return Sync.stamp(Map.of("time", level.getGameTime(),
    "tub", Map.of("input", item(tub.getItems().getStackInSlot(0)), "fluidAmount", tub.getFluidAmount()),
    "barrel", barrelState, "tapOpen", tap.getValue(TapBlock.OPEN),
    "bottleBlock", BuiltInRegistries.BLOCK.getKey(level.getBlockState(args.blockPos("bottle")).getBlock()).toString(),
    "source", inventory(level, args.blockPos("source")), "target", inventory(level, args.blockPos("target")),
    "workers", workers));
`, members: `
static Map<String,Object> item(ItemStack stack) {
    return Map.of("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), "count", stack.getCount());
}
static Map<String,Object> inventory(ServerLevel level, BlockPos at) {
    var items = new LinkedHashMap<String, Integer>();
    if (level.getBlockEntity(at) instanceof Container container)
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            var stack = container.getItem(slot);
            if (!stack.isEmpty()) items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
    return Map.of("position", List.of(at.getX(), at.getY(), at.getZ()), "items", items);
}
` }),
});
