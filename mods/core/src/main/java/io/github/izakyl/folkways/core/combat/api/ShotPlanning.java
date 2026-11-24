package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Optional;
import java.util.List;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Colony geometry and entities to avoid while selecting an automatic shot. */
public final class ShotPlanning {

    private ShotPlanning() {
    }

    public static boolean avoidsColony(ServerLevel level, Colony colony, Entity shooter,
                                       List<Vec3> path, double margin, double blastRadius) {
        return avoidsColony(level, colony, shooter, path, margin, 0, blastRadius);
    }

    public static boolean avoidsColony(ServerLevel level, Colony colony, Entity shooter,
                                       List<Vec3> path, double margin, double spread, double blastRadius) {
        if (path.size() < 2 || !Double.isFinite(blastRadius)) {
            return false;
        }
        var blocks = colonyBlocks(level, colony);
        return avoids(path, margin, spread, blastRadius, shooter == null ? null : shooter.getBoundingBox(), blocks,
            search -> level.getEntities(shooter, search, entity -> entity.isAlive()
                    && (shooter == null || !shooter.hasIndirectPassenger(entity))
                    && ColonyDamage.belongs(level, colony, entity)).stream().map(Entity::getBoundingBox).toList());
    }

    /**
     * What a script needs to keep an automatic shot off the colony, taken on the server thread: the bounds of colony
     * blocks and of colony members within {@code radius} of {@code center}, without the shooter and its passengers.
     */
    public record Snapshot(List<AABB> members, List<AABB> blocks, AABB shooter) {

        public Snapshot {
            members = List.copyOf(members);
            blocks = List.copyOf(blocks);
        }

        /** Whether a path and the blast at its end keep clear of every colony member and block in the snapshot. */
        public boolean avoids(List<Vec3> path, double margin, double blastRadius) {
            if (path.size() < 2 || !Double.isFinite(blastRadius) || blastRadius < 0) {
                return false;
            }
            return ShotPlanning.avoids(path, margin, 0, blastRadius, shooter, blocks, search -> members.stream()
                .filter(search::intersects).toList());
        }
    }

    /** The colony around {@code center}; {@code own} are the shooter's entities, left out with their passengers. */
    public static Snapshot snapshot(ServerLevel level, Colony colony, java.util.Collection<Entity> own, Vec3 center,
                                    double radius) {
        var reach = new AABB(center, center).inflate(radius);
        var members = level.getEntities((Entity) null, reach, entity -> entity.isAlive()
                && own.stream().noneMatch(shooter -> shooter == entity || shooter.hasIndirectPassenger(entity))
                && ColonyDamage.belongs(level, colony, entity)).stream().map(Entity::getBoundingBox).toList();
        var blocks = colonyBlocks(level, colony).stream().filter(reach::intersects).toList();
        return new Snapshot(members, blocks, null);
    }

    private static List<AABB> colonyBlocks(ServerLevel level, Colony colony) {
        var cells = Facilities.extentCells(level, colony.id());
        colony.claims().forEach(claim -> cells.addAll(claim.what().cells()));
        cells.addAll(colony.storesIn(level.dimension()));
        return cells.stream().filter(cell -> cell.in(level) && level.hasChunkAt(cell.block(level))
                && !level.getBlockState(cell.block(level)).isAir())
            .flatMap(cell -> blockBounds(level, cell).stream()).toList();
    }

    private static boolean avoids(List<Vec3> path, double margin, double spread, double blastRadius, AABB shooter,
                                  List<AABB> blocks, Function<AABB, List<AABB>> members) {
        double distance = 0;
        for (int i = 1; i < path.size(); i++) {
            Vec3 from = path.get(i - 1);
            Vec3 to = path.get(i);
            distance += from.distanceTo(to);
            double width = margin + distance * spread;
            AABB search = new AABB(from, to).inflate(width);
            if (members.apply(search).stream().anyMatch(member -> intersects(member.inflate(width), from, to))
                || blocks.stream().anyMatch(block -> intersects(block.inflate(width), from, to))) {
                return false;
            }
        }

        if (blastRadius <= 0) {
            return true;
        }
        Vec3 impact = path.getLast();
        AABB blast = new AABB(impact, impact).inflate(blastRadius + margin + distance * spread);
        return (shooter == null || !shooter.intersects(blast))
            && members.apply(blast).isEmpty()
            && blocks.stream().noneMatch(blast::intersects);
    }

    private static Optional<AABB> blockBounds(ServerLevel level, WorldPos cell) {
        if (cell.realm() instanceof Realm.Frame moving) {
            return WorldSpaces.frame(level, moving.structure()).map(frame -> {
                Vec3 corner = Vec3.atLowerCornerOf(cell.cell());
                Vec3 first = frame.toWorld(corner);
                AABB bounds = new AABB(first, first);
                for (int mask = 1; mask < 8; mask++) {
                    Vec3 point = frame.toWorld(corner.add(mask & 1, (mask >> 1) & 1, (mask >> 2) & 1));
                    bounds = bounds.minmax(new AABB(point, point));
                }
                return bounds;
            });
        }
        return Optional.of(new AABB(cell.cell()));
    }

    private static boolean intersects(AABB box, Vec3 from, Vec3 to) {
        return box.contains(from) || box.contains(to) || box.clip(from, to).isPresent();
    }
}
