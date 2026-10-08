package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.living.LivingContent;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FellingExertionGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FellingExertionGameTests.class);
    }


    @GameTest(template = "empty")
    public static void worldChangesDoNotReportLaborAgain(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        Body body = PersonBody.RESIDENT.get().create(level);
        body.joinColony(colony.id());
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlockPos> logs = List.of(base, base.above(), base.above(2));
        List<BlockPos> leaves = List.of(base.above(3), base.above(3).east());
        try {
            logs.forEach(at -> level.setBlock(at, Blocks.OAK_LOG.defaultBlockState(), 2));
            leaves.forEach(at -> level.setBlock(at, Blocks.OAK_LEAVES.defaultBlockState(), 2));
            Crop crop = Crop.of(level, base, ResourceLocation.withDefaultNamespace("oak_sapling")).orElseThrow();
            Job job = new Job(WorldPos.of(level, base), logs, leaves, Stances.WHEREVER, base);
            Batch batch = new Batch(WorkSite.at(level, base), Stances.WHEREVER, crop, List.of(job));
            FellNode node = new FellNode(new FarmNode.Chore(UUID.randomUUID(), batch, job, null));
            Worker worker = worker(body);
            node.work(level, worker, job);
            helper.assertTrue(colony.kept(LivingContent.ID).getList("hunger", 10).isEmpty(),
                "a node's world changes do not report labor; the executor accounts for its declared effort");
            node.work(level, worker, job);
            helper.assertTrue(colony.kept(LivingContent.ID).getList("hunger", 10).isEmpty(),
                "retrying a world operation does not bypass execution accounting");
            logs.forEach(at -> helper.assertTrue(level.getBlockState(at).isAir(), "the logs were removed"));
            leaves.forEach(at -> helper.assertTrue(level.getBlockState(at).isAir(), "the leaves were removed"));
        } finally {
            body.mob().discard();
            colony.raze();
        }
        helper.succeed();
    }

    private static Worker worker(Body body) {
        return new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body.mob(); }
            public Container pack() { return body.pack(); }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { throw new AssertionError("unexpected spill"); }
            public int rankOf(String perk) { return 0; }
            public List<Node> route() { return List.of(); }
            public Placement placement() { throw new AssertionError("unexpected placement"); }
        };
    }
}
