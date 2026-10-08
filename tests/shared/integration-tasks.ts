import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

// Server tasks behind the Create and Modular Golems integrations, shared by the benches and the promo takes.
// Run them with runColonyTask (tests/e2e/colony-tasks.ts).

type Answer = Record<string, any>;

const NETWORK_IMPORTS = [
  "com.simibubi.create.Create",
  "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity",
  "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity",
  "com.simibubi.create.content.logistics.packagePort.PackagePortTarget.ChainConveyorFrogportTarget",
  "com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour",
  "com.simibubi.create.content.logistics.packagerLink.LogisticsManager",
  "com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour",
  "io.github.izakyl.folkways.plugins.dispatch.DispatchContent",
  "io.github.izakyl.folkways.plugins.dispatch.DispatchPresence",
  "io.github.izakyl.folkways.plugins.dispatch.PackageNetworks",
  "io.github.izakyl.folkways.core.api.vocation.Vocations",
  "io.github.izakyl.folkways.core.api.resident.body.Bodies",
  "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
  "net.minecraft.core.BlockPos",
  "net.minecraft.core.GlobalPos",
  "net.minecraft.server.MinecraftServer",
  "net.minecraft.world.Container",
  "net.minecraft.world.item.Items",
];

const POS = `
private static BlockPos pos(Object raw) {
    var p = (Map<?, ?>) raw;
    return new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(),
        ((Number) p.get("z")).intValue());
}
`;

export const GOLEM_CAST = defineTask<Answer, Answer>({
  name: "folkways.integration.golem-cast",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "dev.xkmc.modulargolems.content.item.golem.GolemHolder",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.engine.colony.Endorsements",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.phys.Vec3",
    ],
    body: `
var server = ctx.server();
var args = Args.of(ctx);
var player = server.getPlayerList().getPlayerByName(args.string("player"));
var level = player.serverLevel();
var colony = ColonyGround.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
var origin = (Map<?, ?>) args.value("origin");
double x = ((Number) origin.get("x")).doubleValue();
double y = ((Number) origin.get("y")).doubleValue();
double z = ((Number) origin.get("z")).doubleValue();
boolean golemsOnly = !Boolean.FALSE.equals(args.value("freeze_humans"));
var trades = args.value("trades") instanceof List<?> named ? named.stream().map(String::valueOf).toList()
    : golemsOnly ? List.of("hauling", "crafting") : null;
for (var resident : golemsOnly ? colony.residents() : List.<io.github.izakyl.folkways.core.api.resident.Resident>of()) {
    var entity = level.getEntity(resident.id());
    if (entity instanceof net.minecraft.world.entity.Mob mob) { mob.setNoAi(true); mob.teleportTo(x, y, z - 14); }
    var body = Bodies.of(entity).orElseThrow();
    for (var vocation : Vocations.all()) body.licences().trade(vocation).ifPresent(trade -> body.licences().allow(trade, false));
}
var uuids = new ArrayList<String>();
int index = 0;
var names = args.value("kinds") instanceof List<?> supplied ? supplied
    : List.of("metal", "humanoid", "dog", "humanoid", "metal", "humanoid", "dog", "metal", "humanoid");
for (var rawName : names) {
    String name = String.valueOf(rawName);
    Vec3 position = new Vec3(x - 4 + (index % 3) * 4, y, z - 2 + (index / 3) * 2);
    if (args.value("positions") instanceof List<?> positions) {
        var p = (Map<?, ?>) positions.get(index);
        position = new Vec3(((Number) p.get("x")).doubleValue() + 0.5, ((Number) p.get("y")).doubleValue(), ((Number) p.get("z")).doubleValue() + 0.5);
    }
    var id = ResourceLocation.parse("modulargolems:" + name + "_golem_holder");
    var holder = (GolemHolder<?, ?>) BuiltInRegistries.ITEM.get(id);
    var stack = holder.withUniformMaterial(ResourceLocation.parse("modulargolems:iron"));
    boolean summoned = holder.summon(stack, level, position, player, golem -> {
        var body = Bodies.of(golem).orElseThrow();
        var attempt = Endorsements.pointed(level, colony, player, body).orElseThrow();
        if (attempt.refusal().isPresent()) throw new IllegalStateException(attempt.refusal().get().key());
        if (!body.colonyId().filter(colony.id()::equals).isPresent()) throw new IllegalStateException("Golem did not join");
        if (Boolean.TRUE.equals(args.value("seed_mending")) && name.equals("metal")) {
            golem.setHealth(golem.getMaxHealth() * 0.5f);
            body.pack().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 4));
        }
        if (trades != null) for (var vocation : Vocations.all()) body.licences().trade(vocation).ifPresent(trade ->
            body.licences().allow(trade, trades.contains(vocation.id().getPath())));
        if (args.value("pack") instanceof List<?> pack) for (int slot = 0; slot < pack.size(); slot++) {
            var kit = (Map<?, ?>) pack.get(slot);
            body.pack().setItem(slot, new net.minecraft.world.item.ItemStack(
                BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(kit.get("item")))),
                ((Number) kit.get("count")).intValue()));
        }
        uuids.add(golem.getUUID().toString());
    });
    if (!summoned) throw new IllegalStateException("Could not summon " + id);
    index++;
}
if (uuids.size() != names.size()) {
    throw new TaskException("refused", "only " + uuids.size() + " of " + names.size() + " golems joined", Map.of("uuids", uuids));
}
return Sync.stamp(Map.of("ok", true, "uuids", uuids));
`,
  }),
});

