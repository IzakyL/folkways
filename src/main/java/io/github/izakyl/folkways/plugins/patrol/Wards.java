package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.FolkwaysConfig;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

// Where patrollers have lately stood watch, and until when. Monsters that would spawn of their own
// accord within a ward's reach do not; spawners, eggs and summons are left alone. Wards are not saved:
// a world loaded again starts unwarded until its patrollers make their rounds.
public final class Wards {

    private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> HELD = new ConcurrentHashMap<>();

    private Wards() {
    }

    static void ward(ServerLevel level, BlockPos stop, long until) {
        HELD.computeIfAbsent(level.dimension(), ignored -> new ConcurrentHashMap<>())
            .merge(stop.immutable(), until, Math::max);
    }

    // The tick the ward on this stop runs out at; one already gone reads as the past.
    static long until(ServerLevel level, BlockPos stop) {
        Map<BlockPos, Long> in = HELD.get(level.dimension());
        return in == null ? Long.MIN_VALUE : in.getOrDefault(stop, Long.MIN_VALUE);
    }

    public static boolean warded(ServerLevel level, BlockPos at) {
        Map<BlockPos, Long> in = HELD.get(level.dimension());
        if (in == null || in.isEmpty()) {
            return false;
        }
        long now = level.getGameTime();
        int radius = FolkwaysConfig.patrolWardRadius();
        long reach = (long) radius * radius;
        boolean found = false;
        for (Iterator<Map.Entry<BlockPos, Long>> held = in.entrySet().iterator(); held.hasNext(); ) {
            Map.Entry<BlockPos, Long> ward = held.next();
            if (ward.getValue() <= now) {
                held.remove();
                continue;
            }
            BlockPos stop = ward.getKey();
            long dx = at.getX() - stop.getX();
            long dz = at.getZ() - stop.getZ();
            if (!found && dx * dx + dz * dz <= reach && Math.abs(at.getY() - stop.getY()) <= radius) {
                found = true;
            }
        }
        return found;
    }

    @SubscribeEvent
    public static void positionCheck(MobSpawnEvent.PositionCheck event) {
        MobSpawnType how = event.getSpawnType();
        if ((how != MobSpawnType.NATURAL && how != MobSpawnType.PATROL)
                || !(event.getEntity() instanceof Enemy)) {
            return;
        }
        ServerLevel level = event.getLevel().getLevel();
        if (warded(level, BlockPos.containing(event.getX(), event.getY(), event.getZ()))) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        HELD.clear();
    }
}
