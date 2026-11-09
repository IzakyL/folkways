package io.github.izakyl.folkways.vanilla.groundwork;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.front.api.ZoneView;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.build.Blueprint;
import io.github.izakyl.folkways.vanilla.build.BlueprintBlock;
import io.github.izakyl.folkways.vanilla.build.BlueprintBuildOrder;
import io.github.izakyl.folkways.vanilla.build.BlueprintVolume;
import io.github.izakyl.folkways.vanilla.build.BuildContent;
import io.github.izakyl.folkways.vanilla.build.BuildOrders;
import io.github.izakyl.folkways.vanilla.build.FiledOrderRig;
import io.github.izakyl.folkways.vanilla.build.SchematicPlacement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class GroundworkOrderGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(GroundworkOrderGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void clearFilesAirOnlyAtTheMarkedCells(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            BlockPos corner = f.at;
            Set<BlockPos> cells = Set.of(corner.east(), corner.south(), corner.above());
            cells.forEach(cell -> f.place(cell, Blocks.STONE.defaultBlockState()));
            f.place(corner, Blocks.GOLD_BLOCK.defaultBlockState());
            ZoneView zone = f.zone(cells, settings(true, false));
            f.sweep();
            BlueprintBuildOrder order = f.order();
            helper.assertValueEqual(order.anchor(), zone.at(corner), "origin is the componentwise minimum");
            helper.assertValueEqual(f.targets(), Map.of(corner.east(), Blocks.AIR.defaultBlockState(),
                corner.south(), Blocks.AIR.defaultBlockState(), corner.above(), Blocks.AIR.defaultBlockState()),
                "explicit air covers exactly the non-box zone, leaving its hole absent");
            helper.assertTrue(f.blueprint().confirmed(), "the zone settings already confirm the target");
            helper.assertTrue(!order.name().isBlank(), "the ordinary numbered site name is used");
            helper.assertTrue(f.front.zone(zone.id()).isEmpty(), "filing immediately erases the zone");
            f.sweep();
            helper.assertValueEqual(f.orders(), 1, "later sweeps cannot file a second order");
            helper.assertTrue(f.level.getBlockState(corner).is(Blocks.GOLD_BLOCK), "the hole is untouched");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void fillTargetsEveryMarkedCell(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            BlockPos air = f.at;
            BlockPos solid = air.east();
            BlockPos grass = air.east(2);
            BlockPos aboveFlower = air.east(3);
            BlockPos aboveSnow = air.east(4);
            f.place(solid, Blocks.STONE.defaultBlockState());
            f.place(grass, Blocks.SHORT_GRASS.defaultBlockState());
            f.place(aboveFlower.below(), Blocks.POPPY.defaultBlockState());
            f.place(aboveSnow.below(), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 8));
            ZoneView zone = f.zone(Set.of(air, solid, grass, aboveFlower, aboveSnow), settings(false, true)
                .with(GroundworkContent.WEED, new ColonySettings.Value.Flag(true))
                .with(GroundworkContent.FLOWERS, new ColonySettings.Value.Flag(true)));
            f.sweep();
            helper.assertValueEqual(f.targets(), Map.of(air, Blocks.DIRT.defaultBlockState(),
                solid, Blocks.DIRT.defaultBlockState(), grass, Blocks.DIRT.defaultBlockState(),
                aboveFlower, Blocks.DIRT.defaultBlockState(), aboveSnow, Blocks.DIRT.defaultBlockState()),
                "fill replaces solids and includes cells above flowers");
            helper.assertTrue(f.front.zone(zone.id()).isEmpty(), "fill becomes an order immediately");
            helper.assertTrue(f.ground.goals(f.colony.view(f.level)).isEmpty(), "other ticked work is ignored");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void colonyHoldingsAreIncludedInEveryOrderKind(GameTestHelper helper) {
        for (int kind = 0; kind < 3; kind++) {
            try (Fixture f = new Fixture(helper)) {
                f.colony.hold(BuildContent.ID, new Claimed.Block(WorldPos.of(f.level, f.at))).orElseThrow();
                f.zone(Set.of(f.at, f.at.east()), settings(kind != 1, kind != 0));
                f.sweep();
                BlockState target = kind == 0 ? Blocks.AIR.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                helper.assertValueEqual(f.targets(), Map.of(f.at, target, f.at.east(), target), "holdings are included");
                helper.assertValueEqual(f.order().anchor(), WorldPos.of(f.level, f.at),
                    "the minimum marked cell is the origin");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void zoneSettingsConfirmTheDrawingWithoutASchematicPrinterPass(GameTestHelper helper) {
        SchematicPlacement.Adapter installed = SchematicPlacement.adapter();
        SchematicPlacement.install(Proxies.of(SchematicPlacement.Adapter.class, (proxy, method, args) -> {
            if (method.getName().equals("print")) {
                throw new AssertionError("groundwork must not pass through schematic confirmation");
            }
            return method.invoke(installed, args);
        }));
        try {
            for (int kind = 0; kind < 3; kind++) {
                try (Fixture f = new Fixture(helper)) {
                    f.place(f.at, Blocks.OAK_PLANKS.defaultBlockState());
                    f.place(f.at.east(), Blocks.DIRT.defaultBlockState());
                    boolean fill = kind != 0;
                    ZoneView zone = f.zone(Set.of(f.at, f.at.east()), settings(kind != 1, fill)
                        .with(GroundworkContent.BLOCK, new ColonySettings.Value.Items(List.of(
                            ItemFilter.item(ResourceLocation.withDefaultNamespace("oak_log"))))));
                    long drawn = f.level.getGameTime();
                    f.sweep();
                    BlockState state = fill ? Blocks.OAK_LOG.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    Blueprint actual = f.blueprint();
                    Blueprint expected = new Blueprint(actual.id(), "groundwork", List.of(
                        new BlueprintBlock(BlockPos.ZERO, state), new BlueprintBlock(new BlockPos(1, 0, 0), state)),
                        List.of(), List.of(), BlueprintVolume.of(2, 1, 1).orElseThrow(), drawn, true);
                    helper.assertValueEqual(actual, expected, "the filed drawing is exactly the zone's own confirmed target");
                    helper.assertTrue(f.front.zone(zone.id()).isEmpty(), "the zone is erased on filing");
                    Worker worker = worker(f.level, f.at.south(3));
                    worker.pack().setItem(0, new ItemStack(Items.OAK_LOG, 16));
                    try {
                        FiledOrderRig.work(helper, f.colony, f.order().id(), worker);
                    } finally {
                        worker.body().discard();
                    }
                    helper.assertValueEqual(f.level.getBlockState(f.at), state, "ordinary build work meets the first target");
                    helper.assertValueEqual(f.level.getBlockState(f.at.east()), state, "ordinary build work meets the second target");
                    helper.assertValueEqual(f.orders(), 0, "the completed order disappears");
                    helper.assertValueEqual(f.drawings(), 0, "completion discards the zone's drawing");
                }
            }
        } finally {
            SchematicPlacement.install(installed);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aReloadedCancelledOrderAlsoDiscardsItsBlueprint(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Set.of(f.at), settings(false, true));
            f.sweep();
            FiledOrderRig.reload(f.colony, f.level);
            helper.assertValueEqual(f.orders(), 0, "a restored order can be cancelled normally");
            helper.assertValueEqual(f.drawings(), 0, "single-use status survives saving and loading");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void unusableFillKeepsTheZoneWithARefusal(GameTestHelper helper) {
        for (ItemFilter item : List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("stick")),
                ItemFilter.tag(ResourceLocation.withDefaultNamespace("planks")))) {
            try (Fixture f = new Fixture(helper)) {
                ZoneView zone = f.zone(Set.of(f.at), settings(false, true)
                    .with(GroundworkContent.BLOCK, new ColonySettings.Value.Items(List.of(item))));
                f.sweep();
                f.refused(zone, GroundRefusal.INVALID_FILL);
                f.sweep();
                f.refused(zone, GroundRefusal.INVALID_FILL);
                f.front.setDrawn(zone.id(), settings(false, true));
                f.sweep();
                helper.assertValueEqual(f.orders(), 1, "correcting the fill item lets the zone convert");
                helper.assertTrue(f.front.zone(zone.id()).isEmpty(), "the corrected zone is erased");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aFullBuildBookKeepsTheZoneAndAddsNoBlueprint(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Blueprint seed = new Blueprint(UUID.randomUUID(), "existing", List.of(
                new BlueprintBlock(BlockPos.ZERO, Blocks.DIRT.defaultBlockState())),
                BlueprintVolume.of(1, 1, 1).orElseThrow(), 0);
            for (int i = 0; i < 64; i++) {
                helper.assertTrue(BuildOrders.fileOnce(f.colony, f.level,
                    new Blueprint(UUID.randomUUID(), seed.name(), seed.blocks(), List.of(), List.of(), seed.volume(), i, true),
                    WorldPos.of(f.level, f.at)), "the existing order fits");
            }
            ZoneView zone = f.zone(Set.of(f.at), settings(true, false));
            CompoundTag before = f.book().copy();
            for (int i = 0; i < 3; i++) {
                f.sweep();
                helper.assertTrue(f.front.zone(zone.id()).isPresent(), "a full book leaves the zone intact");
                helper.assertValueEqual(f.book(), before, "failed sweeps add neither orders nor blueprints");
            }
            helper.assertTrue(f.ground.zoneLines(zone, f.colony.view(f.level)).stream().anyMatch(Line.Blocked.class::isInstance),
                "the zone explains why filing failed");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void oversizedBlueprintKeepsTheZone(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            BlockPos far = f.at.east(BlueprintVolume.MAX_EDGE);
            f.level.getChunkAt(far);
            ZoneView zone = f.zone(Set.of(f.at, far), settings(true, false));
            f.sweep();
            f.refused(zone, GroundRefusal.ORDER_TOO_LARGE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void shipZonesFileOrdersInTheirOwnRealm(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            UUID ship = UUID.randomUUID();
            WorldSpaces.Frame frame = new WorldSpaces.Frame() {
                public UUID id() { return ship; }
                public Level level() { return f.level; }
                public BlockPos storageOrigin() { return f.at.offset(-4, -2, -4); }
                public Vec3 toWorld(Vec3 local) { return local.add(100, 0, 100); }
                public Vec3 toLocal(Vec3 world) { return world.subtract(100, 0, 100); }
            };
            Runnable remove = WorldSpaces.install(new WorldSpaces.Source() {
                public Optional<? extends WorldSpaces.Frame> frame(Level level, UUID id) {
                    return level == f.level && id.equals(ship) ? Optional.of(frame) : Optional.empty();
                }
                public Optional<? extends WorldSpaces.Frame> containing(Level level, BlockPos storage) {
                    return level == f.level && Set.of(f.at, f.at.east()).contains(storage)
                        ? Optional.of(frame) : Optional.empty();
                }
                public Optional<? extends WorldSpaces.Frame> aboard(Entity entity) { return Optional.empty(); }
            });
            try {
                f.zone(Set.of(f.at, f.at.east()), settings(true, false));
                f.sweep();
                helper.assertValueEqual(f.order().anchor(), new WorldPos(new Realm.Frame(ship), frame.local(f.at)),
                    "the order anchor keeps the ship realm and local cell");
                helper.assertValueEqual(f.targets().keySet(), Set.of(f.at, f.at.east()),
                    "local blueprint offsets resolve back to the ship's stored cells");
            } finally {
                remove.run();
            }
        }
        helper.succeed();
    }

    private static ColonySettings settings(boolean clear, boolean fill) {
        return ColonySettings.empty()
            .with(GroundworkContent.CLEAR, new ColonySettings.Value.Flag(clear))
            .with(GroundworkContent.FILL, new ColonySettings.Value.Flag(fill))
            .with(GroundworkContent.BLOCK, new ColonySettings.Value.Items(List.of(
                ItemFilter.item(ResourceLocation.withDefaultNamespace("dirt")))));
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ColonyFront front;
        final BlockPos at;
        GroundPresence ground;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            front = ColonyFront.of(colony);
            ground = colony.service(GroundworkContent.ID, GroundPresence.class)
                .orElseThrow(() -> new AssertionError("groundwork service is installed"));
            at = helper.absolutePos(new BlockPos(5, 2, 5));
            for (BlockPos cell : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(6, -1, 4))) {
                place(cell, Blocks.STONE.defaultBlockState());
                for (int up = 1; up <= 4; up++) {
                    place(cell.above(up), Blocks.AIR.defaultBlockState());
                }
            }
            place(at.south(3), Blocks.CHEST.defaultBlockState());
            colony.hold(BuildContent.ID, new Claimed.Block(WorldPos.of(level, at.south(3))))
                .orElseThrow(() -> new AssertionError("the fixture chest is held"));
        }

        void place(BlockPos at, BlockState state) {
            level.setBlock(at, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        ZoneView zone(Set<BlockPos> cells, ColonySettings chosen) {
            return front.addZone(level, GroundworkContent.ZONE, cells, chosen).orElseThrow();
        }

        void sweep() { ground.refresh(level.getServer()); }
        CompoundTag book() { return colony.kept(BuildContent.ID).getCompound("book"); }
        int orders() { return book().getList("buildOrders", Tag.TAG_COMPOUND).size(); }
        int drawings() { return book().getList("blueprints", Tag.TAG_COMPOUND).size(); }

        BlueprintBuildOrder order() {
            helper.assertValueEqual(orders(), 1, "exactly one ordinary order is filed");
            return BlueprintBuildOrder.load(Reader.of(book().getList("buildOrders", Tag.TAG_COMPOUND).getCompound(0)),
                level.registryAccess()).orElseThrow();
        }

        Blueprint blueprint() {
            return Blueprint.load(Reader.of(book().getList("blueprints", Tag.TAG_COMPOUND).getCompound(0)),
                level.registryAccess()).orElseThrow();
        }

        Map<BlockPos, BlockState> targets() {
            Map<BlockPos, BlockState> states = new LinkedHashMap<>();
            BlockPos anchor = order().anchor().block(level);
            blueprint().blocks().forEach(cell -> states.put(cell.worldPos(anchor), cell.state()));
            return states;
        }

        void refused(ZoneView zone, GroundRefusal why) {
            helper.assertTrue(front.zone(zone.id()).isPresent(), "failed conversion keeps the zone");
            helper.assertValueEqual(orders(), 0, "failed conversion files nothing");
            helper.assertValueEqual(drawings(), 0, "failed conversion keeps no synthetic blueprint");
            helper.assertTrue(ground.zoneLines(zone, colony.view(level)).stream().anyMatch(line ->
                line instanceof Line.Blocked blocked && blocked.notice().key().equals(why.translationKey())),
                "the zone line explains the refusal: " + why);
        }

        public void close() {
            ColonyFront.forget(colony.id());
            colony.raze();
        }
    }

    private static Worker worker(ServerLevel level, BlockPos feet) {
        Mob body = EntityType.PIG.create(level);
        body.setPos(Vec3.atBottomCenterOf(feet));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 16));
        return TestWorker.of(body, new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig")), pack).spill(stack -> { });
    }
}
