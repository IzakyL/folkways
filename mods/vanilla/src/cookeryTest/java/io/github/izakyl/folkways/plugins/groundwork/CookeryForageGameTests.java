package io.github.izakyl.folkways.plugins.groundwork;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.TeaTreeBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryForageGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CookeryForageGameTests.class);
    }

    @GameTest(template = "empty")
    public static void cookeryForageUsesTheOwningCropLifecycle(GameTestHelper helper) {
        var level = helper.getLevel();
        var fixture = new PlanningFixture(helper);
        var colony = Colonies.mint(level.getServer());
        BlockPos origin = helper.absolutePos(new BlockPos(3, 1, 3));
        Set<BlockPos> marked = new java.util.HashSet<>();
        try {
            for (BlockPos floor : BlockPos.betweenClosed(origin.offset(-2, -1, -2), origin.offset(4, -1, 2))) {
                level.setBlock(floor, Blocks.DIRT.defaultBlockState(), 2);
                for (int y = 1; y <= 3; y++) level.setBlock(floor.above(y), Blocks.AIR.defaultBlockState(), 2);
            }
            List<Block> crops = List.of(ModBlocks.TEA_TREE.get(), ModBlocks.TOMATO_CROP.get(), ModBlocks.CHILI_CROP.get());
            for (int index = 0; index < crops.size(); index++) {
                BlockPos cell = origin.east(index);
                marked.add(cell);
                Block block = crops.get(index);
                level.setBlock(cell.below(), (block == ModBlocks.TEA_TREE.get() ? Blocks.DIRT : Blocks.FARMLAND).defaultBlockState(), 2);
                BlockState state = block instanceof CropBlock crop ? crop.getStateForAge(crop.getMaxAge())
                    : ((TeaTreeBlock) block).getStateForAge(5);
                level.setBlock(cell, state, 2);
            }
            ColonyFront.of(colony).addZone(level, GroundworkContent.ZONE, marked, ColonySettings.empty()
                .with(GroundworkContent.CLEAR, new ColonySettings.Value.Flag(false))
                .with(GroundworkContent.FORAGE, new ColonySettings.Value.Flag(true))).orElseThrow();
            GroundPresence presence = new GroundPresence(colony);
            presence.refresh(level.getServer());
            List<Node> nodes = presence.goals(colony.view(level)).stream().flatMap(goal -> goal.nodes().stream()).toList();
            helper.assertTrue(nodes.size() == 3, "the owning integration opts all three renewable crops into forage: "
                + nodes.size() + " jobs; " + marked.stream().map(cell -> level.getBlockState(cell) + " tagged="
                    + Foraging.renewable(level.getBlockState(cell)) + " ripe=" + Foraging.ripe(level, cell)).toList());
            for (Node node : nodes) {
                var done = (Outcome.Done) node.commit(level, fixture.worker);
                helper.assertTrue(done.made().stream().mapToInt(stack -> stack.getCount()).sum() == 1,
                    "the native crop behavior yields one fruit or leaf");
            }
            for (BlockPos cell : marked) {
                BlockState state = level.getBlockState(cell);
                int age = state.getValue(state.getBlock() instanceof CropBlock ? CropBlock.AGE : TeaTreeBlock.AGE);
                helper.assertTrue(age == (state.is(ModBlocks.TEA_TREE.get()) ? 3 : 5), "each plant keeps its native regrowth age");
            }
        } finally {
            fixture.worker.body().discard();
            colony.raze();
        }
        helper.succeed();
    }
}
