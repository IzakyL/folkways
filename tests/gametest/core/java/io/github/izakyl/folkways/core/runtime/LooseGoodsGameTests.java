package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.plan.internal.Message;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.work.internal.PickUp;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LooseGoodsGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LooseGoodsGameTests.class);
    }

    // Goods a plugin leaves lying for the colony are asked to be picked up, as goods a resident spilled are.
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void goodsLeftLyingAreAskedToBePickedUp(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(at.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
        }
        var drop = new ItemEntity(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
            new ItemStack(Items.COBBLESTONE, 12));
        level.addFreshEntity(drop);
        var colony = new ColonyData();
        var labor = new ColonyLabor(colony, level.dimension());
        try {
            labor.leftLoose(drop);
            List<Message> asked = hand(labor);
            helper.assertTrue(asked.stream().anyMatch(message -> message instanceof Message.Submitted submitted
                    && submitted.owner().equals(PickUp.DOMAIN)),
                "the colony is asked to pick the stack up: " + asked);
        } finally {
            labor.close();
            ColonyWays.forget(colony.colonyId());
            drop.discard();
        }
        helper.succeed();
    }

    private static List<Message> hand(ColonyLabor labor) {
        try {
            var field = ColonyLabor.class.getDeclaredField("inbox");
            field.setAccessible(true);
            Inbox inbox = (Inbox) field.get(labor);
            List<Message> batch = inbox.hand();
            inbox.digested();
            return batch;
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }
}
