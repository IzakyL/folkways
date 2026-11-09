package io.github.izakyl.folkways.vanilla.patrol;

import io.github.izakyl.folkways.core.combat.api.Supplies;
import io.github.izakyl.folkways.vanilla.combat.weapon.PatrolSupplyReadout;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.colony.api.KitRules;
import io.github.izakyl.folkways.core.colony.api.PackKeeps;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.VocationGroup;
import io.github.izakyl.folkways.core.vocation.api.VocationSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.effect.Modifier;
import io.github.izakyl.folkways.core.effect.Stat;
import io.github.izakyl.folkways.core.effect.perk.Perk;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.panel.PageKind;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.combat.SpottedTargets;
import io.github.izakyl.folkways.vanilla.combat.ui.LoadoutContent;
import io.github.izakyl.folkways.vanilla.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.Tags;

public final class PatrolContent {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "patrol");

    public static final ResourceLocation VOCATION =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "patrolling");

    public static final ResourceLocation BEAT =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "patrol");

    public static final ResourceLocation PATROLLING =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "patrolling");

    public static final ResourceLocation FIGHTING =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "fighting");

    private static final PageKind PAGE =
        PageKind.boarded(ID, "folkways.page.patrol", ResourceLocation.withDefaultNamespace("iron_sword"));

    // Any melee weapon will do; other mods list theirs under the common tag.
    static final ToolNeed WEAPON = new ToolNeed.Tagged(Tags.Items.MELEE_WEAPON_TOOLS);

    public static final Stat WARD_RADIUS =
        new Stat(
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "ward_radius"), 0, 0, 16);
    public static final Stat WARD_DURATION =
        new Stat(
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "ward_duration"), 1, 1, 2);

    public static final Stat DAMAGE = stat("patrol_damage", 0, 1);
    public static final Stat DEFENCE = stat("patrol_defence", 0, 1);
    public static final Stat ALERT_RANGE = stat("patrol_alert_range", 0, 12);
    public static final Stat COMFORT = stat("patrol_comfort", 0, 10);

    private static Stat stat(String name, double base, double max) {
        return new Stat(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", name), base, 0, max);
    }

    private static double value(int rank, double first, double second, double third) {
        return new double[] {first, second, third}[rank - 1];
    }

    private static final VocationSpec SPEC = new VocationSpec(VOCATION,
        List.of(new Perk("patrolling_watchful", 3,
                rank -> List.of(Modifier.add(WARD_RADIUS, value(rank, 2, 4, 6)),
                    Modifier.add(WARD_DURATION, value(rank, .15, .3, .5)))),
            new Perk("patrolling_martial", 3,
                rank -> List.of(Modifier.add(DAMAGE, value(rank, .1, .2, .35)))),
            new Perk("patrolling_stalwart", 3,
                rank -> List.of(Modifier.add(DEFENCE, value(rank, .05, .1, .2)))),
            new Perk("patrolling_alert", 3,
                rank -> List.of(Modifier.add(ALERT_RANGE, rank * 2))),
            new Perk("patrolling_reassuring", 3,
                rank -> List.of(Modifier.add(COMFORT, value(rank, 2, 4, 6))))),
        List.of());

    private PatrolContent() {
    }

    public static void declare(Declaring declaring) {
        declaring.vocation(SPEC.ordered(100).grouped(VocationGroup.DEFENCE));
        KitRules.supplyPolicy(
            ID, (colony, body) -> !(body.mob() instanceof ResidentEntity person) || !person.hasWeaponSession());
        Equipment.register(
            ID, (colony, body) -> suppliesOf(colony).map(supplies -> supplies.armor(body)).orElse(List.of()));
        PackKeeps.add((colony, body) -> suppliesOf(colony).map(supplies -> supplies.keeps(body)).orElse(List.of()));
        declaring.colony(Supplies.ID, scope -> scope.share(new PatrolSupplyReadout(scope.colony())));
        declaring.colony(SpottedTargets.ID, scope -> {
            SpottedTargets spots = new SpottedTargets(scope.colony());
            scope.share(spots);
            scope.tick(spots::tick);
            scope.closed(why -> spots.clear());
        });
        declaring.colony(PatrolStaffing.ID, scope -> {
            PatrolStaffing staffing = new PatrolStaffing(scope.colony());
            scope.share(staffing);
            scope.tick(staffing::tick);
        });
        declaring.colony(SentryPosts.ID, scope -> {
            SentryPosts posts = new SentryPosts(scope.colony());
            scope.share(posts);
            scope.tick(posts::tick);
            scope.urges(posts::urges);
            scope.closed(why -> posts.close());
        });
        declaring.colony(ID, scope -> {
            PatrolPresence state = new PatrolPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.urges(state::urges);
            scope.closed(state::closed);
        });
    }

    public static Optional<PatrolSupplies> suppliesOf(Colony colony) {
        return colony.service(ID, PatrolPresence.class).map(PatrolPresence::supplies);
    }

    public static void enroll(Registering registering) {
        registering.facing(SentryPosts.ID,
            colony -> colony.service(SentryPosts.ID, SentryPosts.class).map(Facing.class::cast));
        registering.facing(ID, colony -> colony.service(ID, PatrolPresence.class).map(Facing.class::cast));
        registering.enrollment(
            ID,
            new Enrollment(
            List.of(
            new Delegation(
            BEAT,
            new Shape.Path(FolkwaysVanillaConfig.maxPatrolPoints()),
            ResourceLocation.withDefaultNamespace("iron_sword")),
            new Delegation(SentryPosts.ZONE, new Shape.Volume(FolkwaysVanillaConfig.maxSentryCells()),
                ResourceLocation.withDefaultNamespace("bow"))),
            new Schema(
            List.of(
            new Schema.Setting.Choice(
            "no_profile",
            "folkways.loadouts.no_profile",
            ResourceLocation.withDefaultNamespace("shield"),
            List.of(
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "flee"),
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "engage")),
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "flee")))),
            List.of(PAGE, LoadoutContent.PAGE,
                PageKind.boarded(SentryPosts.ID, "folkways.page.sentry_posts",
                ResourceLocation.withDefaultNamespace("bow")))));
    }

    static Vocation trade() {
        return Vocations.required(VOCATION);
    }
}
