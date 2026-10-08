package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.perk.Perk;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;

public final class BuildContent {

    public static final ResourceLocation BUILDING = ResourceLocation.fromNamespaceAndPath("folkways", "building");

    public static final ResourceLocation CLEARING = ResourceLocation.fromNamespaceAndPath("folkways", "clearing");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "build");

    public static final ResourceLocation VOCATION =
        ResourceLocation.fromNamespaceAndPath("folkways", "building");

    public static final String QUICK_HANDS = "building_quick_hands";

    static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.build",
        ResourceLocation.withDefaultNamespace("bricks"));

    private static final VocationSpec SPEC =
        new VocationSpec(VOCATION, List.of(new Perk(QUICK_HANDS, 4)),
            List.of(ItemSpec.of(ItemTags.PICKAXES), ItemSpec.of(ItemTags.AXES),
                ItemSpec.of(ItemTags.SHOVELS)));

    private static final Workload.Haste HASTE =
        Workload.Haste.ranked(QUICK_HANDS, FolkwaysConfig::buildingHaste);

    public static double haste(Worker who) {
        return HASTE.of(who);
    }

    public static void declare(Declaring declaring) {
        declaring.vocation(SPEC);
        declaring.colony(ID, scope -> {
            BuildPresence state = new BuildPresence(scope.colony(), scope.server());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, BuildPresence.class)
            .map(value -> (Facing) value));
        registering.enrollment(ID, new Enrollment(List.of(), Schema.none(), List.of(PAGE)));
    }

    public static Vocation trade() {
        return Vocations.required(VOCATION);
    }

    static Optional<BuildPresence> presenceIn(Optional<?> works) {
        return works.filter(BuildPresence.class::isInstance).map(BuildPresence.class::cast);
    }
}
