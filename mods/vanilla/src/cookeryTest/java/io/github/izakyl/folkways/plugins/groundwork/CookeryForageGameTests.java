package io.github.izakyl.folkways.plugins.groundwork;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.TeaTreeBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
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
    public static void cookeryPlantsAreBrokenWithFoodAndSeedLoot(GameTestHelper helper) {
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
                Block block = crops.get(index);
                for (int row = 0; row < 2; row++) {
                    BlockPos cell = origin.east(index).south(row);
                    marked.add(cell);
                    level.setBlock(cell.below(), (block == ModBlocks.TEA_TREE.get() ? Blocks.DIRT : Blocks.FARMLAND).defaultBlockState(), 2);
                    BlockState state = block instanceof CropBlock crop ? crop.getStateForAge(row == 0 ? crop.getMaxAge() : 0)
                        : ((TeaTreeBlock) block).getStateForAge(row == 0 ? 5 : 0);
                    level.setBlock(cell, state, 2);
                }
            }
            ColonyFront.of(colony).addZone(level, GroundworkContent.ZONE, marked, ColonySettings.empty()
                .with(GroundworkContent.CLEAR, new ColonySettings.Value.Flag(false))
                .with(GroundworkContent.FORAGE, new ColonySettings.Value.Flag(true))).orElseThrow();
            GroundPresence presence = new GroundPresence(colony);
            presence.refresh(level.getServer());
            List<Node> nodes = presence.goals(colony.view(level)).stream().flatMap(goal -> goal.nodes().stream()).toList();
            helper.assertTrue(nodes.size() == 6, "mature and young Cookery plants are all forage targets");
            List<net.minecraft.world.item.ItemStack> drops = new java.util.ArrayList<>();
            for (Node node : nodes) drops.addAll(((Outcome.Done) node.commit(level, fixture.worker)).made());
            helper.assertTrue(drops.stream().filter(stack -> stack.is(ModItems.TEA_SEED.get())
                || stack.is(ModItems.TOMATO_SEED.get()) || stack.is(ModItems.CHILI_SEED.get()))
                .mapToInt(stack -> stack.getCount()).sum() >= 3, "young crops yield their normal seeds; mature crops may yield more");
            helper.assertTrue(drops.stream().filter(stack -> stack.is(ModItems.FRESH_TEA_LEAVES.get())
                || stack.is(ModItems.TOMATO.get()) || stack.is(ModItems.RED_CHILI.get()))
                .mapToInt(stack -> stack.getCount()).sum() == 3, "all three mature plants yield their normal food or leaves");
            for (BlockPos cell : marked) helper.assertTrue(level.getBlockState(cell).isAir(), "Cookery plants do not regrow in place");
        } finally {
            fixture.worker.body().discard();
            colony.raze();
        }
        helper.succeed();
    }
}
