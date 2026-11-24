package io.github.izakyl.folkways.core.mechanics.api;

import io.github.izakyl.folkways.core.base.Ledger;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.IngredientSpecs;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class Mechanics {

    private static final Ledger<Station> STATIONS = new Ledger<>("a station type", Station::id);
    private static final Ledger<Source> SOURCES = new Ledger<>("a recipe catalogue", Source::id);

    private static final Map<RecipeType<?>, ToIntFunction<Recipe<?>>> DURATIONS = new HashMap<>();

    private static final Ledger<Kind> KINDS = Ledger.sealedOnceRead("a drawing kind", Kind::id);

    public record Station(ResourceLocation id, Component name, ResourceLocation delegation,
                          List<ResourceLocation> blocks, List<ResourceLocation> vocations,
                          List<ResourceLocation> recipeTypes, List<Component> conditions) {

        public Station {
            name = name.copy();
            blocks = List.copyOf(blocks);
            vocations = List.copyOf(vocations);
            recipeTypes = List.copyOf(recipeTypes);
            conditions = conditions.stream().map(Component::copy).map(Component.class::cast).toList();
        }

        public static Station block(Block block, ResourceLocation delegation, ResourceLocation vocation,
                                    List<RecipeType<?>> recipes, List<Component> conditions) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            return new Station(id, block.getName(), delegation, List.of(id), List.of(vocation),
                    recipes.stream().map(Mechanics::recipeType).toList(), conditions);
        }
    }

    public record Processing(ResourceLocation id, ResourceLocation station, List<Need> inputs, List<Amount> outputs,
                             List<Component> conditions) {

        public Processing {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            conditions = conditions.stream().map(Component::copy).map(Component.class::cast).toList();
        }
    }

    public record WorkshopEntry(UUID id, ResourceLocation kind, Component name, WorldPos site,
                                Optional<Station> station, Optional<Workshop.FacilityDetails> facility) {
    }

    public record Kind(ResourceLocation id, Component name, Component purpose, String shape, int limit) {
    }

    public record Vocation(ResourceLocation id, Component name, Component purpose) {
    }

    public interface Source {
        ResourceLocation id();

        List<Processing> recipes(RecipeManager recipes, HolderLookup.Provider registries);
    }

    private Mechanics() {
    }

    public static void station(Station station) {
        STATIONS.claim(station);
    }

    public static void recipes(Source source) {
        SOURCES.claim(source);
    }

    public static void duration(RecipeType<?> type, ToIntFunction<Recipe<?>> duration) {
        if (DURATIONS.putIfAbsent(type, duration) != null) {
            throw new IllegalArgumentException("recipe duration already registered for " + type);
        }
    }

    public static int duration(Recipe<?> recipe) {
        var duration = DURATIONS.get(recipe.getType());
        return duration == null ? 20 : duration.applyAsInt(recipe);
    }

    public static void kind(Kind kind) {
        KINDS.claim(kind);
    }

    public static List<Kind> kinds() {
        return KINDS.all().stream().sorted(Comparator.comparing(value -> value.id().toString())).toList();
    }

    public static List<Station> stations() {
        return STATIONS.all().stream().sorted(Comparator.comparing(value -> value.id().toString())).toList();
    }

    /** Current published workshops, across all dimensions of the colony. */
    public static List<WorkshopEntry> workshops(Colony colony, MinecraftServer server) {
        List<WorkshopEntry> entries = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Workshop workshop : colony.workshopsIn(level.dimension())) {
                WorldPos site = workshop.site().where();
                var facility = workshop.facilityDetails();
                var block = facility.isPresent() || !(workshop.site() instanceof WorkSite.AtBlock)
                    ? Optional.<Block>empty() : WorldSpaces.storage(level, site)
                        .flatMap(pos -> Optional.ofNullable(level.getChunkSource().getChunkNow(
                            pos.getX() >> 4, pos.getZ() >> 4)).map(chunk -> chunk.getBlockState(pos)))
                        .filter(state -> !state.isAir()).map(BlockState::getBlock);
                var station = block.map(BuiltInRegistries.BLOCK::getKey).flatMap(STATIONS::of);
                Component name = facility.map(Workshop.FacilityDetails::name)
                    .orElseGet(() -> block.<Component>map(Block::getName)
                        .orElseGet(() -> Component.literal(workshop.id().toString())));
                entries.add(new WorkshopEntry(workshop.key(), workshop.id(), name, site, station, facility));
            }
        }
        return entries.stream().sorted(Comparator.comparing(WorkshopEntry::id)).toList();
    }

    public static List<Vocation> vocations() {
        return Vocations.ids().stream().map(id -> new Vocation(id, named("vocation", id),
                Component.translatable("folkways.vocation." + id.getNamespace() + "." + id.getPath() + ".about")))
                .toList();
    }

    public static List<Processing> recipes(RecipeManager recipes, HolderLookup.Provider registries) {
        return SOURCES.all().stream().flatMap(source -> source.recipes(recipes, registries).stream())
                .sorted(Comparator.comparing((Processing value) -> value.id().toString())
                        .thenComparing(value -> value.station().toString())).toList();
    }

    public static ResourceLocation recipeType(RecipeType<?> type) {
        ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        // RecipeType.simple may be used without registering the type itself.
        return id == null ? ResourceLocation.parse(type.toString()) : id;
    }

    public static Component named(String kind, ResourceLocation id) {
        return Component.translatable("folkways." + kind + "." + id.getNamespace() + "." + id.getPath());
    }

    public static Optional<ItemSpec> ingredient(Ingredient ingredient) {
        return IngredientSpecs.of(ingredient);
    }

    public static Optional<Processing> recipe(RecipeHolder<?> holder, ResourceLocation station,
                                           HolderLookup.Provider registries) {
        var recipe = holder.value();
        var output = recipe.getResultItem(registries);
        if (output.isEmpty()) {
            return Optional.empty();
        }
        List<Need> inputs = new ArrayList<>();
        if (recipe instanceof SmithingTransformRecipe smithing) {
            for (Predicate<ItemStack> slot : List.<Predicate<ItemStack>>of(smithing::isTemplateIngredient,
                    smithing::isBaseIngredient, smithing::isAdditionIngredient)) {
                Optional<ItemSpec> spec = Goods.accepting(slot);
                if (spec.isEmpty()) {
                    return Optional.empty();
                }
                inputs.add(new Need(spec.get(), 1));
            }
        } else {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                Optional<ItemSpec> spec = ingredient(ingredient);
                if (spec.isEmpty()) {
                    return Optional.empty();
                }
                inputs.add(new Need(spec.get(), 1));
            }
        }
        return inputs.isEmpty() ? Optional.empty() : Optional.of(new Processing(holder.id(), station, inputs,
                List.of(new Amount(Goods.specOf(output), output.getCount())), List.of()));
    }
}
