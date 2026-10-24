package io.github.izakyl.folkways.warfare;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class WarfareSafety {
    private WarfareSafety() { }

    static boolean segment(ServerLevel level, ResidentEntity body, Vec3 from, Vec3 to, double margin) {
        AABB corridor = new AABB(from, to).inflate(margin);
        if (!loaded(level, corridor.getMinPosition(), corridor.getMaxPosition())) { return false; }
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, corridor,
                entity -> entity != body && friendly(entity, body))) {
            AABB box = other.getBoundingBox().inflate(margin);
            if (box.contains(from) || box.clip(from, to).isPresent()) { return false; }
        }
        if (owned(level, body).stream().filter(cell -> cell.in(level)).anyMatch(cell -> {
            BlockPos pos = cell.block(level);
            if (level.getBlockState(pos).isAir()) { return false; }
            AABB box = new AABB(pos).inflate(margin);
            return box.contains(from) || box.clip(from, to).isPresent();
        })) { return false; }
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, body)).getType() == HitResult.Type.MISS;
    }

    static boolean impact(ServerLevel level, ResidentEntity body, Vec3 at, double entityRadius, double blockRadius) {
        AABB area = new AABB(at, at).inflate(entityRadius);
        AABB blocks = new AABB(at, at).inflate(blockRadius);
        if (!loaded(level, area.getMinPosition(), area.getMaxPosition())) { return false; }
        if (!level.getEntitiesOfClass(LivingEntity.class, area,
                entity -> entity != body && friendly(entity, body)).isEmpty() || body.getBoundingBox().intersects(area)) { return false; }
        return body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id)).isPresent()
            && (blockRadius <= 0 || owned(level, body).stream().filter(cell -> cell.in(level)).noneMatch(cell -> WorldSpaces.world(level, cell)
                .filter(point -> blocks.inflate(1).contains(point) && !level.getBlockState(cell.block(level)).isAir()).isPresent()));
    }

    private static List<WorldPos> owned(ServerLevel level, ResidentEntity body) {
        var owned = new ArrayList<WorldPos>();
        body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id)).ifPresent(colony -> {
            colony.holdings().forEach(holding -> owned.addAll(holding.what().cells()));
            owned.addAll(colony.storesIn(level.dimension()));
        });
        return owned;
    }

    private static boolean friendly(LivingEntity other, ResidentEntity body) {
        return !(other instanceof Mob mob) || !Threats.hostile(mob, body);
    }

    private static boolean loaded(ServerLevel level, Vec3 from, Vec3 to) {
        AABB bounds = new AABB(from, to);
        // A high arc may cross the build ceiling without leaving its loaded horizontal chunks.
        return level.hasChunksAt(BlockPos.containing(bounds.minX, level.getMinBuildHeight(), bounds.minZ),
            BlockPos.containing(bounds.maxX, level.getMinBuildHeight(), bounds.maxZ));
    }
}
