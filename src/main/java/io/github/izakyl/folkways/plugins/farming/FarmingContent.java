package io.github.izakyl.folkways.plugins.farming;

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
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;

public final class FarmingContent {

    public static final ResourceLocation TILLING = ResourceLocation.fromNamespaceAndPath("folkways", "tilling");

    public static final ResourceLocation PLANTING = ResourceLocation.fromNamespaceAndPath("folkways", "planting");

    public static final ResourceLocation HARVESTING = ResourceLocation.fromNamespaceAndPath("folkways", "harvesting");

    public static final ResourceLocation FELLING = ResourceLocation.fromNamespaceAndPath("folkways", "felling");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "farming");

    public static final ResourceLocation VOCATION =
        ResourceLocation.fromNamespaceAndPath("folkways", "farming");

    public static final ResourceLocation PLOT =
        ResourceLocation.fromNamespaceAndPath("folkways", "farm");

    public static final String QUICK_HANDS = "farming_quick_hands";
    public static final String WIDE_HANDS = "farming_wide_hands";
    public static final String REPLANT = "farming_replant";

    static final int CELL_TICKS = 10;

    static final int FELL_TICKS_PER_LOG = 12;

    static final int FELL_TICKS_PER_LEAF = 2;

    public static final String CROP = "crop";

    private static final VocationSpec SPEC = new VocationSpec(VOCATION,
        List.of(new Perk(QUICK_HANDS, 4), new Perk(WIDE_HANDS, 2), new Perk(REPLANT, 1)),
        List.of(ItemSpec.of(ItemTags.HOES)));

    private static final Workload.Haste HASTE =
        Workload.Haste.ranked(QUICK_HANDS, FolkwaysConfig::farmingHaste);

    public static double haste(Worker who) {
        return HASTE.of(who);
    }

    public static void declare(Declaring declaring) {
        declaring.vocation(SPEC);
        declaring.colony(ID, scope -> {
            FarmPresence state = new FarmPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, FarmPresence.class).map(Facing.class::cast));
        registering.enrollment(ID, new Enrollment(
            List.of(new Delegation(PLOT, new Shape.Volume(FolkwaysConfig.maxFarmCells()),
                new Schema(List.of(crop()))))));
    }

    public static Vocation trade() {
        return Vocations.required(VOCATION);
    }

    private static Schema.Setting crop() {
        return new Schema.Setting.Choice(CROP, "folkways.settings.crop",
            ResourceLocation.withDefaultNamespace("wheat_seeds"),
            Crop.sowable(), ResourceLocation.withDefaultNamespace("wheat"));
    }
}
