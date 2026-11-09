package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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

@EventBusSubscriber(modid = FolkwaysCartsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_carts")
@PrefixGameTestTemplate(false)
public final class CartContentsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CartContentsGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void cartCardCountsWholeStockAndUpdatesWhenUnloaded(GameTestHelper helper) {
        var cart = (AbstractCargoCart) BuiltInRegistries.ENTITY_TYPE
            .get(io.github.izakyl.folkways.carts.Line.Kind.SUPPLY.entity()).create(helper.getLevel());
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        cart.moveTo(helper.absolutePos(new BlockPos(1, 1, 1)).getBottomCenter());
        helper.getLevel().addFreshEntity(cart);
        for (int slot = 0; slot < 8; slot++) {
            cart.setItem(slot, new ItemStack(Items.WHEAT, 64));
        }
        cart.setItem(8, new ItemStack(Items.APPLE, 17));
        helper.assertTrue(count(cart, "minecraft:wheat") == 512 && count(cart, "minecraft:apple") == 17,
            "the synchronized card contains wheat 512 and apples 17, not representative cargo stacks");
        com.mojang.logging.LogUtils.getLogger().info("cart-card stock: wheat={} modelCount={}",
            count(cart, "minecraft:wheat"), cart.getCargo().stream().mapToInt(ItemStack::getCount).sum());
        cart.removeItem(0, 32);
        helper.runAfterDelay(6, () -> {
            helper.assertTrue(count(cart, "minecraft:wheat") == 480, "partial unloading updates the card");
            cart.clearContent();
            helper.runAfterDelay(6, () -> {
                helper.assertTrue(CartContents.lines(cart).stream().noneMatch(Line.Goods.class::isInstance)
                        && CartContents.lines(cart).size() == 2,
                    "an empty cart keeps its title and explicit empty line");
                cart.discard();
                helper.succeed();
            });
        });
    }

    private static long count(AbstractCargoCart cart, String item) {
        return CartContents.lines(cart).stream().filter(Line.Goods.class::isInstance).map(Line.Goods.class::cast)
            .flatMap(line -> line.stacks().stream()).filter(stack -> stack.item().toString().equals(item))
            .mapToLong(Line.Stack::count).sum();
    }
}
