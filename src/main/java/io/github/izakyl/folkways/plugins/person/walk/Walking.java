package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.resident.Locomotion;
import io.github.izakyl.folkways.core.api.resident.MobControls;
import io.github.izakyl.folkways.core.api.resident.Search;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.pathfinder.NodeEvaluator;

public final class Walking implements Locomotion {

    public static final Walking INSTANCE = new Walking();

    private static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "walking");

    private Walking() {
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Conveyance along(double speed, RefusalKind lost) {
        return new OnFoot(speed, lost);
    }

    @Override
    public NodeEvaluator newEvaluator(Search search) {
        ResidentNodeEvaluator evaluator =
            new ResidentNodeEvaluator(search.from().orElse(null));
        evaluator.setCanPassDoors(true);
        evaluator.setCanOpenDoors(true);
        return evaluator;
    }

    @Override
    public void fit(Mob body) {
        if (!(body.getNavigation() instanceof ResidentPathNavigation)) {
            MobControls.navigation(body, new ResidentPathNavigation(body, body.level()));
            MobControls.moveControl(body, new ResidentMoveControl(body));
        }
        if (!opensDoorways(body)) {
            body.goalSelector.addGoal(1, new OpenDoorwayGoal(body));
        }
    }

    private static boolean opensDoorways(Mob body) {
        for (WrappedGoal wrapped : body.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof OpenDoorwayGoal) {
                return true;
            }
        }
        return false;
    }
}
