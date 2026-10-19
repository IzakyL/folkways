package io.github.izakyl.folkways.core.api.terms;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ItemDropsGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ItemDropsGameTests.class);
    }

    @GameTest(template = "empty")
    public static void separateAreasCaptureOnlyTheirNewDropsAndLeaveTheGapAlone(GameTestHelper helper) {
        var level = helper.getLevel();
        Vec3 left = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));
        Vec3 right = left.add(20, 0, 0);
        Vec3 gap = left.add(10, 0, 0);
        var old = new ItemEntity(level, left.x, left.y, left.z, new ItemStack(Items.DIAMOND, 7));
        var first = new ItemEntity(level, left.x, left.y, left.z, new ItemStack(Items.CHICKEN, 3));
        var second = new ItemEntity(level, right.x, right.y, right.z, new ItemStack(Items.BONE, 2));
        var between = new ItemEntity(level, gap.x, gap.y, gap.z, new ItemStack(Items.STICK, 5));
        try {
            helper.assertTrue(level.addFreshEntity(old), "the original ground item exists before the interaction");
            ItemDrops.Captured<Boolean> captured = ItemDrops.capture(level,
                List.of(AABB.ofSize(left, 2, 2, 2), AABB.ofSize(right, 2, 2, 2)), () -> {
                    level.addFreshEntity(first);
                    level.addFreshEntity(second);
                    return level.addFreshEntity(between);
                });
            helper.assertTrue(captured.result(), "the new item in the uncaptured gap is allowed to spawn");
            helper.assertValueEqual(captured.items().size(), 2, "only the two new endpoint drops are delivered");
            helper.assertTrue(captured.items().get(0).is(Items.CHICKEN)
                    && captured.items().get(0).getCount() == 3
                    && captured.items().get(1).is(Items.BONE)
                    && captured.items().get(1).getCount() == 2,
                "both endpoint drops retain their actual items and quantities");
            helper.assertTrue(level.getEntity(first.getUUID()) == null && level.getEntity(second.getUUID()) == null,
                "captured endpoint drops are not also registered as loose entities");
            helper.assertTrue(level.getEntity(between.getUUID()) == between && between.getItem().getCount() == 5,
                "the gap drop remains an ordinary ground item");
            helper.assertTrue(level.getEntity(old.getUUID()) == old && old.getItem().getCount() == 7,
                "an existing item inside a capture area is never collected");
            helper.succeed();
        } finally {
            List.of(old, first, second, between).forEach(ItemEntity::discard);
        }
    }
}
