package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

// Work on the ground itself, done in a groundwork zone: each kind is a box of its own in the zone's settings, and a
// zone does every kind ticked. It clears a box out, fills one in, weeds it, fells the trees in it, and carries off
// what lies loose in it. None of it is a trade of its own: builders clear and fill, farmers weed and fell, and
// haulers carry.
public final class GroundworkContent {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "groundwork");

    public static final ResourceLocation ZONE = ResourceLocation.fromNamespaceAndPath("folkways", "ground");

    // Which work the zone does, a switch for each, and what a fill lays. A new zone clears.
    public static final String CLEAR = "clear";
    public static final String FILL = "fill";
    public static final String WEED = "weed";
    public static final String FELL = "fell";
    public static final String HAUL = "haul";
    public static final String BLOCK = "block";

    public static final ResourceLocation CLEARING = ResourceLocation.fromNamespaceAndPath("folkways", "clearing");
    public static final ResourceLocation FILLING = ResourceLocation.fromNamespaceAndPath("folkways", "filling");
    public static final ResourceLocation WEEDING = ResourceLocation.fromNamespaceAndPath("folkways", "weeding");
    public static final ResourceLocation FELLING = ResourceLocation.fromNamespaceAndPath("folkways", "felling");
    public static final ResourceLocation PICKING_UP = ResourceLocation.fromNamespaceAndPath("folkways", "picking_up");

    static final int CELL_TICKS = 10;

    // A block takes a moment more to break the harder it is, as it would by hand.
    static final int BREAK_TICKS = 10;
    static final int TICKS_PER_HARDNESS = 6;
    static final int MOST_BREAK_TICKS = 80;

    static final int FELL_TICKS_PER_LOG = 12;
    static final int FELL_TICKS_PER_LEAF = 2;

    private static final Workload.Haste HAULING_HASTE =
        Workload.Haste.ranked(Haul.QUICK_HANDS, FolkwaysConfig::haulingHaste);

    private GroundworkContent() {
    }

    public static void declare(Declaring declaring) {
        declaring.colony(ID, scope -> {
            GroundPresence state = new GroundPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, GroundPresence.class).map(Facing.class::cast));
        registering.enrollment(ID, new Enrollment(List.of(new Delegation(ZONE,
            new Shape.Volume(FolkwaysConfig.maxGroundworkCells()),
            new Schema(List.of(
                work(CLEAR, "iron_pickaxe", true),
                work(FILL, "dirt", false),
                work(WEED, "short_grass", false),
                work(FELL, "iron_axe", false),
                work(HAUL, "chest", false),
                Schema.Setting.Items.oneBlock(BLOCK, "folkways.settings.fill_block", icon("dirt"), icon("dirt")))),
            icon("iron_shovel")))));
    }

    static double haulingHaste(Worker who) {
        return HAULING_HASTE.of(who);
    }

    static Vocation hauling() {
        return Vocations.required(Vocations.HAULING);
    }

    // Work done in one go takes longer the more of it there is, but less and less so.
    static int ticksFor(int unit, double amount) {
        return Math.max(1, (int) Math.round(unit * Math.log1p(amount) / Math.log(2)));
    }

    private static Schema.Setting work(String key, String icon, boolean byDefault) {
        return new Schema.Setting.Flag(key, "folkways.settings.ground." + key, icon(icon), byDefault);
    }

    private static ResourceLocation icon(String item) {
        return ResourceLocation.withDefaultNamespace(item);
    }
}
