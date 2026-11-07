package io.github.izakyl.folkways.core.work.support;

import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public final class WorkStances {

    private WorkStances() {
    }

    public static boolean standingIn(LivingEntity body, Stances stances) {
        return stanceHolding(body, stances).isPresent();
    }

    public static Optional<BlockPos> stanceHolding(LivingEntity body, Stances stances) {
        if (!(stances instanceof Stances.Cells(Set<WorldPos> footings))) {
            return Optional.of(body.blockPosition());
        }
        Vec3 position = Bodies.of(body).map(who -> who.locomotion().feet(who.mob())).orElse(body.position());
        for (WorldPos footing : footings) {
            Optional<Vec3> feet = WorldSpaces.relative(body.level(), footing.realm(), position);
            if (feet.isPresent() && Reach.standingAt(feet.get(), footing.cell())) {
                return Optional.of(footing.cell());
            }
        }
        return Optional.empty();
    }

    public static Set<Stand> cellsOf(Stances stances) {
        Set<Stand> cells = new LinkedHashSet<>();
        for (WorldPos cell : stances.cells()) {
            cells.add(new Stand(cell));
        }
        return cells;
    }

}
