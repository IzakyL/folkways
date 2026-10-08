package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.front.api.FrontView;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

record Pen(UUID zone, Livestock keeps, int target, boolean shear, WorldPos anchor,
           Set<BlockPos> floor, int minY, int maxY) {

    private static final int HEADROOM = 4;

    static List<Pen> markedOut(ColonyView colony, FrontView front) {
        List<Pen> found = new ArrayList<>();
        for (ZoneView zone : front.zonesIn(colony.level())) {
            if (!zone.delegation().equals(PastureContent.PASTURE)) {
                continue;
            }
            Settings filled = zone.settings();
            Optional<Livestock> keeps = Livestock.named(filled.choice(PastureContent.ANIMAL.key()));
            if (keeps.isEmpty()) {
                continue;
            }
            found.add(of(zone, keeps.get(),
                filled.count(PastureContent.TARGET.key()), filled.flag(PastureContent.SHEAR.key())));
        }
        return List.copyOf(found);
    }

    boolean holds(Entity animal) {
        var local = WorldSpaces.relative(animal.level(), anchor.realm(), animal.position());
        if (local.isEmpty()) {
            return false;
        }
        BlockPos at = BlockPos.containing(local.get());
        if (at.getY() < minY || at.getY() > maxY + HEADROOM) {
            return false;
        }
        for (int y = minY; y <= maxY; y++) {
            if (floor.contains(new BlockPos(at.getX(), y, at.getZ()))) {
                return true;
            }
        }
        return false;
    }

    AABB reach(Level level) {
        BlockPos low = anchor.cell();
        BlockPos high = anchor.cell();
        for (BlockPos cell : floor) {
            low = new BlockPos(Math.min(low.getX(), cell.getX()), minY, Math.min(low.getZ(), cell.getZ()));
            high = new BlockPos(Math.max(high.getX(), cell.getX()), maxY,
                Math.max(high.getZ(), cell.getZ()));
        }
        AABB local = new AABB(low.getX(), minY, low.getZ(),
            high.getX() + 1.0D, maxY + 1.0D + HEADROOM, high.getZ() + 1.0D);
        if (!(anchor.realm() instanceof Realm.Frame frame)) {
            return local;
        }
        var space = WorldSpaces.frame(level, frame.structure()).orElseThrow();
        AABB world = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 point = space.toWorld(new Vec3(
                (corner & 1) == 0 ? local.minX : local.maxX,
                (corner & 2) == 0 ? local.minY : local.maxY,
                (corner & 4) == 0 ? local.minZ : local.maxZ));
            AABB one = new AABB(point, point);
            world = world == null ? one : world.minmax(one);
        }
        return world;
    }

    private static Pen of(ZoneView zone, Livestock keeps, int target,
            boolean shear) {
        Set<BlockPos> cells = zone.cells().stream().map(cell -> zone.at(cell).cell()).collect(Collectors.toSet());
        BlockPos anchor = null;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos cell : cells) {
            minY = Math.min(minY, cell.getY());
            maxY = Math.max(maxY, cell.getY());
            if (anchor == null || compare(cell, anchor) < 0) {
                anchor = cell.immutable();
            }
        }
        return new Pen(zone.id(), keeps, target, shear, zone.at(zone.cells().iterator().next()).at(anchor),
            Set.copyOf(cells), minY, maxY);
    }

    private static int compare(BlockPos left, BlockPos right) {
        int byX = Integer.compare(left.getX(), right.getX());
        if (byX != 0) {
            return byX;
        }
        int byY = Integer.compare(left.getY(), right.getY());
        return byY != 0 ? byY : Integer.compare(left.getZ(), right.getZ());
    }
}
