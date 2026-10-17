package io.github.izakyl.folkways.core.shell.structure;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import io.github.izakyl.folkways.core.api.terms.Structures;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CreateObstacles implements Structures.Obstacles {

    public static final String CREATE_MOD_ID = "create";

    @Override
    public List<AABB> in(ServerLevel level, AABB area) {
        List<AABB> found = new ArrayList<>();
        for (AbstractContraptionEntity entity : level.getEntitiesOfClass(AbstractContraptionEntity.class, area)) {
            if (entity.getContraption() == null) {
                continue;
            }
            for (var entry : entity.getContraption().getBlocks().entrySet()) {
                BlockPos local = entry.getKey();
                for (AABB shape : entry.getValue().state()
                        .getCollisionShape(EmptyBlockGetter.INSTANCE, local).toAabbs()) {
                    AABB box = bounds(entity, shape.move(local));
                    if (box.intersects(area)) {
                        found.add(box);
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    private static AABB bounds(AbstractContraptionEntity entity, AABB local) {
        AABB bounds = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 point = entity.toGlobalVector(new Vec3(
                (corner & 1) == 0 ? local.minX : local.maxX,
                (corner & 2) == 0 ? local.minY : local.maxY,
                (corner & 4) == 0 ? local.minZ : local.maxZ), 1.0F);
            AABB one = new AABB(point, point);
            bounds = bounds == null ? one : bounds.minmax(one);
        }
        return bounds;
    }
}
