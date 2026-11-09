package io.github.izakyl.folkways.vanilla.fishing;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.VocationGroup;
import io.github.izakyl.folkways.core.vocation.api.VocationSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.effect.Modifier;
import io.github.izakyl.folkways.core.effect.Scope;
import io.github.izakyl.folkways.core.effect.Stat;
import io.github.izakyl.folkways.core.effect.Stats;
import io.github.izakyl.folkways.core.effect.perk.Perk;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.panel.PageKind;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class FishingContent {

    public static final ResourceLocation FISHING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "fishing");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "fishing");

    public static final ResourceLocation VOCATION =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "fishing");

    public static final ResourceLocation FISHERY =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "fish");

    private static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.fish",
        ResourceLocation.withDefaultNamespace("fishing_rod")).tools(VOCATION);

    public static final String QUICK_HANDS = "fishing_quick_hands";

    public static final Stat EXTRA_FISH =
        new Stat(
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "extra_fish"), 0, 0, 1);

    public static final String SCHOOL = "fishing_school";
    public static final String TREASURE = "fishing_treasure";
    public static final String WATER_SENSE = "fishing_water_sense";
    public static final String ROD_CARE = "fishing_rod_care";
    public static final Stat SCHOOL_SIZE = stat("school_size", 2);
    public static final Stat TREASURE_CHANCE = stat("fishing_treasure", 1);
    public static final Stat RARE_FISH = stat("rare_fish", 3);

    private static Stat stat(String name, double max) {
        return new Stat(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", name), 0, 0, max);
    }

    private static final VocationSpec SPEC = new VocationSpec(VOCATION, List.of(
        new Perk(QUICK_HANDS, 3, Perk.Grant.haste(VOCATION, FolkwaysVanillaConfig::fishingHaste)),
        new Perk(SCHOOL, 3, rank -> List.of(
            Modifier.add(EXTRA_FISH, new double[] {.15, .25, .4}[rank - 1]),
            Modifier.add(SCHOOL_SIZE, rank == 3 ? 2 : 1))),
        new Perk(TREASURE, 3, Perk.Grant.added(TREASURE_CHANCE, () -> List.of(.02, .04, .06))),
        new Perk(WATER_SENSE, 3, Perk.Grant.perRank(RARE_FISH, 1)),
        new Perk(ROD_CARE, 3, rank -> List.of(new Modifier.Multiply(Stats.TOOL_WEAR,
            new double[] {.85, .7, .55}[rank - 1], Scope.trade(VOCATION))))),
        List.of(ItemSpec.of(ResourceLocation.withDefaultNamespace("fishing_rod"))));

    private static final Workload.Haste HASTE = Workload.Haste.of(VOCATION);

    public static double haste(Worker who) {
        return HASTE.of(who);
    }

    public static void declare(Declaring declaring) {
        declaring.vocation(SPEC.ordered(300).grouped(VocationGroup.GATHERING));
        declaring.colony(ID, scope -> {
            FishingPresence state = new FishingPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, FishingPresence.class).map(Facing.class::cast));
        registering.enrollment(ID,
            new Enrollment(List.of(new Delegation(FISHERY, new Shape.Volume(cap()),
                ResourceLocation.withDefaultNamespace("fishing_rod"))), Schema.none(), List.of(PAGE)));
    }

    static Vocation trade() {
        return Vocations.required(VOCATION);
    }

    private static int cap() {
        return (int) Math.min(Integer.MAX_VALUE, FolkwaysVanillaConfig.maxFishCells());
    }
}
