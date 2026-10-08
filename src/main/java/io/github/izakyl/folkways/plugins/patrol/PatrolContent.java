package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Shape;
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

    // Any melee weapon will do; other mods list theirs under the common tag.
    static final ToolNeed WEAPON = new ToolNeed.Tagged(Tags.Items.MELEE_WEAPON_TOOLS);

    private static final VocationSpec SPEC = new VocationSpec(VOCATION, List.of(),
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
        registering.enrollment(ID,
            new Enrollment(List.of(new Delegation(BEAT, new Shape.Path(FolkwaysConfig.maxPatrolPoints())))));
    }

    static Vocation trade() {
        return Vocations.required(VOCATION);
    }
}
