package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.effect.Modifier;
import io.github.izakyl.folkways.effect.Stat;
import io.github.izakyl.folkways.effect.perk.Perk;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.Tags;

public final class PatrolContent {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "patrol");

    public static final ResourceLocation VOCATION =
        ResourceLocation.fromNamespaceAndPath("folkways", "patrolling");

    public static final ResourceLocation BEAT =
        ResourceLocation.fromNamespaceAndPath("folkways", "patrol");

    public static final ResourceLocation PATROLLING =
        ResourceLocation.fromNamespaceAndPath("folkways", "patrolling");

    public static final ResourceLocation FIGHTING =
        ResourceLocation.fromNamespaceAndPath("folkways", "fighting");

    private static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.patrol",
        ResourceLocation.withDefaultNamespace("iron_sword"));

    // Any melee weapon will do; other mods list theirs under the common tag.
    static final ToolNeed WEAPON = new ToolNeed.Tagged(Tags.Items.MELEE_WEAPON_TOOLS);

    public static final Stat WARD_RADIUS =
        new Stat(
            ResourceLocation.fromNamespaceAndPath("folkways", "ward_radius"), 0, 0, 16);
    public static final Stat WARD_DURATION =
        new Stat(
            ResourceLocation.fromNamespaceAndPath("folkways", "ward_duration"), 1, 1, 2);

    public static final Stat DAMAGE = stat("patrol_damage", 0, 1);
    public static final Stat DEFENCE = stat("patrol_defence", 0, 1);
    public static final Stat ALERT_RANGE = stat("patrol_alert_range", 0, 12);
    public static final Stat COMFORT = stat("patrol_comfort", 0, 10);

    private static Stat stat(String name, double base, double max) {
        return new Stat(ResourceLocation.fromNamespaceAndPath("folkways", name), base, 0, max);
    }

    private static double value(int rank, double first, double second, double third) {
        return new double[] {first, second, third}[rank - 1];
    }

    private static final VocationSpec SPEC = new VocationSpec(VOCATION,
        List.of(new Perk("patrolling_watchful", 3,
                rank -> List.of(Modifier.add(WARD_RADIUS, value(rank, 3, 6, 10)),
                    Modifier.add(WARD_DURATION, value(rank, .25, .5, 1)))),
            new Perk("patrolling_martial", 3,
                rank -> List.of(Modifier.add(DAMAGE, value(rank, .2, .4, .7)))),
            new Perk("patrolling_stalwart", 3,
                rank -> List.of(Modifier.add(DEFENCE, value(rank, .1, .2, .35)))),
            new Perk("patrolling_alert", 3,
                rank -> List.of(Modifier.add(ALERT_RANGE, rank * 4))),
            new Perk("patrolling_reassuring", 3,
                rank -> List.of(Modifier.add(COMFORT, value(rank, 3, 6, 10))))),
        List.of(ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS)));

    private PatrolContent() {
    }

    public static void declare(Declaring declaring) {
        declaring.vocation(SPEC);
        declaring.colony(ID, scope -> {
            PatrolPresence state = new PatrolPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.urges(state::urges);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, PatrolPresence.class).map(Facing.class::cast));
        registering.enrollment(ID,
            new Enrollment(List.of(new Delegation(BEAT, new Shape.Path(FolkwaysVanillaConfig.maxPatrolPoints()),
                ResourceLocation.withDefaultNamespace("iron_sword"))), Schema.none(), List.of(PAGE)));
    }

    static Vocation trade() {
        return Vocations.required(VOCATION);
    }
}
