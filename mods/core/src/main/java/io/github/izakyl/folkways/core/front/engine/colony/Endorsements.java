package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.front.api.Endorsed;
import io.github.izakyl.folkways.core.front.api.Endorsement;
import io.github.izakyl.folkways.core.front.api.notice.Attempt;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

public final class Endorsements {

    private static final int NEAREST_ARRIVAL = 3;
    private static final int FURTHEST_ARRIVAL = 8;
    private static final int ARRIVAL_TRIES = 24;
    private static final int ARRIVAL_RISE = 3;

    private Endorsements() {
    }

    public static Optional<Attempt> pointed(ServerLevel level, Colony colony, Player by, Body at) {
        ColonyView view = ColonyViews.anyOf(colony, level);
        Endorsement.Pointed asked = new Endorsement.Pointed(colony, view, by, at,
            colony.id().equals(at.colonyId().orElse(null)));
        return Enrollments.admission(at.kind().id())
            .flatMap(admitting -> admitting.how().answer(asked).map(answer -> apply(colony, admitting, answer)));
    }

    public static Attempt called(ServerLevel level, Colony colony, Player by,
            ResourceLocation kind) {
        ColonyView view = ColonyViews.of(colony, level).orElse(null);
        if (view == null) {
            return Attempt.refused(EndorsementRefusal.NO_ROOM_IN_THE_WORLD);
        }
        BlockPos doorstep = doorstep(level, view).orElse(null);
        if (doorstep == null) {
            return Attempt.refused(EndorsementRefusal.NO_ROOM_IN_THE_WORLD);
        }
        Endorsement.Called asked = new Endorsement.Called(colony, view, by, doorstep, kind);
        return Enrollments.admission(kind)
            .flatMap(admitting -> admitting.how().answer(asked).map(answer -> apply(colony, admitting, answer)))
            .orElseGet(() -> Attempt.refused(EndorsementRefusal.NOBODY_TAKES_ANYONE_IN));
    }

    private static Attempt apply(Colony colony, Enrollments.Admitting admitting, Endorsed answer) {
        return switch (answer) {
            case Endorsed.Joins joins -> colony.hold(admitting.owner(), Claimed.Entity.of(joins.body().mob()))
                .map(held -> Attempt.went())
                .orElseGet(() -> Attempt.refused(EndorsementRefusal.HELD_ELSEWHERE));
            case Endorsed.Leaves leaves -> {
                colony.claimOf(leaves.body().id()).ifPresent(held -> colony.release(held.id(), ClaimRelease.LET_GO));
                yield Attempt.went();
            }
            case Endorsed.Refused refused -> new Attempt.Refused(refused.why());
            case Endorsed.Pending ignored -> new Attempt.Pending();
        };
    }

    private static Optional<BlockPos> doorstep(ServerLevel level, ColonyView view) {
        return view.claims().stream()
            .sorted((one, other) -> Boolean.compare(one.what().singleCell().isEmpty(), other.what().singleCell().isEmpty()))
            .map(Claim::what).flatMap(what -> what.cells().stream())
            .flatMap(cell -> io.github.izakyl.folkways.core.world.api.WorldSpaces.storage(level, cell).stream())
            .findFirst().map(cell -> arrivalSpot(level, cell));
    }

    private static BlockPos arrivalSpot(ServerLevel level, BlockPos doorstep) {
        RandomSource random = level.getRandom();
        int span = FURTHEST_ARRIVAL - NEAREST_ARRIVAL;
        for (int attempt = 0; attempt < ARRIVAL_TRIES; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double radius = NEAREST_ARRIVAL + random.nextDouble() * span;
            int x = doorstep.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = doorstep.getZ() + (int) Math.round(Math.sin(angle) * radius);
            for (int dy = ARRIVAL_RISE; dy >= -ARRIVAL_RISE; dy--) {
                BlockPos spot = new BlockPos(x, doorstep.getY() + dy, z);
                if (level.isLoaded(spot) && level.getFluidState(spot).isEmpty()
                    && Footing.withFeetAt(level, spot)) {
                    return spot;
                }
            }
        }
        return doorstep;
    }
}
