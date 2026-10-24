package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.Footing;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/** Construction may move a resident within its closed work area; travel beyond it remains a walk. */
public final class SiteTravel {

    private final Set<Closures.Closed> departed = new HashSet<>();

    public SiteTravel() {
    }

    public void reset() {
        departed.clear();
    }

    public boolean toward(ServerLevel level, Mob body, Set<BlockPos> goals) {
        if (goals.isEmpty() || body.level() != level || body.isPassenger() || body.isSleeping()) {
            return false;
        }
        for (Closures.Closed site : Closures.in(level)) {
            if (!site.holds(body.blockPosition())) {
                continue;
            }
            Set<BlockPos> inside = goals.stream().filter(site::holds)
                .collect(java.util.stream.Collectors.toSet());
            if (inside.isEmpty() && departed.contains(site)) {
                continue;
            }
            // A departing resident only crosses the changing interior. The destination outside the site is
            // never a teleport target, even when it belongs to another construction site.
            Stream<BlockPos> candidates = inside.isEmpty() ? boundary(site.box()) : inside.stream();
            BlockPos landing = candidates.filter(at -> safe(level, body, at))
                .min(Comparator.comparingDouble((BlockPos at) -> inside.isEmpty()
                        ? goals.stream().mapToDouble(at::distSqr).min().orElseThrow()
                        : body.blockPosition().distSqr(at))
                    .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ)).orElse(null);
            if (landing != null) {
                boolean moved = place(level, body, landing);
                // Once at this exit, allow native navigation to detour along or back into the site.
                // Re-selecting the closest boundary every tick would keep pulling that detour back.
                if (inside.isEmpty() && (moved || body.blockPosition().equals(landing))) {
                    departed.add(site);
                }
                return moved;
            }
        }
        return false;
    }

    static boolean place(ServerLevel level, Mob body, BlockPos feet) {
        if (body.level() != level || body.isPassenger() || body.isSleeping()
            || body.blockPosition().equals(feet) || !safe(level, body, feet)
            || Closures.in(level).stream().noneMatch(site ->
                site.holds(body.blockPosition()) && site.holds(feet))) {
            return false;
        }
        body.getNavigation().stop();
        body.teleportTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
        body.setDeltaMovement(Vec3.ZERO);
        body.fallDistance = 0;
        return true;
    }

    private static boolean safe(ServerLevel level, Mob body, BlockPos feet) {
        return level.isLoaded(feet) && level.isLoaded(feet.above()) && level.isLoaded(feet.below())
            && level.getWorldBorder().isWithinBounds(feet) && Footing.withFeetAt(level, feet)
            && level.noCollision(body, body.getBoundingBox().move(
                feet.getX() + 0.5D - body.getX(), feet.getY() - body.getY(), feet.getZ() + 0.5D - body.getZ()));
    }

    private static Stream<BlockPos> boundary(BoundingBox box) {
        return Stream.of(
            BlockPos.betweenClosedStream(box.minX(), box.minY(), box.minZ(), box.minX(), box.maxY(), box.maxZ()),
            BlockPos.betweenClosedStream(box.maxX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()),
            BlockPos.betweenClosedStream(box.minX(), box.minY(), box.minZ(), box.maxX(), box.minY(), box.maxZ()),
            BlockPos.betweenClosedStream(box.minX(), box.maxY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()),
            BlockPos.betweenClosedStream(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.minZ()),
            BlockPos.betweenClosedStream(box.minX(), box.minY(), box.maxZ(), box.maxX(), box.maxY(), box.maxZ()))
            .flatMap(stream -> stream.map(BlockPos::immutable)).distinct();
    }
}
