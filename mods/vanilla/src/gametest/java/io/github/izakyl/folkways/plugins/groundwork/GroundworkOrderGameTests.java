package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.build.Blueprint;
import io.github.izakyl.folkways.plugins.build.BlueprintBlock;
import io.github.izakyl.folkways.plugins.build.BlueprintBuildOrder;
import io.github.izakyl.folkways.plugins.build.BlueprintVolume;
import io.github.izakyl.folkways.plugins.build.BuildContent;
import io.github.izakyl.folkways.plugins.build.BuildOrders;
import io.github.izakyl.folkways.plugins.build.FiledOrderRig;
import java.lang.reflect.Proxy;
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
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GroundworkOrderGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(GroundworkOrderGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
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
            helper.assertTrue(f.blueprint().confirmed(), "normal schematic confirmation freezes the target");
            helper.assertTrue(!order.name().isBlank(), "the ordinary numbered site name is used");
            helper.assertTrue(f.front.zone(zone.id()).isEmpty(), "filing immediately erases the zone");
            f.sweep();
            helper.assertValueEqual(f.orders(), 1, "later sweeps cannot file a second order");
            helper.assertTrue(f.level.getBlockState(corner).is(Blocks.GOLD_BLOCK), "the hole is untouched");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void fillUsesOpenCellsAndNeverBuriesAFlower(GameTestHelper helper) {
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
                grass, Blocks.DIRT.defaultBlockState(), aboveSnow, Blocks.DIRT.defaultBlockState()),
                "only current air and replaceable cells are filled; flowers, unlike snow, bar the cell above");
            helper.assertTrue(f.front.zone(zone.id()).isEmpty(), "fill becomes an order immediately");
            helper.assertTrue(f.ground.goals(f.colony.view(f.level)).isEmpty(), "other ticked work is ignored");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void clearAndFillOrderTheChosenDefaultStateEverywhere(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Set<BlockPos> cells = Set.of(f.at, f.at.east(), f.at.above());
            f.place(f.at, Blocks.STONE.defaultBlockState());
            f.place(f.at.east(), Blocks.DIRT.defaultBlockState());
            f.place(f.at.above().below(), Blocks.POPPY.defaultBlockState());
            ZoneView zone = f.zone(cells, settings(true, true)
                .with(GroundworkContent.WEED, new ColonySettings.Value.Flag(true))
                .with(GroundworkContent.FELL, new ColonySettings.Value.Flag(true))
                .with(GroundworkContent.HAUL, new ColonySettings.Value.Flag(true)));
            f.sweep();
            helper.assertValueEqual(f.targets(), Map.of(f.at, Blocks.DIRT.defaultBlockState(),
                f.at.east(), Blocks.DIRT.defaultBlockState(), f.at.above(), Blocks.DIRT.defaultBlockState()),
                "clear and fill replace all cells, including solids and cells above flowers");
            helper.assertTrue(f.front.zone(zone.id()).isEmpty(), "the combined zone is erased");
            helper.assertTrue(f.ground.goals(f.colony.view(f.level)).isEmpty(), "no groundwork chores remain");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void colonyHoldingsAreExcludedFromEveryOrderKind(GameTestHelper helper) {
        for (int kind = 0; kind < 3; kind++) {
            try (Fixture f = new Fixture(helper)) {
                f.colony.hold(BuildContent.ID, new Held.Block(WorldPos.of(f.level, f.at))).orElseThrow();
                f.zone(Set.of(f.at, f.at.east()), settings(kind != 1, kind != 0));
                f.sweep();
                BlockState target = kind == 0 ? Blocks.AIR.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                helper.assertValueEqual(f.targets(), Map.of(f.at.east(), target), "holdings are always skipped");
                helper.assertValueEqual(f.order().anchor(), WorldPos.of(f.level, f.at),
                    "excluding the minimum cell does not move the origin");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void anEmptySelectionErasesTheZoneWithoutFiling(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(f.at, Blocks.STONE.defaultBlockState());
            ZoneView zone = f.zone(Set.of(f.at), settings(false, true));
            f.sweep();
            helper.assertTrue(f.front.zone(zone.id()).isEmpty(), "nothing left to fill erases the zone");
            helper.assertValueEqual(f.orders(), 0, "an empty selection creates no order");
            helper.assertValueEqual(f.drawings(), 0, "nor any reusable drawing");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void theFiledOrderBuildsCompletesAndDiscardsItsBlueprint(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ZoneView zone = f.zone(Set.of(f.at), settings(false, true));
            f.sweep();
            helper.assertTrue(f.front.zone(zone.id()).isEmpty(), "the zone is already gone before building");
            Worker worker = worker(f.level, f.at.south(3));
            try {
                FiledOrderRig.work(helper, f.colony, f.order().id(), worker);
            } finally {
                worker.body().discard();
            }
            helper.assertTrue(f.level.getBlockState(f.at).is(Blocks.DIRT), "ordinary build work lays the block");
            helper.assertValueEqual(f.orders(), 0, "the completed order disappears");
            helper.assertValueEqual(f.drawings(), 0, "the synthetic blueprint is discarded with it");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
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

    @GameTest(template = "empty_large", timeoutTicks = 100)
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

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aFullBuildBookKeepsTheZoneAndAddsNoBlueprint(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Blueprint seed = new Blueprint(UUID.randomUUID(), "existing", List.of(
                new BlueprintBlock(BlockPos.ZERO, Blocks.DIRT.defaultBlockState())),
                BlueprintVolume.of(1, 1, 1).orElseThrow(), 0);
            for (int i = 0; i < 64; i++) {
                helper.assertTrue(BuildOrders.fileOnce(f.colony, f.level,
                    new Blueprint(UUID.randomUUID(), seed.name(), seed.blocks(), seed.volume(), i),
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

    @GameTest(template = "empty_large", timeoutTicks = 100)
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

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void oldClearAndFillTargetsAreIgnoredOnTheNextSweep(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ZoneView zone = f.zone(Set.of(f.at, f.at.east()), settings(true, false));
            CompoundTag obsolete = new CompoundTag();
            obsolete.putString("clear", "old malformed targets");
            obsolete.put("fill", new net.minecraft.nbt.ListTag());
            f.colony.keep(GroundworkContent.ID, Writer.of().children("sites", List.of(zone.id()), id ->
                Writer.of().uuid("zone", id).blob("targets", obsolete).tag()).tag());
            f.ground = new GroundPresence(f.colony);
            f.sweep();
            helper.assertValueEqual(f.targets(), Map.of(f.at, Blocks.AIR.defaultBlockState(),
                f.at.east(), Blocks.AIR.defaultBlockState()), "old targets never constrain the current drawing");
            helper.assertTrue(f.front.zone(zone.id()).isEmpty(), "the old zone converts on its next sweep");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void anOldZoneWaitsUntilEveryCellIsLoaded(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            BlockPos unloaded = f.at.east(1_000_000);
            helper.assertFalse(f.level.isLoaded(unloaded), "the distant cell is not loaded");
            ZoneView zone = f.zone(Set.of(f.at, unloaded), settings(true, false));
            f.colony.keep(GroundworkContent.ID, Writer.of().children("sites", List.of(zone.id()), id ->
                Writer.of().uuid("zone", id).blob("targets", new CompoundTag()).tag()).tag());
            f.ground = new GroundPresence(f.colony);
            f.sweep();
            helper.assertTrue(f.front.zone(zone.id()).isPresent(), "empty old targets cannot erase an unloaded zone");
            helper.assertValueEqual(f.orders(), 0, "no partial order is filed");
            helper.assertValueEqual(f.drawings(), 0, "no partial drawing is kept");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
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
            colony.hold(BuildContent.ID, new Held.Block(WorldPos.of(level, at.south(3))))
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
        return (Worker) Proxy.newProxyInstance(GroundworkOrderGameTests.class.getClassLoader(),
            new Class<?>[] {Worker.class}, (proxy, method, args) -> switch (method.getName()) {
                case "body" -> body;
                case "pack" -> pack;
                case "resident" -> new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig"));
                case "effects" -> Effects.NONE;
                case "placement" -> Placement.NOWHERE;
                case "held" -> ItemStack.EMPTY;
                case "within", "route", "supplied" -> List.of();
                default -> null;
            });
    }
}
