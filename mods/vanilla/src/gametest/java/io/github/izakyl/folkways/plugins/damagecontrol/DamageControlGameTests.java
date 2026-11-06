package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Keenness;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.shell.structure.StructureSnapshots;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.engine.Enrollments;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.front.engine.colony.ZoneActions;
import io.github.izakyl.folkways.front.engine.colony.ZoneDrafts;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.build.MaintenanceOrders;
import io.github.izakyl.folkways.plugins.build.SchematicPlacement;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
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
    public static void register(RegisterGameTestsEvent event) {
        event.register(DamageControlGameTests.class);
    }

    @GameTest(template = "empty")
    public static void toolStateIgnoresWorkersAssignedInAnotherDimension(GameTestHelper helper)
        throws ReflectiveOperationException {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            ZoneDrafts.zone(rig.level, colony, DamageControlContent.ID, rig.target, rig.target.above(),
                ColonySettings.empty()).orElseThrow();
            var presence = colony.service(DamageControlContent.ID, DamageControlPresence.class).orElseThrow();
            var field = DamageControlPresence.class.getDeclaredField("incidents");
            field.setAccessible(true);
            var incidents = (Incidents) field.get(presence);
            incidents.claim(WorldPos.of(Level.NETHER, rig.target), UUID.randomUUID(), 4);
            helper.assertValueEqual(presence.state(colony.view(rig.level)).areas().size(), 1,
                "a foreign assignment cannot break this dimension's tool state");
        } finally {
            colony.raze();
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void zonePanelRecordsBaselineAndPersistsEachAreaSetting(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            rig.level.setBlock(rig.target, Blocks.OAK_PLANKS.defaultBlockState(), 2);
            helper.assertFalse(DamageControlCommands.apply(rig.level, colony, new DamageControlCommand(colony.id(),
                DamageControlCommand.Action.CREATE, DamageControlCommand.NONE, rig.target, rig.target.above()))
                .refused(),
                "tool creates protection without opening the ordinary zone picker");
            var front = ColonyFront.of(colony);
            var area = front.zonesIn(rig.level).getFirst();
            helper.assertFalse(ZoneActions.act(rig.level, colony, area.id(), "record").orElseThrow().refused(),
                "panel action records baseline");
            helper.assertValueEqual(colony.kept(DamageControlContent.ID).getList("baselines", Tag.TAG_COMPOUND).size(),
                1, "tool uses the existing persistent baseline");
            var schema = Enrollments.delegation(DamageControlContent.ID).orElseThrow().schema();
            helper.assertValueEqual(schema.settings().stream().map(setting -> setting.key()).toList(),
                List.of("extinguish", "repair", "refill"), "the panel exposes only the three supported flags");
            var legacy = area.settings().with("enabled", new ColonySettings.Value.Flag(false));
            front.setDrawn(area.id(), ColonySettings.load(Reader.of(legacy.save())).conformedTo(schema));
            helper.assertFalse(front.zone(area.id()).orElseThrow().settings().flag("enabled"),
                "old saves may retain the stale disabled flag without exposing it in the schema");
            var presence = colony.service(DamageControlContent.ID, DamageControlPresence.class).orElseThrow();
            rig.level.setBlock(rig.target, Blocks.AIR.defaultBlockState(), 2);
            presence.survey(List.of(colony.view(rig.level)), 100);
            var state = presence.state(colony.view(rig.level));
            helper.assertTrue((state.areas().getFirst().flags() & 1) != 0, "every area is always enabled");
            helper.assertTrue(state.areas().getFirst().baseline(), "tool sees recorded baseline");
            rig.level.setBlock(rig.target, Blocks.STONE.defaultBlockState(), 2);
            presence.survey(List.of(colony.view(rig.level)), 100);
            helper.assertTrue(presence.state(colony.view(rig.level)).marks().stream()
                .anyMatch(mark -> mark.at().equals(rig.target) && mark.color() == 0xE878E8),
                "conflict is sent as a purple marker");
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), rig.level.registryAccess());
            try {
                DamageControlState.CODEC.encode(buffer, state);
                helper.assertValueEqual(DamageControlState.CODEC.decode(buffer), state,
                    "state survives the wire codec");
            } finally {
                buffer.release();
            }
        } finally {
            colony.raze();
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelActionsRejectForeignDeletedAndOtherDimensionAreas(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            var area = ZoneDrafts.zone(rig.level, colony, DamageControlContent.ID, rig.target, rig.target,
                ColonySettings.empty()).orElseThrow();
            helper.assertTrue(DamageControlCommands.apply(rig.level, colony, new DamageControlCommand(UUID.randomUUID(),
                DamageControlCommand.Action.RECORD, area.id(), BlockPos.ZERO, BlockPos.ZERO)).refused(),
                "foreign colony is refused");
            Colony other = Colonies.mint(rig.level.getServer());
            try {
                helper.assertTrue(ZoneActions.act(rig.level, other, area.id(), "record").isEmpty(),
                    "a panel cannot act on another colony's zone");
            } finally {
                other.raze();
            }
            var farm = ZoneDrafts.zone(rig.level, colony, ResourceLocation.fromNamespaceAndPath("folkways", "farm"),
                rig.target, rig.target, ColonySettings.empty()).orElseThrow();
            helper.assertTrue(ZoneActions.act(rig.level, colony, farm.id(), "record").isEmpty(),
                "ordinary zones have no damage control action");
            helper.assertTrue(ZoneActions.act(rig.level, colony, area.id(), "unknown").isEmpty(),
                "unregistered action is refused");
            var nether = rig.level.getServer().getLevel(Level.NETHER);
            helper.assertTrue(ZoneActions.act(nether, colony, area.id(), "record").isEmpty(),
                "a panel cannot act on another dimension's zone");
            var front = ColonyFront.of(colony);
            front.erase(area.id());
            helper.assertTrue(ZoneActions.act(rig.level, colony, area.id(), "record").isEmpty(),
                "a stale panel cannot act on a released zone");
            helper.assertTrue(front.zone(farm.id()).isPresent(), "release preserves unrelated zones");
        } finally {
            colony.raze();
            rig.close();
        }
        helper.succeed();
    }

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
    public static void fireOutsideReachCannotBeDeleted(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
        rig.worker.body().moveTo(rig.target.getX() - 6, rig.target.getY(), rig.target.getZ());
        Node node = rig.fire();
        helper.assertTrue(Commit.run(rig.level, node, rig.worker) instanceof Outcome.Failed,
            "unreachable fire refuses");
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
        survey.scan(rig.level, zones, 4, (zone, pos, state) -> {
        });
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
    public static void anAreaPulseRetiresAssignedAndQueuedIncidents(GameTestHelper helper) {
        Rig first = new Rig(helper);
        Rig second = new Rig(helper);
        Colony colony = Colonies.mint(first.level.getServer());
        List<BlockPos> targets = List.of(first.target, first.target.east(), first.target.east(2));
        second.worker.body().setPos(first.target.getX() + 1.5, first.target.getY(), first.target.getZ() + .5);
        int[] pulses = {0};
        SuppressionMethods.register(new SuppressionMethod() {
            public double range() {
                return 4;
            }

            public boolean available(Worker who) {
                return who.body() == first.worker.body();
            }

            public Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target) {
                if (!available(who)) {
                    return Optional.empty();
                }
                return Optional.of(new SuppressionSession() {
                    public Outcome suppress(ServerLevel world, BlockPos at) {
                        pulses[0]++;
                        targets.forEach(cell -> world.removeBlock(cell, false));
                        return Outcome.done();
                    }

                    public void close() {
                    }
                });
            }
        });
        try {
            for (BlockPos at : targets) {
                first.level.setBlock(at.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
                first.level.setBlock(at, Blocks.FIRE.defaultBlockState(), 2);
            }
            var area = ColonyFront.of(colony).addZone(first.level, DamageControlContent.ID,
                Set.copyOf(targets), ColonySettings.empty()).orElseThrow();
            DamageControlPresence presence = new DamageControlPresence(colony);
            presence.tick(first.level.getServer());
            Node aimed = response(presence, colony, first);
            helper.assertTrue(aimed.ready(first.level), "first worker owns the aimed cell");
            Node collateral = response(presence, colony, second);
            helper.assertTrue(collateral.ready(first.level), "second worker owns another cell");
            helper.assertFalse(((WorkSite.AtBlock) aimed.spec().site()).pos()
                .equals(((WorkSite.AtBlock) collateral.spec().site()).pos()), "the two workers never double claim");
            helper.assertTrue(aimed.commit(first.level, first.worker) instanceof Outcome.Done,
                "one area pulse commits");
            var state = presence.state(colony.view(first.level));
            helper.assertTrue(state.marks().stream().noneMatch(mark -> targets.contains(mark.at())),
                "assigned and queued fire markers retire immediately after the pulse");
            helper.assertTrue(state.marks().stream().noneMatch(mark -> mark.color() == 0x70D6FF),
                "all affected target claims are released");
            helper.assertTrue(collateral.ready(first.level), "collateral completion is harmless to the runner");
            helper.assertTrue(collateral.commit(first.level, second.worker) instanceof Outcome.Done,
                "an already extinguished assignment completes without a refusal or another spray");
            helper.assertValueEqual(pulses[0], 1, "collateral completion never spends a second pulse");
            helper.assertTrue(presence.goals(colony.view(first.level)).isEmpty(),
                "no stale work is retained");
            first.level.setBlock(first.target, Blocks.FIRE.defaultBlockState(), 2);
            presence.survey(List.of(colony.view(first.level)), 10);
            helper.assertTrue(presence.state(colony.view(first.level)).marks().stream()
                .anyMatch(mark -> mark.at().equals(first.target)), "a later reignition is surveyed as fresh work");
            collateral.ended(first.level, Ending.DONE);
            helper.assertTrue(presence.zoneLines(area, colony.view(first.level)).size() > 0,
                "the area survey remains readable after late cleanup");
        } finally {
            colony.raze();
            first.close();
            second.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPerformedSpraySettlesThenSmothersItsTargetAndUnblocksRepairs(
        GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        BlockPos collateral = rig.target.east();
        int[] opened = {0};
        SuppressionMethod planned = new SuppressionMethod() {
            public double range() {
                return 4;
            }

            public boolean available(Worker who) {
                return who.body() == rig.worker.body();
            }

            public Set<BlockPos> footings(ServerLevel level, Worker who, WorldPos target) {
                return available(who) ? Set.of(rig.target.west()) : Set.of();
            }

            public Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target) {
                opened[0]++;
                return Optional.of(new SuppressionSession() {
                    public Outcome suppress(ServerLevel world, BlockPos at) {
                        world.removeBlock(collateral, false);
                        return Outcome.failed(DamageControlRefusal.NO_REACH);
                    }

                    public boolean performed() {
                        return true;
                    }

                    public void close() {
                    }
                });
            }
        };
        SuppressionMethods.register(planned);
        SuppressionMethods.register(new SuppressionMethod() {
            public double range() {
                return 4;
            }

            public boolean available(Worker who) {
                return who.body() == rig.worker.body();
            }

            public Set<BlockPos> footings(ServerLevel level, Worker who, WorldPos target) {
                return Set.of();
            }

            public Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target) {
                throw new IllegalStateException("an available tool without the planned footing must not take over");
            }
        });
        for (BlockPos at : List.of(rig.target, collateral)) {
            rig.level.setBlock(at.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            rig.level.setBlock(at, Blocks.FIRE.defaultBlockState(), 2);
        }
        BlockPos missing = rig.target.south();
        rig.level.setBlock(missing, Blocks.STONE.defaultBlockState(), 2);
        var zone = ColonyFront.of(colony).addZone(rig.level, DamageControlContent.ID,
            Set.of(rig.target, collateral, missing), ColonySettings.empty()
                .with("repair", new ColonySettings.Value.Flag(true))).orElseThrow();
        DamageControlPresence presence = new DamageControlPresence(colony);
        presence.act(DamageControlContent.ID, "record:" + zone.id(), Optional.empty(), colony.view(rig.level));
        rig.level.removeBlock(missing, false);
        presence.tick(rig.level.getServer());
        Node response = response(presence, colony, rig);
        helper.assertTrue(response.ready(rig.level), "the planned response owns its incident");
        var work = (Workload.Continuous) response.spec().workload();
        for (int windup = 0; windup <= 10; windup++) {
            helper.assertTrue(work.holds(rig.level, rig.worker), "work reaches the planned stance and fires once");
        }
        helper.assertValueEqual(opened[0], 1, "only the method that planned the stance opens");
        helper.assertTrue(rig.level.getBlockState(rig.target).is(Blocks.FIRE), "settling preserves the target");
        helper.assertTrue(rig.level.getBlockState(collateral).isAir(), "native collateral effect is reconciled");
        helper.assertTrue(repairNodes(presence, colony, rig.level).isEmpty(), "repairs wait while the target burns");
        helper.startSequence().thenExecuteAfter(4, () -> {
            helper.assertTrue(work.holds(rig.level, rig.worker), "four ticks do not finish the settle window");
            helper.assertTrue(rig.level.getBlockState(rig.target).is(Blocks.FIRE), "no early fallback");
        }).thenExecuteAfter(1, () -> {
            try {
                helper.assertFalse(work.holds(rig.level, rig.worker), "five ticks settle the performed spray");
                helper.assertTrue(response.commit(rig.level, rig.worker) instanceof Outcome.Done,
                    "a surviving sprayed target counts as done after direct smother");
                helper.assertTrue(rig.level.getBlockState(rig.target).isAir(), "fallback removes its target");
                helper.assertValueEqual(opened[0], 1, "settling never repeats the spray");
                presence.survey(List.of(colony.view(rig.level)), 100);
                presence.refresh(rig.level.getServer());
                helper.assertFalse(repairNodes(presence, colony, rig.level).isEmpty(),
                    "zero fires releases ordinary repair work");
                response.ended(rig.level, Ending.DONE);
            } finally {
                colony.raze();
                rig.close();
            }
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void lateAreaCompletionCannotReleaseAReignitedTargetsNewClaim(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        try {
            rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
            ExtinguishNode old = (ExtinguishNode) rig.fire();
            helper.assertTrue(old.ready(rig.level), "the original response owns its incident");
            rig.level.removeBlock(rig.target, false);
            old.resolved(rig.level);
            rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
            Node next = rig.fire();
            helper.assertTrue(next.ready(rig.level), "reignition gets a fresh response claim");
            old.ended(rig.level, Ending.DONE);
            helper.assertValueEqual(rig.claims.workers(), 1, "late cleanup cannot release the new response's claim");
            helper.assertFalse(rig.claims.claim(rig.at(), UUID.randomUUID(), 4),
                "another resident still cannot double claim the reignited target");
            next.ended(rig.level, Ending.DONE);
            helper.assertValueEqual(rig.claims.workers(), 0, "the current response can release its own claim");
        } finally {
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void incrementalSurveyHonoursItsBudgetAndFindsReignition(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int x = 0; x < 8; x++) {
            cells.add(rig.target.offset(x, 0, 0));
        }
        BlockPos fire = rig.target.offset(7, 0, 0);
        rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
        FireSurvey survey = new FireSurvey();
        List<ZoneView> zones = List.of(zone(rig.level, cells));
        int[] read = {0};
        helper.assertValueEqual(survey.scan(rig.level, zones, 2, (zone, at, state) -> read[0]++), 2,
            "budget bounds reads");
        helper.assertValueEqual(read[0], 2, "restoration shares the same inspection budget");
        helper.assertTrue(survey.fires().isEmpty(), "scan does not jump to the last cell");
        survey.scan(rig.level, zones, 6, (zone, at, state) -> {
        });
        helper.assertValueEqual(survey.fires().size(), 1, "next slices find the fire");
        rig.level.removeBlock(fire, false);
        survey.scan(rig.level, zones, 8, (zone, at, state) -> {
        });
        helper.assertTrue(survey.fires().isEmpty(), "periodic scan removes vanished fires");
        rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
        survey.scan(rig.level, zones, 8, (zone, at, state) -> {
        });
        helper.assertValueEqual(survey.fires().size(), 1, "periodic scan finds reignition");
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
        helper.assertTrue(rig.level.getBlockState(rig.target) == Blocks.STONE.defaultBlockState(),
            "exact target state is placed");
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
        helper.assertTrue(Commit.run(rig.level, repairs.getFirst(), rig.worker) instanceof Outcome.Done,
            "the build path places the door");
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
            helper.assertTrue(colony.kept(DamageControlContent.ID).isEmpty(),
                "old colonies have no automatic baseline");
            rig.level.setBlock(rig.target, Blocks.OAK_PLANKS.defaultBlockState(), 2);
            helper.assertFalse(presence.act(DamageControlContent.ID, "record:" + zone.id(), Optional.empty(),
                colony.view(rig.level)).refused(),
                "player action records the baseline");
            CompoundTag first = colony.kept(DamageControlContent.ID).copy();
            helper.assertValueEqual(first.getList("baselines", Tag.TAG_COMPOUND).size(), 1,
                "baseline is kept with colony");
            presence = new DamageControlPresence(colony);
            rig.level.setBlock(rig.target, Blocks.STONE.defaultBlockState(), 2);
            presence.act(DamageControlContent.ID, "record:" + zone.id(), Optional.empty(), colony.view(rig.level));
            CompoundTag saved = colony.kept(DamageControlContent.ID).getList("baselines", Tag.TAG_COMPOUND)
                .getCompound(0);
            helper.assertValueEqual(saved.getLong("revision"), 2L, "re-recording after reload increments revision");
            helper.assertTrue(StructureSnapshots.load(saved.getCompound("snapshot")).orElseThrow().cells()
                .containsValue(Blocks.STONE.defaultBlockState()),
                "new baseline replaces the old one");
        } finally {
            colony.raze();
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLicensedResidentGetsOrdinaryFireWorkWithoutPreparation(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            ZoneDrafts.zone(rig.level, colony, DamageControlContent.ID, rig.target.west().north(),
                rig.target.east().south().above(), ColonySettings.empty()).orElseThrow();
            DamageControlPresence presence = new DamageControlPresence(colony);
            presence.tick(rig.level.getServer());
            helper.assertTrue(presence.goals(colony.view(rig.level)).isEmpty(), "an idle zone offers no work");
            rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
            presence.tick(rig.level.getServer());
            Node node = response(presence, colony, rig);
            helper.assertTrue(presence.goals(colony.view(rig.level)).stream()
                .anyMatch(grown -> grown.nodes().contains(node)), "fire is ordinary grown work");
            helper.assertValueEqual(node.spec().vocation().orElseThrow(), DamageControlContent.trade(),
                "the normal planner applies the resident's trade priority and licence");
            helper.assertTrue(Commit.run(rig.level, node, rig.worker) instanceof Outcome.Done,
                "the resident suppresses fire immediately without preparation");
            node.ended(rig.level, Ending.DONE);
        } finally {
            colony.raze();
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void activeSuppressionSurvivesCandidateCooldownAndReleasesItsClaim(GameTestHelper helper)
        throws ReflectiveOperationException {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            ZoneDrafts.zone(rig.level, colony, DamageControlContent.ID, rig.target.west().north(),
                rig.target.east().south().above(), ColonySettings.empty()).orElseThrow();
            DamageControlPresence presence = new DamageControlPresence(colony);
            presence.tick(rig.level.getServer());
            rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
            presence.tick(rig.level.getServer());
            Node response = response(presence, colony, rig);
            helper.assertTrue(response.ready(rig.level), "the selected response owns a real incident claim");
            for (int poll = 0; poll < 25; poll++) {
                var offered = presence.plan(rig.worker, colony.view(rig.level));
                helper.assertTrue(offered.isPresent() && offered.get() == response,
                    "candidate throttling preserves the same planned node");
            }
            rig.level.removeBlock(rig.target, false);
            helper.assertTrue(presence.plan(rig.worker, colony.view(rig.level)).isEmpty(),
                "revocation withdraws the old node before replacement");
            response.released(rig.level, rig.worker, Ending.DROPPED);
            helper.assertTrue(presence.plan(rig.worker, colony.view(rig.level)).isEmpty(),
                "interruption clears retention and preserves the candidate cooldown");
            var field = DamageControlPresence.class.getDeclaredField("incidents");
            field.setAccessible(true);
            var claims = (Incidents) field.get(presence);
            helper.assertValueEqual(claims.workers(), 0, "interruption releases the actual shared incident");
            helper.assertTrue(claims.claim(rig.at(), UUID.randomUUID(), 1),
                "another responder can claim the released fire");
        } finally {
            colony.raze();
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aRecordedZonePublishesRepairWorkAndReportsPlayerRebuilding(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            var zone = ZoneDrafts.zone(rig.level, colony, DamageControlContent.ID, rig.target,
                rig.target.above(), ColonySettings.empty().with("repair", new ColonySettings.Value.Flag(true)))
                .orElseThrow();
            rig.level.setBlock(rig.target, Blocks.STONE.defaultBlockState(), 2);
            DamageControlPresence presence = new DamageControlPresence(colony);
            presence.act(DamageControlContent.ID, "record:" + zone.id(), Optional.empty(), colony.view(rig.level));
            rig.level.removeBlock(rig.target, false);
            presence.tick(rig.level.getServer());
            Node repair = presence.goals(colony.view(rig.level)).stream().flatMap(work -> work.nodes().stream())
                .filter(node -> node.spec()
                    .doing().equals(DamageControlContent.REPAIRING))
                .findFirst()
                .orElseThrow();
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
        } finally {
            colony.raze();
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void baselineDoesNotRestoreADoorBeyondTheMarkedZone(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState();
        rig.level.setBlock(rig.target, lower, 2);
        rig.level.setBlock(rig.target.above(),
            lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 2);
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
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        Node repair = rig.repair(Map.of(rig.at(), slab)).getFirst();
        var nativeCost = SchematicPlacement.adapter().requirement(slab, null).needs().orElseThrow();
        helper.assertValueEqual(repair.spec().needs(), nativeCost,
            "repair carries the same ItemRequirement as the cannon");
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
        helper.assertTrue(repairs.isEmpty(),
            "a sparse lower half cannot fire a shot that also writes an unrequested cell");
        helper.assertTrue(rig.level.getBlockState(rig.target.above()).is(Blocks.GOLD_BLOCK),
            "the neighbouring player block is preserved");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partialDoorsCannotStarveLaterRepairCells(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            BlockPos origin = rig.target.above(128);
            Map<BlockPos, BlockState> recorded = new LinkedHashMap<>();
            Set<BlockPos> cells = new LinkedHashSet<>();
            BlockState lower = Blocks.OAK_DOOR.defaultBlockState();
            BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
            for (int i = 0; i < 128; i++) {
                BlockPos door = origin.offset(i % 16, 0, i / 16);
                rig.level.setBlock(door.below(), Blocks.STONE.defaultBlockState(), 2);
                rig.level.setBlock(door, lower, 2);
                cells.add(door.above());
                recorded.put(door.above().subtract(origin), upper);
            }
            BlockPos stone = origin.east(18);
            for (BlockPos at : BlockPos.betweenClosed(stone.offset(-2, -1, -2), stone.offset(2, -1, 2))) {
                rig.level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
            }
            cells.add(stone);
            recorded.put(stone.subtract(origin), Blocks.STONE.defaultBlockState());
            for (int i = 0; i < 128; i++) {
                BlockPos door = origin.offset(i % 16, 0, i / 16);
                cells.add(door);
                recorded.put(door.subtract(origin), lower);
            }
            var zone = ColonyFront.of(colony).addZone(rig.level, DamageControlContent.ID, cells,
                ColonySettings.empty().with("repair", new ColonySettings.Value.Flag(true))).orElseThrow();
            var snapshot = new StructureSnapshots.Snapshot(WorldPos.of(rig.level, origin), recorded, 0);
            colony.keep(DamageControlContent.ID, Writer.of().integer("schemaVersion", 1)
                .children("baselines", List.of(zone.id()), id -> Writer.of().uuid("zone", id)
                    .longValue("revision", 1)
                    .blob("snapshot", snapshot.save())
                    .tag())
                .tag());
            DamageControlPresence presence = new DamageControlPresence(colony);
            for (int round = 0; round < 3; round++) {
                presence.survey(List.of(colony.view(rig.level)), 512);
                presence.refresh(rig.level.getServer());
            }
            Node repair = repairNodes(presence, colony, rig.level).stream()
                .filter(node -> ((WorkSite.AtBlock) node.spec().site()).pos().equals(WorldPos.of(rig.level, stone)))
                .findFirst()
                .orElseThrow();
            rig.worker.body().setPos(stone.getX() - .5, stone.getY(), stone.getZ() + .5);
            rig.store.setItem(0, new ItemStack(Items.STONE));
            helper.assertTrue(Commit.run(rig.level, repair, rig.worker) instanceof Outcome.Done,
                "the 129th missing cell is repaired after rotating past 128 partial doors");
            helper.assertTrue(rig.level.getBlockState(stone).is(Blocks.STONE), "the later stone cell is restored");
            helper.assertValueEqual(rig.store.countItem(Items.STONE), 0, "the normal build path consumes its material");
        } finally {
            colony.raze();
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disablingAnOverlappingRepairOwnerLetsTheOtherZoneTakeOver(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            var front = ColonyFront.of(colony);
            var chosen = ColonySettings.empty().with("repair", new ColonySettings.Value.Flag(true));
            var first = front.addZone(rig.level, DamageControlContent.ID, Set.of(rig.target), chosen).orElseThrow();
            var second = front.addZone(rig.level, DamageControlContent.ID, Set.of(rig.target), chosen).orElseThrow();
            rig.level.setBlock(rig.target, Blocks.STONE.defaultBlockState(), 2);
            DamageControlPresence presence = new DamageControlPresence(colony);
            for (var zone : List.of(first, second)) {
                presence.act(DamageControlContent.ID, "record:" + zone.id(), Optional.empty(), colony.view(rig.level));
            }
            rig.level.removeBlock(rig.target, false);
            presence.survey(List.of(colony.view(rig.level)), 512);
            presence.refresh(rig.level.getServer());
            Node old = repairNodes(presence, colony, rig.level).getFirst();
            front.setDrawn(first.id(), first.settings().with("repair", new ColonySettings.Value.Flag(false)));
            presence.survey(List.of(colony.view(rig.level)), 512);
            helper.assertFalse(old.ready(rig.level), "the disabled owner's old work cannot run");
            presence.refresh(rig.level.getServer());
            List<Node> replacement = repairNodes(presence, colony, rig.level);
            helper.assertValueEqual(replacement.size(), 1, "the enabled overlap still publishes exactly one repair");
            helper.assertFalse(replacement.getFirst().id().equals(old.id()),
                "ownership changes rather than retaining the disabled task");
            rig.store.setItem(0, new ItemStack(Items.STONE));
            helper.assertTrue(Commit.run(rig.level, replacement.getFirst(), rig.worker) instanceof Outcome.Done,
                "the second zone's replacement work can restore the cell");
        } finally {
            colony.raze();
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anEnclosedNearestFireDoesNotHideAnAccessibleFire(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        try {
            for (BlockPos cell : BlockPos.betweenClosed(rig.target.offset(-2, -1, -2), rig.target.offset(2, 3, 2))) {
                if (!cell.equals(rig.target)) {
                    rig.level.setBlock(cell, Blocks.STONE.defaultBlockState(), 2);
                }
            }
            BlockPos farther = rig.target.east(7);
            rig.level.setBlock(rig.target.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
            rig.level.setBlock(farther.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            rig.level.setBlock(farther, Blocks.FIRE.defaultBlockState(), 2);
            ColonyFront.of(colony).addZone(rig.level, DamageControlContent.ID,
                Set.of(rig.target, farther, rig.target.west(3)), ColonySettings.empty()).orElseThrow();
            DamageControlPresence presence = new DamageControlPresence(colony);
            presence.tick(rig.level.getServer());
            Node fire = response(presence, colony, rig);
            helper.assertValueEqual(((WorkSite.AtBlock) fire.spec().site()).pos(), WorldPos.of(rig.level, farther),
                "the next accessible fire is selected");
            rig.worker.body().setPos(farther.getX() - .5, farther.getY(), farther.getZ() + .5);
            helper.assertTrue(Commit.run(rig.level, fire, rig.worker) instanceof Outcome.Done,
                "the second fire is actually extinguished");
            fire.ended(rig.level, Ending.DONE);
            helper.assertTrue(rig.level.getBlockState(rig.target).is(Blocks.FIRE),
                "the enclosed fire is left for a later retry");
        } finally {
            colony.raze();
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aOneCellBudgetRotatesBetweenDimensions(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        ServerLevel other = rig.level.getServer().getLevel(Level.NETHER);
        BlockPos at = rig.target.above(64);
        other.getChunkAt(at);
        try {
            var front = ColonyFront.of(colony);
            var chosen = ColonySettings.empty().with("repair", new ColonySettings.Value.Flag(true));
            var first = front.addZone(rig.level, DamageControlContent.ID, Set.of(at, at.east()), chosen).orElseThrow();
            var second = front.addZone(other, DamageControlContent.ID, Set.of(at, at.east()), chosen).orElseThrow();
            DamageControlPresence presence = new DamageControlPresence(colony);
            for (ServerLevel level : List.of(rig.level, other)) {
                level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(at.east(), Blocks.AIR.defaultBlockState(), 2);
                var zone = level == rig.level ? first : second;
                presence.act(DamageControlContent.ID, "record:" + zone.id(), Optional.empty(), colony.view(level));
                level.removeBlock(at, false);
                level.setBlock(at.east().below(), Blocks.NETHERRACK.defaultBlockState(), 2);
                level.setBlock(at.east(), Blocks.FIRE.defaultBlockState(), 2);
            }
            for (int tick = 0; tick < 4; tick++) {
                presence.survey(List.of(colony.view(rig.level), colony.view(other)), 1);
            }
            for (var zone : List.of(first, second)) {
                ServerLevel level = zone.dimension().equals(rig.level.dimension()) ? rig.level : other;
                var line = (Line.Text) presence.zoneLines(zone, colony.view(level)).getFirst();
                var status = (TranslatableContents) line.component().getContents();
                helper.assertValueEqual(((Number) status.getArgs()[0]).intValue(), 1,
                    "each dimension discovers its fire");
                helper.assertValueEqual(((Number) status.getArgs()[1]).intValue(), 1,
                    "each dimension discovers its missing block");
            }
        } finally {
            other.removeBlock(at, false);
            other.removeBlock(at.east(), false);
            other.removeBlock(at.east().below(), false);
            colony.raze();
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2600)
    public static void aLicensedResidentStillRespondsAfterLongIdle(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Colony colony = Colonies.mint(rig.level.getServer());
        colony.hold(PersonContent.ID, Held.Entity.of(rig.worker.body())).orElseThrow();
        ColonyFront.of(colony).addZone(rig.level, DamageControlContent.ID,
            Set.of(rig.target), ColonySettings.empty()).orElseThrow();
        DamageControlPresence presence = new DamageControlPresence(colony);
        helper.onEachTick(() -> presence.tick(rig.level.getServer()));
        helper.startSequence().thenExecuteAfter(2405, () -> {
            rig.level.setBlock(rig.target.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
        }).thenExecuteAfter(2, () -> {
            try {
                Node node = presence.goals(colony.view(rig.level)).getFirst().nodes().getFirst();
                helper.assertTrue(Commit.run(rig.level, node, rig.worker) instanceof Outcome.Done,
                    "long idle cannot disable a licensed response");
                helper.assertTrue(rig.level.getBlockState(rig.target).isAir(), "the late fire is removed");
                node.ended(rig.level, Ending.DONE);
            } finally {
                colony.raze();
                rig.close();
            }
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void unreachableOrUnopenedPlansCannotAuthorizeFallback(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
        int[] opened = {0};
        var unreachable = new SuppressionMethod.Plan(Stances.at(rig.level, rig.target.east(7)), Set.of(), () -> {
            opened[0]++;
            throw new IllegalStateException("an unreachable stance cannot open or spray");
        });
        Node node = new ExtinguishNode(rig.at(), rig.worker.resident().id(), unreachable, rig.claims,
            () -> true, ended -> { }, level -> { });
        helper.assertFalse(((Workload.Continuous) node.spec().workload()).holds(rig.level, rig.worker),
            "work fails at an unreachable stance");
        helper.assertTrue(node.commit(rig.level, rig.worker) instanceof Outcome.Failed,
            "unreachable work remains a failure");
        helper.assertValueEqual(opened[0], 0, "no emission is possible before arrival");
        node.ended(rig.level, Ending.DROPPED);
        var unopened = new SuppressionMethod.Plan(Stances.at(rig.level, rig.target.west()), Set.of(),
            Optional::empty, () -> DamageControlRefusal.NO_WATER);
        Node dry = new ExtinguishNode(rig.at(), rig.worker.resident().id(), unopened, rig.claims,
            () -> true, ended -> { }, level -> { });
        helper.assertTrue(dry.commit(rig.level, rig.worker) instanceof Outcome.Failed failed
            && failed.why() == DamageControlRefusal.NO_WATER, "failed opening retains the specific refusal");
        helper.startSequence().thenExecuteAfter(6, () -> {
            try {
                helper.assertTrue(rig.level.getBlockState(rig.target).is(Blocks.FIRE),
                    "neither failed plan schedules a fallback");
                dry.ended(rig.level, Ending.DROPPED);
            } finally {
                rig.close();
            }
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void damageControlDefaultsToTheHighestTradePriority(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        try {
            var licences = Bodies.of(rig.worker.body()).orElseThrow().licences();
            var known = licences.trade(DamageControlContent.trade()).orElseThrow();
            helper.assertValueEqual(licences.keennessOf(known), Keenness.FIRST,
                "damage control uses the highest normal trade priority by default");
        } finally {
            rig.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aHundredRespondersShareBoundedPlanningWithoutPendingStarvation(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        List<Rig> responders = new ArrayList<>();
        Map<UUID, Node> heard = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            Rig responder = new Rig(helper);
            colony.hold(PersonContent.ID, Held.Entity.of(responder.worker.body())).orElseThrow();
            responders.add(responder);
        }
        Rig rig = responders.getFirst();
        ColonyFront.of(colony).addZone(rig.level, DamageControlContent.ID,
            Set.of(rig.target), ColonySettings.empty()).orElseThrow();
        rig.level.setBlock(rig.target.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
        rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
        int[] searches = {0};
        Set<UUID> workers = new LinkedHashSet<>();
        responders.forEach(responder -> workers.add(responder.worker.resident().id()));
        SuppressionMethods.register(new SuppressionMethod() {
            public double range() {
                return 2;
            }

            public Set<BlockPos> footings(ServerLevel level, Worker who, WorldPos target) {
                if (!workers.contains(who.resident().id())) {
                    return Set.of();
                }
                searches[0]++;
                return Set.of(rig.target.west());
            }

            public Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target) {
                return new VanillaSmother().open(level, who, target);
            }
        });
        DamageControlPresence presence = new DamageControlPresence(colony);
        presence.tick(rig.level.getServer());
        helper.onEachTick(() -> {
            searches[0] = 0;
            for (Rig responder : responders) {
                presence.plan(responder.worker, colony.view(rig.level))
                    .ifPresent(node -> heard.put(responder.worker.resident().id(), node));
            }
            helper.assertTrue(searches[0] <= FolkwaysVanillaConfig.damageControlConcurrency(),
                "all workers share the configured footing-search allowance each tick");
        });
        helper.startSequence().thenExecuteAfter((100 + FolkwaysVanillaConfig.damageControlConcurrency() - 1)
            / FolkwaysVanillaConfig.damageControlConcurrency() + 5, () -> {
                try {
                    helper.assertValueEqual(heard.size(), 100, "fixed resident order does not starve later workers");
                    Node last = heard.get(responders.getLast().worker.resident().id());
                    helper.assertTrue(last.ready(rig.level),
                        "pending plans for occupied workers do not reserve the fire from a later idle worker");
                    helper.assertFalse(heard.get(rig.worker.resident().id()).ready(rig.level),
                        "once claimed the incident remains exclusive");
                    presence.closed(null);
                } finally {
                    colony.raze();
                    responders.forEach(Rig::close);
                }
            }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void cancellationOrProtectionLossDuringSettlingCannotSmother(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.level.setBlock(rig.target, Blocks.FIRE.defaultBlockState(), 2);
        boolean[] enabled = {true};
        var plan = new SuppressionMethod.Plan(Stances.at(rig.level, rig.target.west()), Set.of(),
            () -> Optional.of(new SuppressionSession() {
                public Outcome suppress(ServerLevel level, BlockPos target) {
                    return Outcome.failed(DamageControlRefusal.NO_REACH);
                }

                public boolean performed() {
                    return true;
                }

                public void close() {
                }
            }));
        Node canceled = new ExtinguishNode(rig.at(), rig.worker.resident().id(), plan, rig.claims,
            () -> true, ended -> { }, level -> { });
        Node disabled = new ExtinguishNode(rig.at(), rig.worker.resident().id(), plan, rig.claims,
            () -> enabled[0], ended -> { }, level -> { });
        for (Node node : List.of(canceled, disabled)) {
            var work = (Workload.Continuous) node.spec().workload();
            for (int windup = 0; windup <= 10; windup++) {
                helper.assertTrue(work.holds(rig.level, rig.worker), "the planned pulse enters settling");
            }
        }
        canceled.released(rig.level, rig.worker, Ending.DROPPED);
        enabled[0] = false;
        helper.startSequence().thenExecuteAfter(6, () -> {
            try {
                for (Node node : List.of(canceled, disabled)) {
                    helper.assertFalse(((Workload.Continuous) node.spec().workload()).holds(rig.level, rig.worker),
                        "lost authority terminates settling");
                    helper.assertTrue(node.commit(rig.level, rig.worker) instanceof Outcome.Failed,
                        "lost authority cannot complete a fallback");
                    node.ended(rig.level, Ending.DROPPED);
                }
                helper.assertTrue(rig.level.getBlockState(rig.target).is(Blocks.FIRE),
                    "cancellation and protection withdrawal preserve the surviving fire");
            } finally {
                rig.close();
            }
        }).thenSucceed();
    }

    private static Node response(DamageControlPresence presence, Colony colony, Rig rig) {
        if (Bodies.of(rig.worker.body()).orElseThrow().colonyId().isEmpty()) {
            colony.hold(PersonContent.ID, Held.Entity.of(rig.worker.body())).orElseThrow();
        }
        return presence.plan(rig.worker, colony.view(rig.level)).orElseThrow();
    }

    private static List<Node> repairNodes(DamageControlPresence presence, Colony colony, ServerLevel level) {
        return presence.goals(colony.view(level)).stream().flatMap(work -> work.nodes().stream())
            .filter(node -> node.spec()
                .doing().equals(DamageControlContent.REPAIRING))
            .toList();
    }

    private static ZoneView zone(ServerLevel level, Set<BlockPos> cells) {
        UUID id = UUID.randomUUID();
        Settings settings = Proxies.of(Settings.class, (proxy, method, args) -> switch (method.getName()) {
            case "flag" -> true;
            default -> null;
        });
        return Proxies.of(ZoneView.class, (proxy, method, args) -> switch (method.getName()) {
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
            worker = TestWorker.of(body).within(new Worker.Store(WorldPos.of(level, target.west(2)), store))
                .effects(Effects.NONE).spill(stack -> { });
        }

        WorldPos at() {
            return WorldPos.of(level, target);
        }

        Node fire() {
            var plan = new SuppressionMethod.Plan(Stances.at(level, target.west()), Set.of(),
                () -> new VanillaSmother().open(level, worker, at()));
            return new ExtinguishNode(at(), worker.resident().id(), plan, claims,
                () -> true, node -> { }, world -> { });
        }

        List<Node> repair(Map<WorldPos, BlockState> target) {
            return MaintenanceOrders.missing(level, target, DamageControlContent.ID, DamageControlContent.trade(),
                DamageControlContent.REPAIRING, () -> true, at -> true, DamageControlRefusal.CONFLICT,
                (node, how) -> {
                });
        }

        void close() {
            worker.body().discard();
        }
    }
}
