package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.perk.Perk;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class WaresContent {

    public static final ResourceLocation LOADING = ResourceLocation.fromNamespaceAndPath("folkways", "loading");

    public static final ResourceLocation UNLOADING = ResourceLocation.fromNamespaceAndPath("folkways", "unloading");

    public static final ResourceLocation CLEARING_STATION = ResourceLocation.fromNamespaceAndPath("folkways", "clearing_station");

    public static final ResourceLocation FUELING = ResourceLocation.fromNamespaceAndPath("folkways", "fueling");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "wares");

    public static final ResourceLocation STORE =
        ResourceLocation.fromNamespaceAndPath("folkways", "store");

    public static final ResourceLocation STATION =
        ResourceLocation.fromNamespaceAndPath("folkways", "station");

    public static final ResourceLocation CRAFTING =
        ResourceLocation.fromNamespaceAndPath("folkways", "crafting");
    public static final String QUICK_HANDS = "crafting_quick_hands";

    public static final Schema.Setting FUEL = new Schema.Setting.Items(
        "fuel",
        "folkways.settings.fuel",
        ResourceLocation.withDefaultNamespace("coal"),
        List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("coal")),
            ItemFilter.item(ResourceLocation.withDefaultNamespace("charcoal"))));

    private static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.wares",
        ResourceLocation.withDefaultNamespace("crafting_table"));

    private static final VocationSpec VOCATION =
        new VocationSpec(CRAFTING, List.of(new Perk(QUICK_HANDS, 4)));

    private static final Workload.Haste HASTE =
        Workload.Haste.ranked(QUICK_HANDS, FolkwaysConfig::craftingHaste);

    public static double haste(Worker who) {
        return HASTE.of(who);
    }

    public static void declare(Declaring declaring) {
        declaring.vocation(VOCATION);
        declaring.refinement(Smelt.SMELTING);
        declaring.colony(ID, scope -> {
            CraftRecipes recipes = new CraftRecipes(scope.server());
            scope.closed(why -> recipes.closed());
            WaresPresence state = new WaresPresence(scope.colony(), recipes);
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
            scope.watch(STORE, new StorePresence(scope.colony(), scope.server()));
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, WaresPresence.class)
            .map(value -> (io.github.izakyl.folkways.front.api.Facing) value));
        registering.enrollment(ID, new Enrollment(
            List.of(new Delegation(STATION, new Shape.Block(Stations::isStation)),
                new Delegation(STORE, Shape.Block.fallback(
                    (level, pos) -> Containers.at(level, pos).isPresent(), Containers::anchor))),
            new Schema(List.of(FUEL)),
            List.of(PAGE)));
    }

    public static Vocation trade() {
        return Vocations.required(CRAFTING);
    }

}
