package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.effect.Flag;
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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.state.BlockState;

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

    private static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.farm",
        ResourceLocation.withDefaultNamespace("wheat"));

    public static final String QUICK_HANDS = "farming_quick_hands";
    public static final String REPLANT = "farming_replant";

    // Whether a crop cut whole is sown again from its own seed as it is cut.
    public static final Flag REPLANTS = new Flag(ResourceLocation.fromNamespaceAndPath("folkways", "replants"));

    static final int CELL_TICKS = 10;

    static final int FELL_TICKS_PER_LOG = 12;

    static final int FELL_TICKS_PER_LEAF = 2;

    public static final String CROP = "crop";

    // Whether residents pull up the grass and flowers standing on ground the plot wants tilled.
    public static final Schema.Setting CLEAR_GRASS = new Schema.Setting.Flag("clear_grass",
        "folkways.settings.farm.clear_grass", ResourceLocation.withDefaultNamespace("short_grass"), true);

    public static final Schema.Setting CLEAR_FLOWERS = new Schema.Setting.Flag("clear_flowers",
        "folkways.settings.farm.clear_flowers", ResourceLocation.withDefaultNamespace("poppy"), true);

    // Work done in one go takes longer the more of it there is, but less and less so: one unit of it takes the
    // unit's ticks, and every doubling after that as many again.
    static int ticksFor(int unit, double amount) {
        return Math.max(1, (int) Math.round(unit * Math.log1p(amount) / Math.log(2)));
    }

    private static final VocationSpec SPEC = new VocationSpec(VOCATION,
        List.of(
            new Perk(QUICK_HANDS, 4, Perk.Grant.haste(VOCATION, FolkwaysVanillaConfig::farmingHaste)),
            new Perk(REPLANT, 1, Perk.Grant.flag(REPLANTS))),
        List.of(ItemSpec.of(ItemTags.HOES)));

    private static final Workload.Haste HASTE = Workload.Haste.of(VOCATION);

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
            List.of(new Delegation(PLOT, new Shape.Volume(FolkwaysVanillaConfig.maxFarmCells()),
                new Schema(List.of(crop(), CLEAR_GRASS, CLEAR_FLOWERS)),
                ResourceLocation.withDefaultNamespace("wheat"))),
            Schema.none(), List.of(PAGE)));
    }

    public static Vocation trade() {
        return Vocations.required(VOCATION);
    }

    // What a farmer pulls up before tilling, for work that weeds ground it never tills: the grass, the flowers, or both.
    public static boolean weed(BlockState state, boolean grass, boolean flowers) {
        return new Tillage.Clearing(grass, flowers).clears(state);
    }

    private static Schema.Setting crop() {
        return new Schema.Setting.Choice(CROP, "folkways.settings.crop",
            ResourceLocation.withDefaultNamespace("wheat_seeds"),
            Crop.sowable(), ResourceLocation.withDefaultNamespace("wheat"));
    }
}
