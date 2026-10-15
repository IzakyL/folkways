package io.github.izakyl.folkways.plugins.cookery.pot;

import com.github.ysbbbbbb.kaleidoscopecookery.crafting.container.SimpleInput;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexPotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.PotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.serializer.FlexPotRecipeSerializer;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.serializer.PotRecipeSerializer;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityEvaluator;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Need;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

record PotPlan(ResourceLocation id, PotRecipe recipe, Tag definition, boolean flexible,
               List<Need> materials, Optional<Need> carrier) {
    PotPlan {
        definition = definition.copy();
        materials = List.copyOf(materials);
    }

    static Optional<PotPlan> find(ServerLevel level, ResourceLocation id) {
        return level.getRecipeManager().byKey(id).flatMap(holder -> of(level, holder));
    }

    static Optional<PotPlan> of(ServerLevel level, RecipeHolder<?> holder) {
        if (FolkwaysConfig.refusedRecipes().contains(holder.id())) return Optional.empty();
        PotRecipe recipe;
        Tag definition;
        boolean flexible;
        if (holder.value() instanceof PotRecipe fixed) {
            recipe = fixed;
            definition = PotRecipeSerializer.CODEC.codec()
                .encodeStart(RegistryOps.create(NbtOps.INSTANCE, level.registryAccess()), fixed).getOrThrow();
            flexible = false;
        } else if (holder.value() instanceof FlexPotRecipe flex) {
            recipe = new PotRecipe(flex.time(), flex.stirFryCount(), flex.carrier(), flex.ingredients(), flex.result());
            definition = FlexPotRecipeSerializer.CODEC.codec()
                .encodeStart(RegistryOps.create(NbtOps.INSTANCE, level.registryAccess()), flex).getOrThrow();
            flexible = true;
        } else return Optional.empty();
        if (recipe.result().isEmpty() || recipe.result().getCount() > 64 || recipe.time() < 0 || recipe.stirFryCount() < 0) {
            return Optional.empty();
        }
        List<Need> materials = new ArrayList<>();
        materials.add(new Need(ItemSpec.of(TagMod.OIL), 1));
        for (Ingredient ingredient : recipe.ingredients()) {
            if (ingredient.isEmpty()) continue;
            Optional<ItemSpec> spec = goods(ingredient);
            if (spec.isEmpty()) return Optional.empty();
            materials.add(new Need(spec.get(), 1));
        }
        if (materials.size() <= 1) return Optional.empty();
        Optional<ItemSpec> carrier = recipe.carrier().isEmpty() ? Optional.empty() : goods(recipe.carrier());
        if (!recipe.carrier().isEmpty() && carrier.isEmpty()) return Optional.empty();
        return Optional.of(new PotPlan(holder.id(), recipe, definition, flexible, merge(materials),
            carrier.map(spec -> new Need(spec, recipe.result().getCount()))));
    }

    static Optional<ItemSpec> goods(Ingredient ingredient) {
        if (ingredient.isEmpty() || ingredient.hasNoItems()) return Optional.empty();
        if (!ingredient.isCustom()) {
            List<ItemSpec> values = new ArrayList<>();
            for (Ingredient.Value value : ingredient.getValues()) {
                if (value instanceof Ingredient.TagValue tag) values.add(ItemSpec.of(tag.tag()));
                else if (value instanceof Ingredient.ItemValue item && !item.item().isEmpty()) values.add(Goods.specOf(item.item()));
            }
            if (!values.isEmpty()) return Optional.of(ItemSpec.anyOf(values));
        }
        return Goods.accepting(ingredient);
    }

    ItemSpec produces() { return Goods.specOf(recipe.result()); }
    int count() { return recipe.result().getCount(); }
    int ingredientCount() { return (int) recipe.ingredients().stream().filter(ingredient -> !ingredient.isEmpty()).count(); }

    List<Need> perRun() {
        List<Need> needs = new ArrayList<>(materials);
        carrier.ifPresent(needs::add);
        return merge(needs);
    }

    List<Need> total(int runs) {
        return perRun().stream().map(need -> new Need(need.spec(), need.count() * runs, true)).toList();
    }

    List<Predicate<ItemStack>> slots() {
        List<Predicate<ItemStack>> slots = new ArrayList<>();
        slots.add(stack -> stack.is(TagMod.OIL));
        recipe.ingredients().stream().filter(ingredient -> !ingredient.isEmpty()).forEach(slots::add);
        for (int index = 0; index < (carrier.isPresent() ? count() : 0); index++) slots.add(recipe.carrier());
        return List.copyOf(slots);
    }

    boolean selected(ServerLevel level, SimpleInput input) {
        var fixed = level.getRecipeManager().getRecipeFor(ModRecipes.POT_RECIPE, input, level);
        if (fixed.isPresent()) return !flexible && fixed.get().id().equals(id);
        return flexible && level.getRecipeManager().getRecipeFor(ModRecipes.FLEX_POT_RECIPE, input, level)
            .filter(holder -> holder.id().equals(id)).isPresent();
    }

    ItemStack result(ServerLevel level, List<ItemStack> inputs) {
        ItemStack expected = recipe.result().copy();
        if (flexible) QualityUtils.setQuality(expected, QualityEvaluator.evaluate(inputs, recipe.ingredients(), id, level.getSeed()));
        return expected;
    }

    List<Amount> leaves(int runs) {
        List<Amount> leaves = new ArrayList<>();
        if (recipe.result().getMaxStackSize() < Goods.stackSize(produces())) {
            leaves.addAll(Leaves.room((int) (((long) count() * runs + recipe.result().getMaxStackSize() - 1) / recipe.result().getMaxStackSize())));
        } else leaves.add(new Amount(produces(), (long) count() * runs));
        for (Ingredient ingredient : recipe.ingredients()) {
            if (ingredient.isEmpty()) continue;
            var containers = java.util.Arrays.stream(ingredient.getItems()).map(ItemUtils::getContainerItem)
                .filter(item -> item != Items.AIR).distinct().toList();
            if (containers.size() == 1) leaves.add(new Amount(Goods.specOf(containers.getFirst().getDefaultInstance()), runs));
            else if (containers.size() > 1 || ingredient.isCustom()) leaves.addAll(Leaves.room(runs));
        }
        // A failed timed batch can still return one native dark dish instead of its promised food.
        leaves.addAll(Leaves.room(1));
        return Leaves.merged(leaves);
    }

    private static List<Need> merge(List<Need> needs) {
        Map<ItemSpec, Long> counts = new LinkedHashMap<>();
        needs.forEach(need -> counts.merge(need.spec(), need.count(), Long::sum));
        return counts.entrySet().stream().map(entry -> new Need(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingInt(need -> Goods.members(need.spec()).size())).toList();
    }
}