// Draws patrol routes on the colony as the book would, each a list of points walked end to end and back.
export const PATROL_ROUTES = defineTask<Answer, Answer>({
  name: "folkways.integration.patrol-routes",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.engine.colony.ColonySchemas",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var server = ctx.server();
var args = Args.of(ctx);
var level = server.overworld();
var colony = ColonyGround.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
var front = ColonyFront.of(colony);
var beat = ResourceLocation.fromNamespaceAndPath("folkways", "patrol");
var settings = ColonySettings.byDefault(ColonySchemas.ofDelegation(beat));
var drawn = new ArrayList<Object>();
for (var rawRoute : args.list("routes")) {
    var points = new ArrayList<BlockPos>();
    for (var raw : (List<?>) rawRoute) {
        var p = (Map<?, ?>) raw;
        points.add(new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(),
            ((Number) p.get("z")).intValue()));
    }
    var path = front.addPath(level, beat, points, settings)
        .orElseThrow(() -> new IllegalStateException("The colony would not hold the route " + points));
    drawn.add(path.id().toString());
}
return Sync.stamp(Map.of("ok", true, "routes", drawn));
`,
  }),
});

export const GOLEM_WORK = defineTask<Answer, Answer>({
  name: "folkways.integration.golem-work",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.Items",
      "net.minecraft.core.registries.BuiltInRegistries",
    ],
    body: `
var level = ctx.server().overworld();
var args = Args.of(ctx);
var workers = new ArrayList<Object>();
var missing = new ArrayList<Object>();
for (var uuid : args.list("uuids")) {
    var entity = level.getEntity(UUID.fromString(String.valueOf(uuid)));
    if (entity == null || !entity.isAlive()) {
        missing.add(uuid);
        continue;
    }
    var body = Bodies.of(entity).orElseThrow();
    workers.add(Map.of("uuid", uuid, "doing", body.doing().map(Object::toString).orElse("idle"),
        "x", entity.getX(), "z", entity.getZ(), "health", body.mob().getHealth(), "max_health", body.mob().getMaxHealth()));
}
var target = (Map<?, ?>) args.value("target");
var at = new BlockPos(((Number) target.get("x")).intValue(), ((Number) target.get("y")).intValue(),
    ((Number) target.get("z")).intValue());
var chest = (Container) level.getBlockEntity(at);
var stock = new TreeMap<String, Integer>();
int delivered = 0, produced = 0;
for (int i = 0; i < chest.getContainerSize(); i++) {
    var stack = chest.getItem(i);
    if (!stack.isEmpty()) stock.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
    if (stack.is(Items.OAK_PLANKS) || stack.is(Items.STICK) || stack.is(Items.IRON_INGOT) || stack.is(Items.COOKED_BEEF) || stack.is(Items.STONE_BRICKS)) produced += stack.getCount();
    if (stack.is(Items.OAK_LOG)) delivered += stack.getCount();
}
return Map.of("ok", true, "workers", workers, "missing", missing, "delivered", delivered, "produced", produced, "stock", stock);
`,
  }),
});

export const WIRE_NETWORK = defineTask<Answer, Answer>({
  name: "folkways.integration.wire-network",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: NETWORK_IMPORTS,
    body: `
var server = ctx.server();
var level = server.overworld();
var args = Args.of(ctx);
var network = UUID.randomUUID();
var linked = new ArrayList<Object>(args.has("links") ? args.list("links") : List.of(args.value("link")));
linked.add(args.value("ticker"));
for (int l = 0; l < linked.size(); l++) {
    var link = BlockEntityBehaviour.get(level, pos(linked.get(l)), LogisticallyLinkedBehaviour.TYPE);
    if (link == null) throw new IllegalStateException("No logistics link at " + linked.get(l));
    link.unload();
    link.freqId = network;
    if (l < linked.size() - 1) {
        var global = GlobalPos.of(level.dimension(), link.getPos());
        Create.LOGISTICS.linkLoaded(network, global);
        Create.LOGISTICS.linkAdded(network, global,
            server.getPlayerList().getPlayerByName(args.string("player")).getUUID());
    }
    LogisticallyLinkedBehaviour.keepAlive(link);
    link.blockEntity.notifyUpdate();
}
var chains = args.list("chains");
var edges = new ArrayList<List<?>>();
if (args.has("edges")) for (var edge : args.list("edges")) edges.add((List<?>) edge);
else for (int i = 0; i < (chains.size() == 2 ? 1 : chains.size()); i++) edges.add(List.of(i, (i + 1) % chains.size()));
for (var edge : edges) {
    var a = (ChainConveyorBlockEntity) level.getBlockEntity(pos(chains.get(((Number) edge.get(0)).intValue())));
    var b = (ChainConveyorBlockEntity) level.getBlockEntity(pos(chains.get(((Number) edge.get(1)).intValue())));
    a.addConnectionTo(b.getBlockPos()); b.addConnectionTo(a.getBlockPos());
    a.notifyUpdate(); b.notifyUpdate();
}
var ports = new ArrayList<Object>(args.has("sends") ? args.list("sends") : List.of(args.value("send")));
var docks = new ArrayList<Object>(args.has("send_chains") ? args.list("send_chains") : List.of(0));
ports.add(args.value("receive"));
docks.add(args.has("receive_chain") ? args.value("receive_chain") : chains.size() - 1);
for (int index = 0; index < ports.size(); index++) {
    boolean receiving = index == ports.size() - 1;
    var port = (PackagePortBlockEntity) level.getBlockEntity(pos(ports.get(index)));
    port.addressFilter = receiving ? "Folkways" : "Supplier";
    port.acceptsPackages = receiving;
    port.target = new ChainConveyorFrogportTarget(pos(chains.get(((Number) docks.get(index)).intValue())).subtract(port.getBlockPos()),
        0.0f, Optional.empty(), false);
    port.target.setup(port, level, port.getBlockPos());
    port.target.register(port, level, port.getBlockPos());
    port.filterChanged();
    port.notifyUpdate();
}
var colony = ColonyGround.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
var desk = pos(args.value("ticker"));
int crew = 0;
int builders = args.has("builders") ? (int) args.integer("builders") : 0;
for (var resident : Boolean.FALSE.equals(args.value("assign_crew")) ? List.<io.github.izakyl.folkways.core.api.resident.Resident>of() : colony.residents()) {
    var body = Bodies.of(level.getEntity(resident.id())).orElseThrow();
    body.pack().insert(new net.minecraft.world.item.ItemStack(Items.BREAD, 16));
    boolean keeper = crew == 0;
    String calling = crew >= colony.residents().size() - builders ? "building" : "crafting";
    for (var vocation : Vocations.all()) {
        boolean allowed = keeper ? vocation.id().equals(DispatchContent.VOCATION)
            : vocation.id().equals(Vocations.HAULING) || vocation.id().getPath().equals(calling);
        body.licences().trade(vocation).ifPresent(trade -> body.licences().allow(trade, allowed));
    }
    var stand = keeper ? desk.south(2) : pos(args.value("receive")).south(2).east(crew);
    body.mob().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
    crew++;
}
var tickerLink = BlockEntityBehaviour.get(level, desk, LogisticallyLinkedBehaviour.TYPE);
var adapter = PackageNetworks.get();
if (!adapter.desksOf(level, network).contains(desk)) throw new IllegalStateException("Stock ticker not discovered");
var other = UUID.randomUUID();
tickerLink.freqId = other;
if (adapter.desksOf(level, network).contains(desk) || !adapter.desksOf(level, other).contains(desk)) {
    throw new IllegalStateException("Stock ticker discovery retained an old network binding");
}
tickerLink.freqId = network;
var dispatch = (DispatchPresence) colony.service(DispatchContent.ID, Object.class).orElseThrow();
if (!dispatch.take(network)) throw new IllegalStateException("Network handover refused");
if (!dispatch.holds(network)) throw new TaskException("refused", "dispatch took network " + network + " but does not hold it");
return Sync.stamp(Map.of("ok", true, "network", network.toString()));
`,
    members: POS,
  }),
});

export const NETWORK_STATE = defineTask<Answer, Answer>({
  name: "folkways.integration.network-state",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: NETWORK_IMPORTS,
    body: `
var server = ctx.server();
var level = server.overworld();
var args = Args.of(ctx);
var link = BlockEntityBehaviour.get(level, pos(args.value("link")), LogisticallyLinkedBehaviour.TYPE);
int stock = 0;
for (var lot : LogisticsManager.getSummaryOfNetwork(link.freqId, true).getStacks()) {
    if (lot.stack.is(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(String.valueOf(args.has("input_item") ? args.value("input_item") : "minecraft:oak_log"))))) stock += lot.count;
}
var chest = (Container) level.getBlockEntity(pos(args.value("target")));
var store = (Container) level.getBlockEntity(pos(args.has("store") ? args.value("store") : args.value("target")));
int delivered = 0, sticks = 0, chests = 0;
for (int i = 0; i < store.getContainerSize(); i++) {
    if (store.getItem(i).is(Items.STICK)) sticks += store.getItem(i).getCount();
    if (store.getItem(i).is(Items.CHEST)) chests += store.getItem(i).getCount();
}
for (int i = 0; i < chest.getContainerSize(); i++) {
    if (chest.getItem(i).is(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(String.valueOf(args.has("output_item") ? args.value("output_item") : "minecraft:oak_planks"))))) delivered += chest.getItem(i).getCount();
}
var colony = ColonyGround.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
int seated = 0;
for (var resident : Boolean.FALSE.equals(args.value("assign_crew")) ? List.<io.github.izakyl.folkways.core.api.resident.Resident>of() : colony.residents()) {
    var entity = level.getEntity(resident.id());
    if (entity != null && entity.isPassenger()) seated++;
}
var a = (ChainConveyorBlockEntity) level.getBlockEntity(pos((args.list("chains")).get(0)));
var port = (PackagePortBlockEntity) level.getBlockEntity(pos(args.value("receive")));
int parcels = 0;
for (int i = 0; i < port.inventory.getSlots(); i++) if (!port.inventory.getStackInSlot(i).isEmpty()) parcels++;
return Map.of("ok", true, "stock", stock, "delivered", delivered, "seated", seated,
    "parcels", parcels, "chain_speed", a.getSpeed(), "sticks", sticks, "chests", chests);
`,
    members: POS,
  }),
});

export const COLONY_PACKS = defineTask<Answer, Answer>({
  name: "folkways.integration.colony-packs",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server();
var args = Args.of(ctx);
var colony = ColonyGround.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
var totals = new TreeMap<String, Integer>();
int read = 0;
for (var resident : colony.residents()) {
    var entity = server.overworld().getEntity(resident.id());
    if (entity == null) continue;
    var body = Bodies.of(entity);
    if (body.isEmpty()) continue;
    read++;
    for (var stack : body.get().pack().contents()) {
        if (!stack.isEmpty()) totals.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
    }
}
return Map.of("ok", true, "totals", totals, "read", read, "expected", colony.residents().size());
`,
  }),
});
