package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.PointSites;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ColonyMemberWritesGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "test/changed_site");

    static {
        PointSites.register(OWNER, (level, cell) -> level.getBlockState(cell).is(Blocks.COMPOSTER));
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ColonyMemberWritesGameTests.class);
    }

    @GameTest(template = "empty")
    public static void demolishedBlocksAndPointsLeaveOnTheNextTickWithoutLosingNeighboursOrAreas(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var block = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        var point = WorldPos.of(level, helper.absolutePos(new BlockPos(3, 1, 1)));
        var neighbourBlock = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 3)));
        var neighbourPoint = WorldPos.of(level, helper.absolutePos(new BlockPos(3, 1, 3)));
        level.setBlockAndUpdate(block.cell(), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(point.cell(), Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(neighbourBlock.cell(), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(neighbourPoint.cell(), Blocks.COMPOSTER.defaultBlockState());
        var blockHolding = colony.hold(OWNER, new Held.Block(block)).orElseThrow();
        var pointHolding = colony.hold(OWNER, new Held.Point(point)).orElseThrow();
        var neighbourBlockHolding = colony.hold(OWNER, new Held.Block(neighbourBlock)).orElseThrow();
        var neighbourPointHolding = colony.hold(OWNER, new Held.Point(neighbourPoint)).orElseThrow();
        var area = colony.hold(OWNER, new Held.Area(Set.of(block, point))).orElseThrow();
        AtomicInteger released = new AtomicInteger();
        ColonyData.find(level.getServer(), colony.id()).orElseThrow().holdings().setReleasedListener((holding, why) -> {
            helper.assertTrue(why == Release.GONE, "world changes report the gone lifecycle reason");
            released.incrementAndGet();
        });

        level.setBlockAndUpdate(block.cell(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(point.cell(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(released.get() == 0 && colony.holding(blockHolding.id()).isPresent()
            && colony.holding(pointHolding.id()).isPresent(), "block setters defer removal and callbacks until the tick boundary");
        level.setBlockAndUpdate(neighbourBlock.cell(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(neighbourPoint.cell(), Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 5));

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(colony.holdingAt(block).isEmpty() && colony.holdingAt(point).isEmpty(),
                "the normal server tick clears both marked blocks and native points without a heartbeat or explicit reconcile");
            helper.assertTrue(released.get() == 2, "each demolished site is released once");
            helper.assertTrue(colony.holding(neighbourBlockHolding.id()).isPresent()
                && colony.holding(neighbourPointHolding.id()).isPresent(), "valid block state changes retain both kinds of site");
            helper.assertTrue(colony.holding(area.id()).isPresent(), "demolition does not erase a player's drawn area");
            level.setBlockAndUpdate(block.cell(), Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(point.cell(), Blocks.COMPOSTER.defaultBlockState());
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(colony.holdingAt(block).isEmpty() && colony.holdingAt(point).isEmpty(),
                        "placing fresh blocks after demolition does not revive either old registration");
                    helper.assertTrue(ColonyMembership.owner(level.getBlockEntity(block.cell())).isEmpty(),
                        "the replacement block entity has no inherited colony mark");
                    helper.succeed();
                } finally {
                    colony.raze();
                }
            });
        });
    }

    @GameTest(template = "empty")
    public static void changedUnloadedSitesRemainHeldWithoutLoadingTheirChunks(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var holdings = ColonyData.find(level.getServer(), colony.id()).orElseThrow().holdings();
        var block = WorldPos.of(level, new BlockPos(25_000_000, 80, 25_000_000));
        var point = WorldPos.of(level, new BlockPos(25_000_002, 80, 25_000_000));
        holdings.hold(null, OWNER, new Held.Block(block)).orElseThrow();
        holdings.hold(null, OWNER, new Held.Point(point)).orElseThrow();
        ColonyMemberWrites.blockChanged(level, block.cell());
        ColonyMemberWrites.blockChanged(level, point.cell());
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(holdings.at(block).isPresent() && holdings.at(point).isPresent(),
                    "an unloaded site is not evidence of demolition");
                helper.assertTrue(!level.isLoaded(block.cell()) && !level.isLoaded(point.cell()),
                    "dirty-site validation never loads absent chunks");
                helper.succeed();
            } finally {
                colony.raze();
            }
        });
    }

    @GameTest(template = "empty")
    public static void largeEditsFallBackToAReconcileInsteadOfLosingPendingRemovals(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var cell = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        level.setBlockAndUpdate(cell.cell(), Blocks.CHEST.defaultBlockState());
        colony.hold(OWNER, new Held.Block(cell)).orElseThrow();
        ColonyMembership.leave(level.getBlockEntity(cell.cell()));
        for (int x = 0; x < 5000; x++) {
            ColonyMemberWrites.blockChanged(level, new BlockPos(24_000_000 + x, 80, 24_000_000));
        }
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(colony.holdingAt(cell).isEmpty(),
                    "bounded dirty tracking still reconciles invalid holdings after a large edit");
                helper.assertTrue(!level.isLoaded(new BlockPos(24_000_000, 80, 24_000_000)),
                    "overflow does not inspect or load every changed cell");
                helper.succeed();
            } finally {
                colony.raze();
            }
        });
    }
}
