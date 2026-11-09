package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class CompletedConstructionGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CompletedConstructionGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void finishingDoubleChestPreservesCompletedHalfAndItsInventory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos left = helper.absolutePos(new BlockPos(8, 2, 8));
        BlockState leftState = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos right = left.relative(ChestBlock.getConnectedDirection(leftState));
        BlockState rightState = leftState.setValue(ChestBlock.TYPE, ChestType.RIGHT);
        for (BlockPos pos : List.of(left, right)) {
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        }
        level.setBlock(left, leftState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        ChestBlockEntity original = (ChestBlockEntity) level.getBlockEntity(left);
        original.setItem(0, new ItemStack(Items.DIAMOND, 7));
        BuildTarget target = new BuildTarget(WorldPos.of(level, left).realm(), Map.of(left, leftState, right,
            rightState));
        SiteRig rig = SiteRig.start(level, target);
        helper.assertTrue(rig.site.metCells().contains(left), "the existing half is completed before work begins");
        helper.assertValueEqual(rig.action(right).spec().needs().stream().mapToLong(need -> need.count()).sum(), 1L,
            "only the missing chest is supplied");
        Worker worker = worker(helper, left.south(3), new SimpleContainer(new ItemStack(Items.CHEST)));
        Outcome outcome = rig.work(helper, right, worker);
        helper.assertTrue(outcome instanceof Outcome.Done, "the missing half can be constructed");
        helper.assertTrue(level.getBlockEntity(left) == original, "the completed chest entity is never replaced");
        helper.assertTrue(original.getItem(0).is(Items.DIAMOND) && original.getItem(0).getCount() == 7,
            "the completed half keeps every stored item");
        helper.assertTrue(((Outcome.Done) outcome).made().stream().noneMatch(stack -> stack.is(Items.DIAMOND)
            || stack.is(Items.CHEST)), "completed contents and chest are not harvested as construction drops");
        helper.assertTrue(level.getBlockState(right).is(Blocks.CHEST), "the other half is actually present");
        helper.assertTrue(rig.site.complete(), "both halves are permanently completed");
        rig.site.close();
        worker.body().discard();
        helper.succeed();
    }

    private static Worker worker(GameTestHelper helper, BlockPos feet, SimpleContainer pack) {
        Mob body = EntityType.PIG.create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(feet));
        return TestWorker.of(body, new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig")), pack).spill(stack -> { });
    }
}
