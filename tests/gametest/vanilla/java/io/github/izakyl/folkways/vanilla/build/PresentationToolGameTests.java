package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.ToolPolicy;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.Tools;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class PresentationToolGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PresentationToolGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void optionalDiggingToolsMatchTheBlockWithoutBlockingBareHands(GameTestHelper helper) {
        preferred(helper, Blocks.DIRT.defaultBlockState(), Items.IRON_SHOVEL);
        preferred(helper, Blocks.SAND.defaultBlockState(), Items.IRON_SHOVEL);
        preferred(helper, Blocks.OAK_LOG.defaultBlockState(), Items.IRON_AXE);
        preferred(helper, Blocks.HAY_BLOCK.defaultBlockState(), Items.IRON_HOE);
        helper.succeed();
    }

    private static void preferred(GameTestHelper helper, BlockState state, Item expected) {
        NodeSpec spec = strip(helper, state);
        helper.assertTrue(spec.tools().size() == 1 && !spec.tools().getFirst().required(),
            "hand-harvestable blocks declare a preferred tool");
        helper.assertTrue(Tools.equipped(PersonBody.ID, spec, List.of()), "empty hands do not block work");
        helper.assertTrue(Tools.canPrepare(PersonBody.ID, spec, List.of(), new ToolPolicy(Map.of())),
            "planning does not require the preferred tool");
        ItemStack tool = new ItemStack(expected);
        ItemStack selected = Tools.from(List.of(new ItemStack(Items.STICK), tool),
            spec.tools().getFirst().need()).orElseThrow();
        helper.assertTrue(selected == tool, "display and held lookup select the matching physical tool");
        helper.assertTrue(!Tools.answers(new ItemStack(Items.IRON_SWORD), new ToolNeed.SuitableFor(state)),
            "an unrelated item is never a digging tool");
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void requiredHarvestToolsStillEnforceTheirTier(GameTestHelper helper) {
        NodeSpec spec = strip(helper, Blocks.DIAMOND_ORE.defaultBlockState());
        helper.assertTrue(spec.tools().getFirst().required(), "ore still requires its harvest tool");
        helper.assertTrue(!Tools.equipped(PersonBody.ID, spec, List.of(new ItemStack(Items.WOODEN_PICKAXE))),
            "a low tier pickaxe does not satisfy ore harvesting");
        helper.assertTrue(Tools.equipped(PersonBody.ID, spec, List.of(new ItemStack(Items.IRON_PICKAXE))),
            "a suitable pickaxe satisfies ore harvesting");
        helper.succeed();
    }

    private static NodeSpec strip(GameTestHelper helper, BlockState state) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        BuildJob job = new BuildJob(BuildJob.Kind.STRIP, at, Stances.WHEREVER,
            Map.of(at, state), Map.of(at, Blocks.AIR.defaultBlockState()));
        return NodeSpec.of(UUID.randomUUID(), BuildContent.ID, new WorkSite.AtBlock(at), Stances.WHEREVER,
            Workload.Once.of(20)).tools(job.tools()).done();
    }
}
