package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.mechanics.api.Mechanics;
import io.github.izakyl.folkways.core.script.api.ScriptSteps;
import io.github.izakyl.folkways.core.script.api.ScriptValues;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeManager;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkThread;

record WorkshopRecipeSnapshot(Map<String, List<Map<String, Object>>> types, Map<String, ItemSpec> items) {

    private static final Map<RecipeManager, WorkshopRecipeSnapshot> CATALOGS = new WeakHashMap<>();

    static final StarlarkCallable BUILTIN = new StarlarkCallable() {
        @Override
        public String getName() {
            return "recipes_of";
        }

        @Override
        public Object fastcall(StarlarkThread thread, Object[] positional, Object[] named) throws EvalException {
            if (positional.length != 1 || named.length != 0 || !(positional[0] instanceof String id)) {
                throw Starlark.errorf("recipes_of expects a recipe type id");
            }
            var snapshot = thread.getThreadLocal(WorkshopRecipeSnapshot.class);
            if (snapshot == null) {
                throw Starlark.errorf("recipes_of is available inside recipes(input)");
            }
            var rows = snapshot.types().get(id);
            if (rows == null) {
                throw Starlark.errorf("unknown recipe type %s", id);
            }
            ScriptSteps.charge(thread, rows.size());
            return ScriptValues.facilityRecipes().snapshot(Map.of("recipes", rows), value -> value, thread.mutability())
                .get("recipes");
        }
    };

    static synchronized WorkshopRecipeSnapshot of(ServerLevel level) {
        return CATALOGS.computeIfAbsent(level.getRecipeManager(), ignored -> snapshot(level));
    }

    private static WorkshopRecipeSnapshot snapshot(ServerLevel level) {
        var station = DefinedFacilities.ID;
        Map<String, List<Map<String, Object>>> types = new LinkedHashMap<>();
        Map<String, ItemSpec> items = new LinkedHashMap<>();
        BuiltInRegistries.RECIPE_TYPE.keySet().forEach(id -> types.put(id.toString(), new ArrayList<>()));
        level.getRecipeManager().getRecipes().stream().sorted(Comparator.comparing(holder -> holder.id()))
            .forEach(holder -> Mechanics.recipe(holder, station, level.registryAccess()).ifPresent(recipe -> {
                var type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
                if (type != null) {
                    types.get(type.toString()).add(Map.of(
                        "id", recipe.id().toString(),
                        "inputs", recipe.inputs().stream().map(need -> item(items, need.spec(), need.count())).toList(),
                        "outputs", recipe.outputs().stream().map(amount -> item(items, amount.spec(), amount.count())).toList(),
                        "ticks", Math.max(1, Mechanics.duration(holder.value()))));
                }
            }));
        types.replaceAll((type, rows) -> List.copyOf(rows));
        return new WorkshopRecipeSnapshot(Map.copyOf(types), Map.copyOf(items));
    }

    private static Map<String, Object> item(Map<String, ItemSpec> items, ItemSpec spec, long count) {
        String name = spec.describe();
        items.putIfAbsent(name, spec);
        return Map.of("item", name, "count", count);
    }
}
