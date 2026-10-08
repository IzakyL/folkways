package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.perk.Perk;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
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

public final class PastureContent {

    public static final ResourceLocation SHEARING = ResourceLocation.fromNamespaceAndPath("folkways", "shearing");

    public static final ResourceLocation FEEDING = ResourceLocation.fromNamespaceAndPath("folkways", "feeding");

    public static final ResourceLocation CULLING = ResourceLocation.fromNamespaceAndPath("folkways", "culling");

    public static final ResourceLocation GLEANING = ResourceLocation.fromNamespaceAndPath("folkways", "gleaning");

    public static final ResourceLocation MILKING = ResourceLocation.fromNamespaceAndPath("folkways", "milking");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "pasture");

    public static final ResourceLocation PASTURE = ID;

    public static final ResourceLocation HERDING =
        ResourceLocation.fromNamespaceAndPath("folkways", "herding");

    public static final String QUICK_HANDS = "herding_quick_hands";

    public static final String BUTCHER = "herding_butcher";

    public static final Schema.Setting ANIMAL = new Schema.Setting.Choice(
        "animal",
        "folkways.settings.pasture.animal",
        ResourceLocation.withDefaultNamespace("cow_spawn_egg"),
        Livestock.types(),
        Livestock.COW.type());

    public static final Schema.Setting TARGET = new Schema.Setting.Count(
        "target",
        "folkways.settings.pasture.target",
        ResourceLocation.withDefaultNamespace("wheat"),
        0, 64, 4);

    public static final Schema.Setting SHEAR = new Schema.Setting.Flag(
        "shear",
        "folkways.settings.pasture.shear",
        ResourceLocation.withDefaultNamespace("shears"),
        true);

    private static final PageKind PAGE = PageKind.boarded(PASTURE, "folkways.page.pasture",
        ResourceLocation.withDefaultNamespace("lead"));

    private static final VocationSpec VOCATION =
        new VocationSpec(HERDING, List.of(new Perk(QUICK_HANDS, 4), new Perk(BUTCHER, 3)),
            List.of(ItemSpec.of(ResourceLocation.withDefaultNamespace("shears"))));

    private static final Workload.Haste HASTE =
        Workload.Haste.ranked(QUICK_HANDS, FolkwaysConfig::herdingHaste);

    public static double haste(Worker who) {
        return HASTE.of(who);
    }

    public static void declare(Declaring declaring) {
        declaring.vocation(VOCATION);
        declaring.colony(ID, scope -> {
            PasturePresence state = new PasturePresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, PasturePresence.class)
            .map(value -> (io.github.izakyl.folkways.front.api.Facing) value));
        registering.enrollment(ID, new Enrollment(List.of(pen()), Schema.none(), List.of(PAGE)));
    }

    public static Vocation trade() {
        return Vocations.required(HERDING);
    }

    private static Delegation pen() {
        return new Delegation(PASTURE, new Shape.Volume(FolkwaysConfig.maxPastureCells()),
            new Schema(List.of(ANIMAL, TARGET, SHEAR)));
    }
}
