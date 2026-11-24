package io.github.izakyl.folkways.core.facility.internal;

import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Gait;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.resident.api.Locomotion;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.travel.api.Fare;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.travel.api.Passage;
import io.github.izakyl.folkways.core.world.api.Closures;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

public final class GatePassage implements Passage {

    private static final ResourceLocation ID = ResourceLocation.parse("folkways_core:gate");

    private static final Fare WALKING = Fare.flat(Gait.ticksToWalk(2));

    static Hop hop(WorldPos from, WorldPos to) {
        return new Hop(ID, new Stances.Cells(Set.of(from)), new Stances.Cells(Set.of(to)), WALKING);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public boolean through(Hop first, Hop next) {
        return false;
    }

    @Override
    public Conveyance aboard(Hop hop) {
        throw new IllegalArgumentException("a gate crossing requires a walking conveyance");
    }

    @Override
    public Conveyance aboard(Hop hop, Locomotion moving, double speed, RefusalKind lost) {
        Set<WorldPos> landing = hop.landing().cells();
        WorldPos from = hop.boarding().cells().iterator().next();
        WorldPos to = landing.iterator().next();
        WorldPos cell = from.at(from.cell().offset((to.cell().getX() - from.cell().getX()) / 2, 0,
            (to.cell().getZ() - from.cell().getZ()) / 2));
        return new Conveyance() {
            private final Conveyance walking = moving.along(speed, lost);

            @Override
            public void setOut(ServerLevel level, Mob body, Set<WorldPos> onward) {
                walking.setOut(level, body, landing);
            }

            @Override
            public Going step(ServerLevel level, Mob body, Set<WorldPos> onward) {
                if (Closures.in(level).stream().anyMatch(one -> one.impassable() && one.holds(cell))) {
                    return new Going.Failed(lost);
                }
                return walking.step(level, body, landing);
            }

            @Override
            public Optional<Doing> doing() {
                return walking.doing();
            }

            @Override
            public void release(ServerLevel level, Mob body) {
                walking.release(level, body);
            }
        };
    }
}
