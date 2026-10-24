package io.github.izakyl.folkways.plugins.person.living;

import com.github.ysbbbbbb.kaleidoscopecookery.block.food.FoodBiteBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.registry.FoodBiteRegistry;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.Quality;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
import io.github.izakyl.folkways.FolkwaysMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryDiningFoodsGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(CookeryDiningFoodsGameTests.class); }

    @GameTest(template = "empty")
    public static void placedQualityDishUsesNativePortionsAndReturnsFinalLootOnlyOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        var id = ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "dongpo_pork");
        FoodBiteBlock block = (FoodBiteBlock) BuiltInRegistries.BLOCK.get(id);
        var data = FoodBiteRegistry.FOOD_DATA_MAP.get(id);
        var at = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
        ItemStack dishes = new ItemStack(block, 2);
        QualityUtils.setQuality(dishes, Quality.SUPERB);
        helper.assertTrue(DiningFoods.placeable(dishes), "known single-cell native meal is placeable");
        helper.assertTrue(DiningFoods.place(level, at, dishes), "native food placement succeeds");
        helper.assertValueEqual(dishes.getCount(), 1, "one whole dish is placed");
        helper.assertTrue(QualityUtils.getQuality(dishes) == Quality.SUPERB, "unplaced stack retains quality");
        var end = level.getBlockState(at).setValue(block.getBites(), block.getMaxBites());
        List<ItemStack> expected = Block.getDrops(end, level, at, null);
        List<ItemStack> leftovers = new ArrayList<>();
        try (var fixture = new EatingFixture(helper, ItemStack.EMPTY, 0)) {
            for (int bite = 0; bite < block.getMaxBites(); bite++) {
                var portion = DiningFoods.serving(level, at).orElseThrow();
                helper.assertTrue(QualityUtils.getQuality(portion.item()) == Quality.SUPERB,
                    "placed quality survives into each portion's identity");
                helper.assertValueEqual(portion.food().nutrition(),
                    (int) Math.round(data.blockFood().nutrition() * Quality.SUPERB.getRatio()),
                    "a native block bite uses blockFood rather than whole-item nutrition");
                List<ItemStack> made = DiningFoods.eat(level, at, fixture.worker);
                if (bite + 1 < block.getMaxBites()) {
                    helper.assertTrue(made.isEmpty(), "containers appear only after the last bite");
                    helper.assertValueEqual(level.getBlockState(at).getValue(block.getBites()), bite + 1,
                        "the native bites state changes exactly once");
                }
                leftovers.addAll(made);
            }
            helper.assertTrue(level.getBlockState(at).isAir(), "the empty dish is removed");
            helper.assertTrue(DiningFoods.serving(level, at).isEmpty(), "the empty dish never feeds again");
            for (ItemStack wanted : expected) {
                helper.assertValueEqual(leftovers.stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, wanted))
                    .mapToInt(ItemStack::getCount).sum(), wanted.getCount(), "native final-state loot returns exactly once");
            }
            helper.assertValueEqual(leftovers.stream().mapToInt(ItemStack::getCount).sum(),
                expected.stream().mapToInt(ItemStack::getCount).sum(), "no extra meal or container is synthesized");
            for (var effect : data.blockFood().effects()) {
                if (effect.probability() >= 1.0F) {
                    var active = fixture.body.mob().getEffect(effect.effect().getEffect());
                    helper.assertTrue(active != null, "native guaranteed bite effect is applied to the resident");
                    helper.assertValueEqual(active.getDuration(),
                        (int) Math.round(effect.effect().getDuration() * Quality.SUPERB.getRatio()),
                        "native effect duration honors placed quality");
                }
            }
        }
        helper.succeed();
    }
}
