package io.github.izakyl.folkways.cookery.audit;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.github.izakyl.folkways.FolkwaysMod;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NativeRecipeAuditGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(NativeRecipeAuditGameTests.class); }

    @GameTest(template = "empty")
    public static void auditTheActualLoadedRecipesIngredientsAndFoodItems(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        JsonObject audit = new JsonObject();
        audit.addProperty("cookery_version", "1.6.0-neoforge+mc1.21.1");
        audit.addProperty("source_commit", "4d39e36cfa749939ba5f9d6d3b715ef9a984cb9c");
        var manifest = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(
            java.util.Objects.requireNonNull(NativeRecipeAuditGameTests.class.getResourceAsStream("/cookery-native-recipes.json")),
            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        java.util.Set<String> owned = new java.util.LinkedHashSet<>();
        manifest.getAsJsonArray("recipes").forEach(id -> owned.add(id.getAsString()));
        helper.assertValueEqual(owned.size(), 612, "the pinned jar index includes 580 mod IDs and 32 Minecraft IDs");
        audit.add("manifest", manifest);
        JsonArray recipes = new JsonArray();
        Map<String, Integer> types = new LinkedHashMap<>();
        int nativeCount = 0;
        JsonArray fixtures = new JsonArray();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes().stream()
                .sorted(Comparator.comparing(one -> one.id().toString())).toList()) {
            Recipe<?> recipe = holder.value();
            var type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            // Cookery registers its serializers but uses RecipeType.simple without a type-registry entry.
            if (type == null) type = net.minecraft.resources.ResourceLocation.tryParse(recipe.getType().toString());
            if (type == null) {
                helper.assertFalse(owned.contains(holder.id().toString()),
                    "every native Cookery recipe names its actual production type");
                fixtures.add(holder.id().toString());
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", holder.id().toString());
            row.addProperty("cookery_owned", owned.contains(holder.id().toString()));
            row.addProperty("type", type.toString());
            row.add("definition", Recipe.CODEC.encodeStart(ops, recipe).getOrThrow());
            JsonArray resolved = new JsonArray();
            recipe.getIngredients().forEach(ingredient -> {
                JsonArray alternatives = new JsonArray();
                java.util.Arrays.stream(ingredient.getItems()).filter(stack -> !stack.isEmpty())
                    .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).distinct().sorted()
                    .forEach(alternatives::add);
                resolved.add(alternatives);
            });
            row.add("resolved_ingredients", resolved);
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (!result.isEmpty()) row.add("result", ItemStack.CODEC.encodeStart(ops, result).getOrThrow());
            recipes.add(row);
            if (owned.contains(holder.id().toString())) {
                nativeCount++;
                types.merge(row.get("type").getAsString(), 1, Integer::sum);
            }
        }
        audit.add("unregistered_test_fixtures", fixtures);
        audit.addProperty("native_recipe_count", nativeCount);
        audit.add("native_types", new GsonBuilder().create().toJsonTree(types));
        audit.add("recipes", recipes);
        JsonObject items = new JsonObject();
        for (var item : BuiltInRegistries.ITEM) {
            ItemStack stack = item.getDefaultInstance();
            if (stack.isEmpty()) continue;
            JsonObject row = new JsonObject();
            row.addProperty("food", stack.has(DataComponents.FOOD));
            row.addProperty("stack_size", stack.getMaxStackSize());
            ItemStack remainder = stack.getCraftingRemainingItem();
            if (!remainder.isEmpty()) row.addProperty("remainder", BuiltInRegistries.ITEM.getKey(remainder.getItem()).toString());
            JsonArray tags = new JsonArray();
            item.builtInRegistryHolder().tags().map(tag -> tag.location().toString()).sorted().forEach(tags::add);
            row.add("tags", tags);
            items.add(BuiltInRegistries.ITEM.getKey(item).toString(), row);
        }
        audit.add("items", items);
        Files.writeString(Path.of("cookery-recipe-audit.json"), new GsonBuilder().setPrettyPrinting().create().toJson(audit));
        helper.assertValueEqual(nativeCount, 612, "the audit covers every recipe in the pinned official Cookery jar");
        helper.assertTrue(types.containsKey("kaleidoscope_cookery:millstone")
            && types.containsKey("kaleidoscope_cookery:pot"), "the audit includes native appliances, not just ordinary crafting");
        helper.succeed();
    }
}
