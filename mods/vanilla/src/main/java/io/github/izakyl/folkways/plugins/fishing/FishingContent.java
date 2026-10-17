package io.github.izakyl.folkways.plugins.fishing;

import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.effect.perk.Perk;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class FishingContent {

    public static final ResourceLocation FISHING = ResourceLocation.fromNamespaceAndPath("folkways", "fishing");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "fishing");

    public static final ResourceLocation VOCATION =
        ResourceLocation.fromNamespaceAndPath("folkways", "fishing");

    public static final ResourceLocation FISHERY =
        ResourceLocation.fromNamespaceAndPath("folkways", "fish");

    private static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.fish",
        ResourceLocation.withDefaultNamespace("fishing_rod"));

    public static final String QUICK_HANDS = "fishing_quick_hands";

    private static final VocationSpec SPEC =
        new VocationSpec(VOCATION, List.of(
            new Perk(QUICK_HANDS, 4, Perk.Grant.haste(VOCATION, FolkwaysVanillaConfig::fishingHaste))),
            List.of(ItemSpec.of(ResourceLocation.withDefaultNamespace("fishing_rod"))));

    private static final Workload.Haste HASTE = Workload.Haste.of(VOCATION);

    public static double haste(Worker who) {
        return HASTE.of(who);
    }

    public static void declare(Declaring declaring) {
        declaring.vocation(SPEC);
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
