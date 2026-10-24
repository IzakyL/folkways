package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.IngredientSpecs;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.crafting.CompoundIngredient;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.neoforged.neoforge.common.crafting.DifferenceIngredient;
import net.neoforged.neoforge.common.crafting.IntersectionIngredient;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IngredientSpecsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(IngredientSpecsGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void componentIngredientsAllowOnlyTheExtrasTheirPredicateAllows(GameTestHelper helper) {
        Ingredient partial = potion(false);
        Ingredient strict = potion(true);
        ItemStack named = partial.getItems()[0].copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Medicine"));
        ItemStack other = PotionContents.createItemStack(Items.POTION, Potions.POISON);
        for (Ingredient ingredient : List.of(partial, strict)) {
            ItemSpec spec = IngredientSpecs.of(ingredient).orElseThrow();
            for (ItemStack stack : List.of(partial.getItems()[0], named, other)) {
                helper.assertTrue(Goods.matches(stack, spec) == ingredient.test(stack),
                    "strict and non-strict recipe predicates retain their component semantics");
            }
            ItemSpec loaded = ItemSpec.load(Reader.of(spec.save(helper.getLevel().registryAccess())),
                helper.getLevel().registryAccess()).orElseThrow();
            helper.assertTrue(loaded.equals(spec) && Goods.matches(named, loaded) == ingredient.test(named),
                "recipe component semantics survive persistence");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void compoundIntersectionAndDifferenceIngredientsRetainTheirChildren(GameTestHelper helper) {
        Ingredient name = DataComponentIngredient.of(false, DataComponents.CUSTOM_NAME,
            Component.literal("Medicine"), Items.POTION);
        Ingredient intersection = IntersectionIngredient.of(potion(false), name);
        Ingredient union = CompoundIngredient.of(Ingredient.of(Items.PAPER),
            CompoundIngredient.of(intersection, Ingredient.of(Items.SUGAR)));
        Ingredient difference = DifferenceIngredient.of(union, Ingredient.of(Items.SUGAR));
        ItemStack named = potion(false).getItems()[0].copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Medicine"));
        named.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        for (Ingredient ingredient : List.of(intersection, union, difference)) {
            ItemSpec spec = IngredientSpecs.of(ingredient).orElseThrow();
            for (ItemStack stack : List.of(named, potion(false).getItems()[0], new ItemStack(Items.PAPER),
                    new ItemStack(Items.SUGAR), new ItemStack(Items.STONE))) {
                helper.assertTrue(Goods.matches(stack, spec) == ingredient.test(stack),
                    "compound recipes use their children rather than incomplete display samples");
            }
        }
        helper.assertTrue(IngredientSpecs.of(DifferenceIngredient.of(Ingredient.of(Items.POTION), potion(false))).isEmpty(),
            "an unrepresentable component complement is refused instead of guessed from display samples");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void recipeCustomDataValuesDoNotTurnIntoNbtSubsetPredicates(GameTestHelper helper) {
        CompoundTag data = new CompoundTag();
        data.putString("AmmoId", "tacz:45acp");
        Ingredient ingredient = DataComponentIngredient.of(false, DataComponents.CUSTOM_DATA, CustomData.of(data), Items.PAPER);
        ItemSpec spec = IngredientSpecs.of(ingredient).orElseThrow();
        ItemStack stack = ingredient.getItems()[0].copy();
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Ammunition"));
        helper.assertTrue(ingredient.test(stack) && Goods.matches(stack, spec), "unrelated extra components remain allowed");
        data.putString("maker", "test");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        helper.assertTrue(!ingredient.test(stack) && !Goods.matches(stack, spec),
            "NeoForge's custom_data component value equality stays distinct from an NBT subset constraint");
        helper.assertTrue(!Goods.matches(stack, ItemSpec.load(Reader.of(spec.save())).orElseThrow()),
            "the component equality mode also survives NBT persistence");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void containmentRequiresEveryOfferedStackToMatch(GameTestHelper helper) {
        ItemSpec broad = ItemSpec.of(Items.POTION);
        ItemSpec partial = IngredientSpecs.of(potion(false)).orElseThrow();
        ItemStack named = potion(false).getItems()[0].copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Medicine"));
        ItemSpec exact = Goods.specOf(named);
        helper.assertTrue(Goods.overlap(partial, broad) && !Goods.includes(partial, broad)
            && Goods.includes(broad, partial) && Goods.includes(partial, exact) && !Goods.includes(exact, partial),
            "overlap is insufficient to prove containment, while a stronger old specification is safe");
        helper.assertTrue(!Goods.includes(partial, ItemSpec.anyOf(List.of(exact, broad)))
            && Goods.includes(ItemSpec.anyOf(List.of(broad, ItemSpec.of(Items.PAPER))), partial),
            "every old alternative must be contained by the new requirement");
        helper.assertTrue(Goods.members(exact) == Goods.members(broad), "component variants share item-membership metadata");
        helper.succeed();
    }

    private static Ingredient potion(boolean strict) {
        return DataComponentIngredient.of(strict, DataComponents.POTION_CONTENTS, new PotionContents(Potions.HEALING), Items.POTION);
    }
}
