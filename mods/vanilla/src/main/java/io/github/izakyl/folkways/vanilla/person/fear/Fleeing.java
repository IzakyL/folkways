package io.github.izakyl.folkways.vanilla.person.fear;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.person.walk.ResidentPathNavigation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

final class Fleeing {

    private static final int RANGE = 10;
    private static final int RISE = 4;
    private static final int NEARBY = 5;
    private static final int MIN_SEPARATION_SQUARED = 16;
    private static final double SUPPORT_RANGE = Math.sqrt(2 * RANGE * RANGE + RISE * RISE) + NEARBY + 1;

    private record Candidate(BlockPos feet, int support, double separation) {
    }

    private Fleeing() {
    }

    static Optional<BlockPos> awayFrom(Mob body, Optional<Vec3> from, Colony colony, ColonyView view) {
        if (!(body instanceof PathfinderMob walker)) {
            return Optional.empty();
        }
        Vec3 start = body.position();
        Vec3 source = from.orElse(null);
        double initialSeparation = source == null ? 0 : start.distanceToSqr(source);
        Set<BlockPos> sampled = new LinkedHashSet<>();
        if (source != null) {
            Vec3 away = new Vec3(start.x - source.x, 0, start.z - source.z).normalize().scale(8);
            // Always sample the center and both sides of the escape direction on the current level.
            sampled.add(BlockPos.containing(start.add(away)));
            sampled.add(BlockPos.containing(start.add(away.yRot((float) (Math.PI / 4)))));
            sampled.add(BlockPos.containing(start.add(away.yRot((float) (-Math.PI / 4)))));
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            Vec3 sample = source == null ? LandRandomPos.getPos(walker, RANGE, RISE)
                : DefaultRandomPos.getPosAway(walker, RANGE, RISE, source);
            if (sample != null) {
                sampled.add(BlockPos.containing(sample));
            }
        }
        List<BlockPos> safe = sampled.stream()
            .filter(feet -> !feet.equals(body.blockPosition()) && body.level().hasChunkAt(feet)
                && Footing.withFeetAt(body.level(), feet))
            .filter(feet -> source == null || separates(start, source, initialSeparation, feet))
            .toList();
        if (safe.isEmpty()) {
            return Optional.empty();
        }
        List<Vec3> facilities = facilities(colony, view, start);
        List<Vec3> members = members(body, colony, view);
        PathNavigation navigation = escapeNavigation(walker);
        return safe.stream().map(feet -> {
            Vec3 point = Vec3.atBottomCenterOf(feet);
            // A nearby facility counts once and a nearby member counts twice. Distance breaks ties.
            int support = nearby(facilities, point, start) + 2 * nearby(members, point, start);
            return new Candidate(feet, support, source == null ? 0 : point.distanceToSqr(source));
        }).sorted(Comparator.comparingInt(Candidate::support).thenComparingDouble(Candidate::separation).reversed())
            .map(Candidate::feet)
            .filter(feet -> reachable(navigation, walker.blockPosition(), feet, source, initialSeparation))
            .findFirst();
    }

    private static boolean separates(Vec3 start, Vec3 source, double initialSeparation, BlockPos feet) {
        Vec3 point = Vec3.atBottomCenterOf(feet);
        return start.distanceToSqr(point) >= MIN_SEPARATION_SQUARED
            && point.distanceToSqr(source) > initialSeparation + MIN_SEPARATION_SQUARED
            && point.subtract(start).dot(start.subtract(source)) >= 0;
    }

    private static int nearby(List<Vec3> sites, Vec3 point, Vec3 start) {
        int count = 0;
        for (Vec3 site : sites) {
            double distance = point.distanceToSqr(site);
            if (distance <= NEARBY * NEARBY && distance < start.distanceToSqr(site)) {
                count++;
            }
        }
        return count;
    }

    private static List<Vec3> facilities(Colony colony, ColonyView view, Vec3 start) {
        Set<WorldPos> sites = new HashSet<>(colony.storesIn(view.dimension()));
        colony.workshopsIn(view.dimension()).forEach(workshop -> sites.add(workshop.site().where()));
        view.claims().forEach(claim -> claim.what().singleCell().ifPresent(sites::add));
        ServerLevel level = view.level();
        return sites.stream()
            .filter(site -> WorldSpaces.storage(level, site).filter(level::hasChunkAt).isPresent())
            .flatMap(site -> WorldSpaces.world(level, site).stream())
            .filter(point -> local(start, point))
            .toList();
    }

    private static List<Vec3> members(Mob body, Colony colony, ColonyView view) {
        ServerLevel level = view.level();
        Vec3 start = body.position();
        List<Vec3> members = new ArrayList<>();
        view.residents().forEach(resident -> {
            Entity entity = level.getEntity(resident.id());
            if (entity != null && entity != body && entity.isAlive() && local(start, entity.position())) {
                members.add(entity.position());
            }
        });
        for (var player : level.players()) {
            if (player.isAlive() && !player.isSpectator() && local(start, player.position())
                && Charters.member(level.getServer(), colony, player.getUUID())) {
                members.add(player.position());
            }
        }
        return members;
    }

    private static boolean local(Vec3 start, Vec3 point) {
        return start.distanceToSqr(point) <= SUPPORT_RANGE * SUPPORT_RANGE;
    }

    private static PathNavigation escapeNavigation(PathfinderMob walker) {
        PathNavigation live = walker.getNavigation();
        if (live.getClass() != ResidentPathNavigation.class) {
            return live;
        }
        // A path query can change the current navigation target even if the escape is never taken.
        PathNavigation probe = new ResidentPathNavigation(walker, walker.level());
        probe.setMaxVisitedNodesMultiplier(FolkwaysVanillaConfig.pathSearchBudget());
        probe.setCanFloat(live.canFloat());
        return probe;
    }

    private static boolean reachable(PathNavigation navigation, BlockPos start, BlockPos feet,
            Vec3 source, double separation) {
        Path path = navigation.createPath(Set.of(feet), 0);
        if (path == null || !path.canReach()) {
            return false;
        }
        if (source != null) {
            for (int index = 0; index < path.getNodeCount(); index++) {
                BlockPos step = path.getNodePos(index);
                if (!step.equals(start) && Vec3.atBottomCenterOf(step).distanceToSqr(source) < separation) {
                    return false;
                }
            }
        }
        return true;
    }
}
