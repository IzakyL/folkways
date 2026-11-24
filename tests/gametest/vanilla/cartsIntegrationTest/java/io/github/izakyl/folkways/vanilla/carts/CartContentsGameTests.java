package io.github.izakyl.folkways.vanilla.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.core.shell.carts.api.CartContents;
import io.github.izakyl.folkways.core.shell.carts.api.CartHitches;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class CartContentsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CartContentsGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void cartSynchronizesWholeStockAndUpdatesWhenUnloaded(GameTestHelper helper) {
        var cart = (AbstractCargoCart) BuiltInRegistries.ENTITY_TYPE
            .get(ResourceLocation.fromNamespaceAndPath(CartHitches.ASTIKOR, "supply_cart")).create(helper.getLevel());
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        cart.moveTo(helper.absolutePos(new BlockPos(1, 1, 1)).getBottomCenter());
        helper.getLevel().addFreshEntity(cart);
        for (int slot = 0; slot < 8; slot++) {
            cart.setItem(slot, new ItemStack(Items.WHEAT, 64));
        }
        cart.setItem(8, new ItemStack(Items.APPLE, 17));
        helper.assertTrue(count(cart, "minecraft:wheat") == 512 && count(cart, "minecraft:apple") == 17,
            "the synchronized contents contain all 512 wheat and 17 apples");
        com.mojang.logging.LogUtils.getLogger().info("synchronized cart stock: wheat={} modelCount={}",
            count(cart, "minecraft:wheat"), cart.getCargo().stream().mapToInt(ItemStack::getCount).sum());
        cart.removeItem(0, 32);
        helper.runAfterDelay(6, () -> {
            helper.assertTrue(count(cart, "minecraft:wheat") == 480, "partial unloading updates synchronized contents");
            cart.clearContent();
            helper.runAfterDelay(6, () -> {
                helper.assertTrue(((CartContents.View) cart).folkways$contents().isEmpty(),
                    "clearing the cart removes all synchronized stock");
                cart.discard();
                helper.succeed();
            });
        });
    }

    private static long count(AbstractCargoCart cart, String item) {
        return ((CartContents.View) cart).folkways$contents().getLong(item);
    }
}
