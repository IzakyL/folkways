package io.github.izakyl.folkways.vanilla.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.shell.carts.api.CartHitches;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class CartsCardsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CartsCardsGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void cartCardCountsWholeStockAndUpdatesWhenUnloaded(GameTestHelper helper) {
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
            "the synchronized card contains wheat 512 and apples 17, not representative cargo stacks");
        cart.removeItem(0, 32);
        helper.runAfterDelay(6, () -> {
            helper.assertTrue(count(cart, "minecraft:wheat") == 480, "partial unloading updates the card");
            cart.clearContent();
            helper.runAfterDelay(6, () -> {
                helper.assertTrue(CartsCards.lines(cart).equals(List.of(Line.text(cart.getDisplayName()),
                        Line.text(Component.translatable("folkways.draft.empty")))),
                    "an empty cart keeps its title and explicit empty line");
                cart.discard();
                helper.succeed();
            });
        });
    }

    private static long count(AbstractCargoCart cart, String item) {
        return CartsCards.lines(cart).stream().filter(Line.Goods.class::isInstance).map(Line.Goods.class::cast)
            .flatMap(line -> line.stacks().stream()).filter(stack -> stack.item().toString().equals(item))
            .mapToLong(Line.Stack::count).sum();
    }
}
