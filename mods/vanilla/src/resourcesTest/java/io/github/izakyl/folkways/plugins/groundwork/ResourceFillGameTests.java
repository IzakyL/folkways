package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.resources.ResourceItems;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = io.github.izakyl.folkways.plugins.FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class ResourceFillGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(ResourceFillGameTests.class); }

    @GameTest(template = "empty")
    public static void stoneResourceFillsWithThePlannedStoneAndPrefersRealBlocks(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(2, 2, 2));
        f.worker.pack().setItem(0, new ItemStack(ResourceItems.STONE.get(), 2));
        f.worker.pack().setItem(1, new ItemStack(Items.ANDESITE));
        for (int index = 0; index < 2; index++) {
            BlockPos target = cell.east(index);
            level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
            Job job = Job.at(target, new WorkSite.AtBlock(WorldPos.of(level, target)), Stances.WHEREVER);
            Chores tending = new Chores(UUID.randomUUID(), "fill", (world, chore) -> new FillNode(chore, ItemSpec.of(Items.ANDESITE)),
                () -> { }, new PastDay());
            Node node = new FillNode(new Chores.Chore(UUID.randomUUID(), job, tending), ItemSpec.of(Items.ANDESITE));
            var done = Commit.run(level, node, f.worker);
            helper.assertTrue(done instanceof Outcome.Done && level.getBlockState(target).is(Blocks.ANDESITE), "fill keeps the chosen andesite");
            helper.assertTrue(((Outcome.Done) done).inputs().getFirst().paid().is(index == 0 ? Items.ANDESITE : ResourceItems.STONE.get()),
                "real andesite is preferred before stone resource");
        }
        helper.assertValueEqual(Goods.countIn(f.worker.pack(), ItemSpec.of(ResourceItems.STONE.get())), 1L, "one real block and one resource spent");
        helper.succeed();
    }
}
