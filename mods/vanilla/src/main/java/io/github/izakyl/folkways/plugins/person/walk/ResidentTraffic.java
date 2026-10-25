package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

final class ResidentTraffic {
    private static final double STOP_GAP = 0.55D;
    private static final double EASE_GAP = 1.0D;
    private static final int MOVING_TICKS = 20;

    private final Mob body;
    private final List<ResidentEntity> nearby = new ArrayList<>();
    private Vec3 previous;
    private long advanced = Long.MIN_VALUE;
    private long scanAt;
    private long yielded = Long.MIN_VALUE;

    ResidentTraffic(Mob body) {
        this.body = body;
    }

    void tick() {
        Vec3 here = body.position();
        Vec3 direction = direction(body.getNavigation().getPath());
        if (previous != null && here.subtract(previous).dot(direction) > 0.0001D) {
            advanced = body.level().getGameTime();
        }
        previous = here;
    }

    boolean yielding() {
        return yielded >= body.level().getGameTime() - 1 && !body.getNavigation().isDone();
    }

    void clear() {
        yielded = Long.MIN_VALUE;
    }

    double speed(Vec3 direction) {
        long now = body.level().getGameTime();
        if (now >= scanAt) {
            scanAt = now + 5;
            nearby.clear();
            body.level().getEntities(EntityTypeTest.forClass(ResidentEntity.class),
                body.getBoundingBox().expandTowards(direction.scale(3.0D)).inflate(0.5D, 0.5D, 0.5D),
                other -> other != body && other.isAlive(), nearby, 8);
        }
        double scale = 1.0D;
        for (ResidentEntity other : nearby) {
            if (!other.isAlive() || other.isPassenger() || other.getNavigation().isDone()
                    || !(other.getMoveControl() instanceof ResidentMoveControl control)
                    || !control.traffic.moving(now)
                    || direction.dot(direction(other.getNavigation().getPath())) < 0.8D) {
                continue;
            }
            Vec3 apart = other.position().subtract(body.position());
            double ahead = apart.dot(direction);
            double across = Math.abs(apart.x * direction.z - apart.z * direction.x);
            if (ahead <= 0 || ahead > 3.0D || across > 0.55D || Math.abs(apart.y) > 0.6D) {
                continue;
            }
            double gap = ahead - (body.getBbWidth() + other.getBbWidth()) / 2.0D;
            scale = Math.min(scale, Mth.clamp((gap - STOP_GAP) / (EASE_GAP - STOP_GAP), 0.0D, 1.0D));
        }
        if (scale < 1.0D) {
            yielded = now;
        }
        return scale;
    }

    // A path or a facing alone cannot keep a queue waiting behind somebody who has stopped working or walking.
    private boolean moving(long now) {
        return advanced >= now - MOVING_TICKS;
    }

    static Vec3 direction(Path path) {
        if (path == null || path.isDone()) {
            return Vec3.ZERO;
        }
        int next = path.getNextNodeIndex();
        int from = next > 0 ? next - 1 : next;
        int to = next > 0 ? next : Math.min(next + 1, path.getNodeCount() - 1);
        var a = path.getNodePos(from);
        var b = path.getNodePos(to);
        return new Vec3(b.getX() - a.getX(), 0, b.getZ() - a.getZ()).normalize();
    }
}
