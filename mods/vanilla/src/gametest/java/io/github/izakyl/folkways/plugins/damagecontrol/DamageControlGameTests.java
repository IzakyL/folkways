package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.shell.structure.StructureSnapshots;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.front.engine.colony.ZoneDrafts;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.build.MaintenanceOrders;
import io.github.izakyl.folkways.plugins.build.SchematicPlacement;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
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
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DamageControlGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(DamageControlGameTests.class); }

    @GameTest(template = "empty")
    public static void fireAndSoulFireAreSmotheredWithoutWater(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        for (Block block : List.of(Blocks.FIRE, Blocks.SOUL_FIRE)) {
            rig.level.setBlock(rig.target.below(), block == Blocks.FIRE
                ? Blocks.NETHERRACK.defaultBlockState() : Blocks.SOUL_SOIL.defaultBlockState(), 3);
            rig.level.setBlock(rig.target, block.defaultBlockState(), 2);
            Node node = rig.fire();
            helper.assertTrue(Commit.run(rig.level, node, rig.worker) instanceof Outcome.Done, "fire is suppressed");
            node.ended(rig.level, Ending.DONE);
            helper.assertTrue(rig.level.getBlockState(rig.target).isAir(), "fire leaves air, never a water source");
        }
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aVanishedFireAndReignitionAreIndependentIncidents(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Node stale = rig.fire();
        helper.assertTrue(Commit.run(rig.level, stale, rig.worker) instanceof Outcome.Done, "a vanished fire is harmless");
        stale.ended(rig.level, Ending.DONE);
        rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
        Node next = rig.fire();
        helper.assertFalse(stale.id().equals(next.id()), "reignition has a fresh task");
        helper.assertTrue(Commit.run(rig.level, next, rig.worker) instanceof Outcome.Done, "reignition is extinguished");
        next.ended(rig.level, Ending.DONE);
        helper.assertTrue(rig.level.getBlockState(rig.target).isAir(), "no fire remains");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fireOutsideReachCannotBeDeleted(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
        rig.worker.body().moveTo(rig.target.getX() - 6, rig.target.getY(), rig.target.getZ());
        Node node = rig.fire();
        helper.assertTrue(Commit.run(rig.level, node, rig.worker) instanceof Outcome.Failed, "unreachable fire refuses");
        helper.assertTrue(rig.level.getBlockState(rig.target).is(Blocks.FIRE), "unreachable fire remains");
        node.ended(rig.level, Ending.DROPPED);
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void overlappingZonesShareOneFireAndOneClaim(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
        FireSurvey survey = new FireSurvey();
        List<ZoneView> zones = List.of(zone(rig.level, Set.of(rig.target)), zone(rig.level, Set.of(rig.target)));
        survey.scan(rig.level, zones, 4, (zone, pos, state) -> { });
        helper.assertValueEqual(survey.fires().size(), 1, "overlapping zones publish one address");
        Incidents claims = new Incidents();
        UUID first = UUID.randomUUID();
        helper.assertTrue(claims.claim(rig.at(), first, 4), "first responder claims the fire");
        helper.assertFalse(claims.claim(rig.at(), UUID.randomUUID(), 4), "second responder cannot duplicate it");
        claims.release(rig.at(), first);
        helper.assertTrue(claims.claim(rig.at(), UUID.randomUUID(), 4), "interruption releases the address");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void incrementalSurveyHonoursItsBudgetAndFindsReignition(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int x = 0; x < 8; x++) { cells.add(rig.target.offset(x, 0, 0)); }
        BlockPos fire = rig.target.offset(7, 0, 0);
        rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
        FireSurvey survey = new FireSurvey();
        List<ZoneView> zones = List.of(zone(rig.level, cells));
        int[] read = {0};
        helper.assertValueEqual(survey.scan(rig.level, zones, 2, (zone, at, state) -> read[0]++), 2, "budget bounds reads");
        helper.assertValueEqual(read[0], 2, "restoration shares the same inspection budget");
        helper.assertTrue(survey.fires().isEmpty(), "scan does not jump to the last cell");
        survey.scan(rig.level, zones, 6, (zone, at, state) -> { });
        helper.assertValueEqual(survey.fires().size(), 1, "next slices find the fire");
        rig.level.removeBlock(fire, false);
        survey.scan(rig.level, zones, 8, (zone, at, state) -> { });
        helper.assertTrue(survey.fires().isEmpty(), "periodic scan removes vanished fires");
        rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
        survey.scan(rig.level, zones, 8, (zone, at, state) -> { });
        helper.assertValueEqual(survey.fires().size(), 1, "periodic scan finds reignition");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void interruptionReleasesSuppressionClaims(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.claims.claim(rig.at(), rig.worker.resident().id(), 1);
        Node node = rig.fire();
        node.released(rig.level, rig.worker, Ending.DROPPED);
        helper.assertValueEqual(rig.claims.workers(), 0, "stopping work releases its claim");
        node.ended(rig.level, Ending.DROPPED);
        helper.assertValueEqual(rig.claims.workers(), 0, "cleanup is idempotent");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void baselineUsesPaletteOffsetsAndSkipsUnsafeBlocks(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.level.setBlock(rig.target, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        rig.level.setBlock(rig.target.east(), Blocks.CHEST.defaultBlockState(), 2);
        rig.level.setBlock(rig.target.west(), Blocks.WATER.defaultBlockState(), 2);
        var snapshot = MaintenanceBaseline.record(rig.level,
            zone(rig.level, Set.of(rig.target, rig.target.east(), rig.target.west())));
        var saved = snapshot.save();
        var loaded = StructureSnapshots.load(saved).orElseThrow();
        helper.assertValueEqual(loaded.cells(), snapshot.cells(), "palette and relative positions survive reload");
        helper.assertValueEqual(loaded.cells().size(), 1, "containers and fluids are not recorded");
        helper.assertTrue(saved.getInt("DataVersion") > 0, "Minecraft data version is explicit");
        helper.assertFalse(MaintenanceBaseline.accepts(Blocks.WHEAT.defaultBlockState()), "crops are excluded");
        helper.assertFalse(MaintenanceBaseline.accepts(Blocks.PISTON.defaultBlockState()), "machines are excluded");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anUnknownPaletteBlockIsSkippedRatherThanReadAsAir(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        var snapshot = new StructureSnapshots.Snapshot(rig.at(), Map.of(BlockPos.ZERO,
            Blocks.STONE.defaultBlockState()), 0);
        CompoundTag saved = snapshot.save();
        saved.getList("palette", Tag.TAG_COMPOUND).getCompound(0).putString("Name", "missing_mod:lost_block");
        var loaded = StructureSnapshots.load(saved).orElseThrow();
        helper.assertTrue(loaded.cells().isEmpty(), "missing registry entries never turn into air targets");
        helper.assertValueEqual(loaded.unsupported(), 1, "unavailable cells are counted");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void restorationConsumesRealStoreMaterials(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Node node = rig.repair(Map.of(rig.at(), Blocks.STONE.defaultBlockState())).getFirst();
        rig.store.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        helper.assertTrue(Commit.run(rig.level, node, rig.worker) instanceof Outcome.Failed,
            "the cannon requirement is stone, not cobblestone");
        rig.store.setItem(0, new ItemStack(Items.STONE, 2));
        helper.assertTrue(Commit.run(rig.level, node, rig.worker) instanceof Outcome.Done, "repair is placed");
        helper.assertValueEqual(rig.store.countItem(Items.STONE), 1, "exactly one real stone is spent");
        helper.assertTrue(rig.level.getBlockState(rig.target) == Blocks.STONE.defaultBlockState(), "exact target state is placed");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aConflictAfterPlanningPreservesBothBlockAndMaterials(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Node node = rig.repair(Map.of(rig.at(), Blocks.STONE.defaultBlockState())).getFirst();
        rig.store.setItem(0, new ItemStack(Items.STONE));
        rig.level.setBlock(rig.target, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        Outcome outcome = Commit.run(rig.level, node, rig.worker);
        helper.assertTrue(outcome instanceof Outcome.Failed failed && failed.why() == DamageControlRefusal.CONFLICT,
            "player rebuilding is reported as a conflict");
        helper.assertValueEqual(rig.store.countItem(Items.STONE), 1, "all materials return on refusal");
        helper.assertTrue(rig.level.getBlockState(rig.target).is(Blocks.GOLD_BLOCK), "player block is preserved");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anAlreadyRestoredCellReturnsItsMaterials(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Node node = rig.repair(Map.of(rig.at(), Blocks.STONE.defaultBlockState())).getFirst();
        rig.store.setItem(0, new ItemStack(Items.STONE));
        rig.level.setBlock(rig.target, Blocks.STONE.defaultBlockState(), 2);
        helper.assertTrue(Commit.run(rig.level, node, rig.worker) instanceof Outcome.Done, "exact state is skipped");
        helper.assertValueEqual(rig.worker.pack().countItem(Items.STONE) + rig.store.countItem(Items.STONE), 1,
            "the skipped cannon shot spends nothing");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bothDoorPartsUseOneCannonShotAndOneDoor(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState();
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        List<Node> repairs = rig.repair(Map.of(rig.at(), lower, WorldPos.of(rig.level, rig.target.above()), upper));
        helper.assertValueEqual(repairs.size(), 1, "two parts form one shot");
        rig.store.setItem(0, new ItemStack(Items.OAK_DOOR, 2));
        helper.assertTrue(Commit.run(rig.level, repairs.getFirst(), rig.worker) instanceof Outcome.Done, "the build path places the door");
        helper.assertValueEqual(rig.store.countItem(Items.OAK_DOOR), 1, "upper half does not charge another door");
        helper.assertTrue(rig.level.getBlockState(rig.target) == lower, "lower state matches the cannon");
        helper.assertTrue(rig.level.getBlockState(rig.target.above()) == upper, "upper state matches the cannon");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void recordingAndReloadingAZoneKeepsItsBaseline(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            var zone = ZoneDrafts.zone(rig.level, colony, DamageControlContent.ID, rig.target.west(),
                rig.target.east().above(), ColonySettings.empty()).orElseThrow();
            DamageControlPresence presence = new DamageControlPresence(colony);
            helper.assertTrue(colony.kept(DamageControlContent.ID).isEmpty(), "old colonies have no automatic baseline");
            rig.level.setBlock(rig.target, Blocks.OAK_PLANKS.defaultBlockState(), 2);
            helper.assertFalse(presence.act(DamageControlContent.ID, "record:" + zone.id(), Optional.empty(), colony.view(rig.level)).refused(),
                "player action records the baseline");
            CompoundTag first = colony.kept(DamageControlContent.ID).copy();
            helper.assertValueEqual(first.getList("baselines", Tag.TAG_COMPOUND).size(), 1, "baseline is kept with colony");
            presence = new DamageControlPresence(colony);
            rig.level.setBlock(rig.target, Blocks.STONE.defaultBlockState(), 2);
            presence.act(DamageControlContent.ID, "record:" + zone.id(), Optional.empty(), colony.view(rig.level));
            CompoundTag saved = colony.kept(DamageControlContent.ID).getList("baselines", Tag.TAG_COMPOUND).getCompound(0);
            helper.assertValueEqual(saved.getLong("revision"), 2L, "re-recording after reload increments revision");
            helper.assertTrue(StructureSnapshots.load(saved.getCompound("snapshot")).orElseThrow().cells().containsValue(Blocks.STONE.defaultBlockState()),
                "new baseline replaces the old one");
        } finally { colony.raze(); rig.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onDutyFireResponsePreemptsWorkBelowFear(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            ZoneDrafts.zone(rig.level, colony, DamageControlContent.ID, rig.target.west().north(),
                rig.target.east().south().above(), ColonySettings.empty()).orElseThrow();
            DamageControlPresence presence = new DamageControlPresence(colony);
            presence.tick(rig.level.getServer());
            Node watch = presence.goals(colony.view(rig.level)).getFirst().nodes().getFirst();
            watch.commit(rig.level, rig.worker);
            rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
            presence.tick(rig.level.getServer());
            List<Urge> urges = presence.urges(rig.worker, colony.view(rig.level));
            helper.assertValueEqual(urges.size(), 1, "on-duty damage control worker responds");
            helper.assertTrue(urges.getFirst().preempts() && urges.getFirst().weight() > Urge.NOW
                && urges.getFirst().weight() < Urge.DIRE, "ordinary work yields; fear and flight win");
            Node responding = urges.getFirst().take().get();
            responding.ended(rig.level, Ending.DROPPED);
        } finally { colony.raze(); rig.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aRecordedZonePublishesRepairWorkAndReportsPlayerRebuilding(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            var zone = ZoneDrafts.zone(rig.level, colony, DamageControlContent.ID, rig.target,
                rig.target.above(), ColonySettings.empty().with("repair", new ColonySettings.Value.Flag(true))).orElseThrow();
            rig.level.setBlock(rig.target, Blocks.STONE.defaultBlockState(), 2);
            DamageControlPresence presence = new DamageControlPresence(colony);
            presence.act(DamageControlContent.ID, "record:" + zone.id(), Optional.empty(), colony.view(rig.level));
            rig.level.removeBlock(rig.target, false);
            presence.tick(rig.level.getServer());
            Node repair = presence.goals(colony.view(rig.level)).stream().flatMap(work -> work.nodes().stream())
                .filter(node -> node.spec().doing().equals(DamageControlContent.REPAIRING)).findFirst().orElseThrow();
            rig.store.setItem(0, new ItemStack(Items.STONE));
            helper.assertTrue(Commit.run(rig.level, repair, rig.worker) instanceof Outcome.Done,
                "a recorded missing cell grows real builder work");
            helper.assertTrue(rig.level.getBlockState(rig.target).is(Blocks.STONE), "the baseline is restored");
            helper.assertValueEqual(rig.store.countItem(Items.STONE), 0, "zone repair consumes real material");
            repair.ended(rig.level, Ending.DONE);
            rig.level.setBlock(rig.target, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
            presence.tick(rig.level.getServer());
            var line = (Line.Text) presence.zoneLines(zone, colony.view(rig.level)).getFirst();
            var contents = (TranslatableContents) line.component().getContents();
            helper.assertValueEqual(((Number) contents.getArgs()[2]).intValue(), 1,
                "the zone card reports a conflicting player block");
        } finally { colony.raze(); rig.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void baselineDoesNotRestoreADoorBeyondTheMarkedZone(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState();
        rig.level.setBlock(rig.target, lower, 2);
        rig.level.setBlock(rig.target.above(), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 2);
        var cut = MaintenanceBaseline.record(rig.level, zone(rig.level, Set.of(rig.target)));
        helper.assertTrue(cut.cells().isEmpty(), "the cannon's implicit upper half must fit in the zone");
        var whole = MaintenanceBaseline.record(rig.level, zone(rig.level, Set.of(rig.target, rig.target.above())));
        helper.assertValueEqual(whole.cells().size(), 2, "a whole door is a valid grouped baseline");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void doubleSlabRepairUsesTheCannonRequirement(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        Node repair = rig.repair(Map.of(rig.at(), slab)).getFirst();
        var nativeCost = SchematicPlacement.adapter().requirement(slab, null).needs().orElseThrow();
        helper.assertValueEqual(repair.spec().needs(), nativeCost, "repair carries the same ItemRequirement as the cannon");
        rig.store.setItem(0, new ItemStack(Items.STONE_SLAB, 2));
        helper.assertTrue(Commit.run(rig.level, repair, rig.worker) instanceof Outcome.Done, "double slab is restored");
        helper.assertValueEqual(rig.store.countItem(Items.STONE_SLAB), 0, "two slabs are consumed");
        helper.assertTrue(rig.level.getBlockState(rig.target) == slab, "the resolved double state is preserved");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSparseRepairCannotOverwriteAnUnrequestedDoorPart(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.level.setBlock(rig.target.above(), Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        List<Node> repairs = rig.repair(Map.of(rig.at(), Blocks.OAK_DOOR.defaultBlockState()));
        helper.assertTrue(repairs.isEmpty(), "a sparse lower half cannot fire a shot that also writes an unrequested cell");
        helper.assertTrue(rig.level.getBlockState(rig.target.above()).is(Blocks.GOLD_BLOCK), "the neighbouring player block is preserved");
        rig.close();
        helper.succeed();
    }

    private static ZoneView zone(ServerLevel level, Set<BlockPos> cells) {
        UUID id = UUID.randomUUID();
        Settings settings = (Settings) Proxy.newProxyInstance(Settings.class.getClassLoader(), new Class<?>[] {Settings.class},
            (proxy, method, args) -> switch (method.getName()) { case "flag" -> true; default -> null; });
        return (ZoneView) Proxy.newProxyInstance(ZoneView.class.getClassLoader(), new Class<?>[] {ZoneView.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "id" -> id;
                case "cells" -> cells;
                case "settings" -> settings;
                case "at" -> WorldPos.of(level, (BlockPos) args[0]);
                case "dimension" -> level.dimension();
                case "delegation" -> DamageControlContent.ID;
                default -> null;
            });
    }

    private static final class Rig {
        final ServerLevel level;
        final BlockPos target;
        final SimpleContainer store = new SimpleContainer(9);
        final Worker worker;
        final Incidents claims = new Incidents();

        Rig(GameTestHelper helper) {
            level = helper.getLevel();
            target = helper.absolutePos(new BlockPos(3, 1, 3));
            for (BlockPos cell : BlockPos.betweenClosed(target.offset(-3, -1, -3), target.offset(8, -1, 3))) {
                level.setBlock(cell, Blocks.STONE.defaultBlockState(), 2);
            }
            var body = PersonBody.RESIDENT.get().create(level);
            body.setNoAi(true);
            body.moveTo(target.getX() - .5, target.getY(), target.getZ() + .5);
            level.addFreshEntity(body);
            worker = (Worker) Proxy.newProxyInstance(Worker.class.getClassLoader(), new Class<?>[] {Worker.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "body" -> body;
                    case "resident" -> body.resident();
                    case "pack" -> body.pack();
                    case "within" -> List.of(new Worker.Store(WorldPos.of(level, target.west(2)), store));
                    case "effects" -> Effects.NONE;
                    case "placement" -> Placement.NOWHERE;
                    case "held" -> ItemStack.EMPTY;
                    case "supplied", "route" -> List.of();
                    default -> null;
                });
        }

        WorldPos at() { return WorldPos.of(level, target); }
        Node fire() { return new ExtinguishNode(at(), worker.resident().id(), Stances.at(level, target.west()), claims, () -> true); }
        List<Node> repair(Map<WorldPos, BlockState> target) {
            return MaintenanceOrders.missing(level, target, DamageControlContent.ID, DamageControlContent.trade(),
                DamageControlContent.REPAIRING, () -> true, at -> true, DamageControlRefusal.CONFLICT, (node, how) -> { });
        }
        void close() { worker.body().discard(); }
    }
}
