import { defineTask, javaTask, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { licenseCrewFast, setMaintainDemandsFast } from "./fast-setup";

function layout(origin: Vec) {
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  return {
    pond: { min: at(6, -2, -12), max: at(11, -1, -9) },
    fishery: { min: at(7, 0, -13), max: at(10, 0, -13) },
    pens: [
      { animal: "minecraft:cow", target: 1, shear: false, min: at(-26, 0, -11), max: at(-24, 0, -9) },
      { animal: "minecraft:sheep", target: 2, shear: true, min: at(-22, 0, -11), max: at(-20, 0, -9) },
    ],
    // Separate output from input; bucket pickup stays on the open north bank of the cow pen.
    stores: [at(5, 0, -13), at(-24, 0, -14), at(-26, 0, -14), at(-21, 0, -6)],
    posts: [at(8, 0, -13), at(-25, 0, -13), at(-22, 0, -6)],
  };
}

/** Stage once while frozen, immediately before recording. Thereafter the ordinary scheduler owns
 * fishing, shearing, milking, hauling and every movement; the read task below never changes the world. */
export async function stageExtraYard(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  if (workersUUIDs.length !== 3 || new Set(workersUUIDs).size !== 3) {
    throw new Error("The extra yard needs three distinct new residents: one fisher and two herders");
  }
  const plan = layout(origin);
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  const fill = async (a: Vec, b: Vec, state: string) =>
    tryCommand(server, `fill ${commandPos(a)} ${commandPos(b)} ${state}`);
  // An independent two-block-deep pond leaves the existing vegetable beds and their centre pool intact.
  await fill(at(5, 0, -13), at(12, 3, -8), "minecraft:air");
  await fill(at(5, -3, -13), at(12, -1, -8), "minecraft:stone_bricks");
  await fill(plan.pond.min, plan.pond.max, "minecraft:water");
  // Both nine-cell pens are deliberately small enough for the production herder to work over the fence.
  await fill(at(-28, 0, -14), at(-18, 3, -6), "minecraft:air");
  await fill(at(-28, -1, -14), at(-18, -1, -6), "minecraft:grass_block");
  for (const x of [-27, -23, -19]) await fill(at(x, 0, -12), at(x, 0, -8), "minecraft:spruce_fence");
  for (const z of [-12, -8]) await fill(at(-27, 0, z), at(-19, 0, z), "minecraft:spruce_fence");
  // Residents use the native doorway goal to open these gates when collecting supplies or leaving a pen.
  for (const x of [-25, -21]) await world.command(server,
    `setblock ${commandPos(at(x, 0, -12))} minecraft:spruce_fence_gate[facing=north,open=false]`);
  // Keep one full empty cell between every pedestal and both the pens and street rails.
  for (const [x, z] of [[-30, -12], [12, -13]] as const) {
    await world.command(server, `setblock ${commandPos(at(x, 0, z))} minecraft:cut_sandstone`);
    await world.command(server, `setblock ${commandPos(at(x, 1, z))} minecraft:lantern`);
  }
  // The complete z=-7 row stays empty on the south side of the z=-8 fence.
  // Feed, water and wool storage sit beyond that aisle, never against the rail.
  for (const x of [-26, -25]) await world.command(server,
    `setblock ${commandPos(at(x, 0, -6))} minecraft:hay_block`);
  await world.command(server, `setblock ${commandPos(at(-19, 0, -6))} minecraft:water_cauldron[level=3]`);
  // No cows, sheep, fish, wool or milk are stocked as finished output.
  await world.command(server, `setblock ${commandPos(plan.stores[0])} minecraft:barrel[facing=up]{Items:[{Slot:0b,id:"minecraft:fishing_rod",count:1},{Slot:1b,id:"minecraft:cooked_beef",count:24}]}`);
  await world.command(server, `setblock ${commandPos(plan.stores[2])} minecraft:barrel[facing=up]{Items:[{Slot:0b,id:"minecraft:shears",count:1},{Slot:1b,id:"minecraft:bucket",count:8},{Slot:2b,id:"minecraft:cooked_beef",count:24}]}`);
  for (const index of [1, 3]) await world.command(server,
    `setblock ${commandPos(plan.stores[index])} minecraft:barrel[facing=up]`);
  const marked = await runColonyTask(server, MARK_MEMBERS, {
    player, colony_id, cells: plan.stores.flatMap(cell => [cell.x, cell.y, cell.z]),
  });
  const licensed = await licenseCrewFast(server, workersUUIDs.map((uuid, i) => ({
    uuid, trades: [i === 0 ? "fishing" : "herding", "hauling"],
  })));
  const staged = await runColonyTask(server, STAGE, { colony_id, origin, workers: workersUUIDs, ...plan });
  // The one-off real delivery forces the production MilkHost/MilkNode path, rather than merely displaying a cow.
  const orders = await setMaintainDemandsFast(server, player, [
    { at: plan.stores[1], item: "minecraft:milk_bucket", count: 1, low: 1, once: true },
  ]);
  return { ...plan, workers: workersUUIDs, marked, licensed, staged, orders,
    initial: await readExtraYard(server, colony_id, origin, workersUUIDs), via: "native-fishing-and-pasture" };
}

/** Evidence only: no actor placement, inventory edits, growth changes or manual work commits. */
export async function readExtraYard(
  server: MinecraftServer, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  return runColonyTask(server, READ, { colony_id, origin, workers: workersUUIDs, ...layout(origin) });
}

const STAGE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.cookery-extra-yard-stage", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.engine.colony.*", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "io.github.izakyl.folkways.plugins.orders.OrdersContent", "io.github.izakyl.folkways.core.api.terms.ItemFilter",
    "io.github.izakyl.folkways.core.api.resident.body.Keenness", "io.github.izakyl.folkways.core.api.vocation.Vocations",
    "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation", "net.minecraft.world.item.*",
    "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.animal.*", "net.minecraft.world.phys.AABB",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var colony = ColonyGround.of(ctx.server(), UUID.fromString(args.string("colony_id"))).orElseThrow();
var o = args.requireObject("origin").raw();
int ox = ((Number)o.get("x")).intValue(), oy = ((Number)o.get("y")).intValue(), oz = ((Number)o.get("z")).intValue();
var bounds = new AABB(ox - 28, oy, oz - 14, ox - 18, oy + 4, oz - 6);
if (!level.getEntitiesOfClass(Animal.class, bounds, beast -> beast.getTags().contains("folkways_promo_extra_yard")).isEmpty())
    throw new IllegalStateException("The extra yard was already staged; refusing to duplicate its animals");
var fish = (Map<?, ?>) args.value("fishery");
var fishery = ZoneDrafts.zone(level, colony, ResourceLocation.parse("folkways:fish"),
    point((Map<?, ?>) fish.get("min")), point((Map<?, ?>) fish.get("max")), ColonySettings.empty()).orElseThrow();
// Native shelf filters keep dining food and general hauling out of these work supplies.
var filters = List.of(
    List.of("minecraft:fishing_rod", "minecraft:cooked_beef", "minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish", "minecraft:pufferfish"),
    List.of("minecraft:milk_bucket"), List.of("minecraft:bucket", "minecraft:shears", "minecraft:cooked_beef"),
    List.of("#minecraft:wool"));
for (int i = 0; i < filters.size(); i++) {
    var entries = new ArrayList<ItemFilter>();
    for (String value : filters.get(i)) entries.add(value.startsWith("#")
        ? ItemFilter.tag(ResourceLocation.parse(value.substring(1))) : ItemFilter.item(ResourceLocation.parse(value)));
    var at = point((Map<?, ?>) args.list("stores").get(i));
    var attempt = ColonyBoards.setItemFilters(colony, OrdersContent.ID, Optional.of(at), "filter", entries,
        ColonyViews.forBoard(colony, level, Optional.of(at))).orElseThrow();
    if (attempt.refusal().isPresent()) throw new IllegalStateException("Yard shelf filter refused: " + attempt.refusal().get().key());
    // Fresh shelves begin in exclude mode; the public board action switches them to admit-only.
    var mode = ColonyBoards.act(level, colony, OrdersContent.ID, Optional.of(at), "filter_mode").orElseThrow();
    if (mode.refusal().isPresent()) throw new IllegalStateException("Yard shelf mode refused: " + mode.refusal().get().key());
}
var zones = new ArrayList<Object>();
zones.add(Map.of("id", fishery.id().toString(), "kind", "fishing"));
for (var raw : args.list("pens")) {
    var pen = (Map<?, ?>) raw;
    var settings = ColonySettings.empty()
        .with("animal", new ColonySettings.Value.Choice(ResourceLocation.parse(String.valueOf(pen.get("animal")))))
        .with("target", new ColonySettings.Value.Count(((Number)pen.get("target")).intValue()))
        .with("shear", new ColonySettings.Value.Flag(Boolean.TRUE.equals(pen.get("shear"))));
    var zone = ZoneDrafts.zone(level, colony, ResourceLocation.parse("folkways:pasture"),
        point((Map<?, ?>) pen.get("min")), point((Map<?, ?>) pen.get("max")), settings).orElseThrow();
    zones.add(Map.of("id", zone.id().toString(), "kind", pen.get("animal")));
}
var animals = new ArrayList<Object>();
Cow cow = EntityType.COW.create(level);
if (cow == null) throw new IllegalStateException("Cow creation failed");
placeAnimal(level, cow, ox - 24.5, oy, oz - 9.5);
animals.add(Map.of("uuid", cow.getUUID().toString(), "kind", "cow"));
for (double z : new double[] {-10.5, -8.5}) {
    Sheep sheep = EntityType.SHEEP.create(level);
    if (sheep == null) throw new IllegalStateException("Sheep creation failed");
    sheep.setSheared(false);
    placeAnimal(level, sheep, ox - 20.5, oy, oz + z);
    animals.add(Map.of("uuid", sheep.getUUID().toString(), "kind", "sheep"));
}
var workers = new ArrayList<Object>();
var ids = args.strings("workers");
for (int i = 0; i < ids.size(); i++) {
    var entity = level.getEntity(UUID.fromString(ids.get(i)));
    var body = Bodies.of(entity).orElseThrow();
    String role = i == 0 ? "fishing" : "herding";
    for (var vocation : Vocations.all()) body.licences().trade(vocation).ifPresent(trade -> {
        body.licences().allow(trade, vocation.id().getPath().equals(role) || vocation.id().equals(Vocations.HAULING));
        body.licences().setKeenness(trade, vocation.id().getPath().equals(role) ? Keenness.FIRST : Keenness.FOURTH);
    });
    body.pack().clearContent();
    body.pack().insert(new ItemStack(i == 0 ? Items.FISHING_ROD : i == 1 ? Items.BUCKET : Items.SHEARS));
    body.pack().insert(new ItemStack(Items.COOKED_BEEF, 24));
    var post = point((Map<?, ?>) args.list("posts").get(i));
    // One initial stage position before recording; the scheduler performs all subsequent travel and work.
    entity.teleportTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5);
    workers.add(Map.of("uuid", ids.get(i), "role", i == 0 ? "fishing" : i == 1 ? "milking" : "shearing"));
}
return Sync.stamp(Map.of("zones", zones, "animals", animals, "workers", workers));
`, members: `
static BlockPos point(Map<?, ?> raw) {
    return new BlockPos(((Number)raw.get("x")).intValue(), ((Number)raw.get("y")).intValue(), ((Number)raw.get("z")).intValue());
}
static void placeAnimal(net.minecraft.server.level.ServerLevel level, Animal animal, double x, double y, double z) {
    animal.setAge(0);
    animal.setPersistenceRequired();
    animal.addTag("folkways_promo_extra_yard");
    animal.setPos(x, y, z);
    if (!level.addFreshEntity(animal)) throw new IllegalStateException("Could not spawn the initial yard animal");
}
` }),
});

const READ = defineTask<Record<string, any>, any>({
  name: "folkways.promo.cookery-extra-yard-read", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.engine.colony.ColonyGround", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
    "io.github.izakyl.folkways.core.api.terms.Containers", "io.github.izakyl.folkways.front.api.PastDay",
    "io.github.izakyl.folkways.base.persist.Reader", "io.github.izakyl.folkways.plugins.fishing.FishingContent",
    "net.minecraft.core.BlockPos",
    "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.world.entity.animal.*", "net.minecraft.world.phys.AABB",
    "net.minecraft.world.item.Items", "net.minecraft.tags.ItemTags", "net.minecraft.world.Container",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var colony = ColonyGround.of(ctx.server(), UUID.fromString(args.string("colony_id"))).orElseThrow();
var o = args.requireObject("origin").raw();
int ox = ((Number)o.get("x")).intValue(), oy = ((Number)o.get("y")).intValue(), oz = ((Number)o.get("z")).intValue();
var workers = new ArrayList<Object>();
var stores = new ArrayList<Object>();
var totals = new LinkedHashMap<String, Integer>();
for (int i = 0; i < args.strings("workers").size(); i++) {
    var id = args.strings("workers").get(i);
    var entity = level.getEntity(UUID.fromString(id));
    var body = entity == null ? null : Bodies.of(entity).orElse(null);
    if (body == null) { workers.add(Map.of("uuid", id, "role", i == 0 ? "fishing" : i == 1 ? "milking" : "shearing", "doing", "missing")); continue; }
    var pack = count(body.pack());
    pack.forEach((item, quantity) -> totals.merge(item, quantity, Integer::sum));
    workers.add(Map.of("uuid", id, "role", i == 0 ? "fishing" : i == 1 ? "milking" : "shearing",
        "doing", body.doing().map(doing -> doing.toward().orElse(doing.what()).getPath()).orElse("idle"),
        "position", List.of(entity.getX(), entity.getY(), entity.getZ()), "items", pack,
        "walking", !body.mob().getNavigation().isDone(),
        "doingDetail", body.doing().map(Object::toString).orElse("idle"),
        "balk", body.balk().<Object>map(balk -> Map.of("doing", balk.doing().toString(),
            "reason", balk.why().translationKey(), "at", balk.at())).orElse(Map.of())));
}
for (var raw : args.list("stores")) {
    var at = point((Map<?, ?>) raw);
    var items = Containers.at(level, at).map(Task::count).orElseGet(LinkedHashMap::new);
    items.forEach((item, quantity) -> totals.merge(item, quantity, Integer::sum));
    stores.add(Map.of("position", List.of(at.getX(), at.getY(), at.getZ()), "items", items));
}
var animals = new ArrayList<Object>();
var cows = new HashSet<UUID>();
int sheared = 0;
for (Animal animal : level.getEntitiesOfClass(Animal.class, new AABB(ox - 28, oy, oz - 14, ox - 18, oy + 4, oz - 6),
        beast -> beast.getTags().contains("folkways_promo_extra_yard"))) {
    boolean bare = animal instanceof Sheep sheep && sheep.isSheared();
    if (bare) sheared++;
    if (animal instanceof Cow) cows.add(animal.getUUID());
    animals.add(Map.of("uuid", animal.getUUID().toString(), "kind", BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString(),
        "position", List.of(animal.getX(), animal.getY(), animal.getZ()), "sheared", bare, "baby", animal.isBaby()));
}
int milked = 0;
for (var raw : colony.kept(net.minecraft.resources.ResourceLocation.parse("folkways:pasture")).getList("milked", 10)) {
    var record = (net.minecraft.nbt.CompoundTag) raw;
    if (record.hasUUID("cow") && cows.contains(record.getUUID("cow"))) milked++;
}
// Production evidence comes only from completed CastNode outcomes, never from movable inventory.
// Load a private read snapshot and restrict it to this yard's real pond and dimension.
var tally = new PastDay();
tally.load(Reader.of(colony.kept(FishingContent.ID).getCompound("made")));
var pond = (Map<?, ?>) args.value("pond");
var pondMin = point((Map<?, ?>) pond.get("min"));
var pondMax = point((Map<?, ?>) pond.get("max"));
var caughtItems = new LinkedHashMap<String, Long>();
tally.within(at -> at.in(level) && at.cell().getX() >= pondMin.getX() && at.cell().getX() <= pondMax.getX()
    && at.cell().getY() >= pondMin.getY() && at.cell().getY() <= pondMax.getY()
    && at.cell().getZ() >= pondMin.getZ() && at.cell().getZ() <= pondMax.getZ(),
    level.getGameTime() - PastDay.TICKS).forEach((item, quantity) -> caughtItems.put(item.toString(), quantity));
long caught = caughtItems.values().stream().mapToLong(Long::longValue).sum();
int fishCount = java.util.stream.Stream.of("minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish", "minecraft:pufferfish")
    .mapToInt(item -> totals.getOrDefault(item, 0)).sum();
int woolCount = totals.entrySet().stream().filter(entry -> entry.getKey().endsWith("_wool")).mapToInt(Map.Entry::getValue).sum();
var result = new LinkedHashMap<String, Object>();
result.put("time", level.getGameTime()); result.put("fishCount", fishCount); result.put("sheared", sheared);
result.put("caught", caught); result.put("caughtItems", caughtItems);
result.put("milkCount", totals.getOrDefault("minecraft:milk_bucket", 0)); result.put("milked", milked); result.put("woolCount", woolCount);
// Persisted order/shelf snapshots and fresh body refusals reveal input, delivery and reach failures.
var orderState = colony.kept(net.minecraft.resources.ResourceLocation.parse("folkways:orders"));
var orders = new ArrayList<Object>();
for (var raw : orderState.getList("orders", 10)) {
    var order = (net.minecraft.nbt.CompoundTag) raw;
    orders.add(Map.of("into", order.getCompound("into").toString(), "what", order.getCompound("what").toString(),
        "low", order.getInt("low"), "count", order.getInt("count"),
        "remaining", order.getLong("remaining"), "standing", order.getBoolean("standing")));
}
result.put("orders", orders); result.put("shelves", orderState.getList("shelves", 10).toString());
result.put("workers", workers); result.put("animals", animals); result.put("stores", stores);
return result;
`, members: `
static BlockPos point(Map<?, ?> raw) {
    return new BlockPos(((Number)raw.get("x")).intValue(), ((Number)raw.get("y")).intValue(), ((Number)raw.get("z")).intValue());
}
static LinkedHashMap<String, Integer> count(Container container) {
    var items = new LinkedHashMap<String, Integer>();
    for (int slot = 0; slot < container.getContainerSize(); slot++) {
        var stack = container.getItem(slot);
        if (!stack.isEmpty()) items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
    }
    return items;
}
` }),
});
