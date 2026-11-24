package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Optional;
import java.util.Set;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Whether a rider stands in a ride's boarding or landing stances; the API's own copy of the work-side check. */
final class RideStances {

    private RideStances() {
    }

    static boolean standingIn(LivingEntity body, Stances stances) {
        if (!(stances instanceof Stances.Cells(Set<WorldPos> footings))) {
            return true;
        }
        Vec3 position = Bodies.of(body).map(who -> who.locomotion().feet(who.mob())).orElse(body.position());
        for (WorldPos footing : footings) {
            Optional<Vec3> feet = WorldSpaces.relative(body.level(), footing.realm(), position);
            if (feet.isPresent() && Reach.standingAt(feet.get(), footing.cell())) {
                return true;
            }
        }
        return false;
    }

}
