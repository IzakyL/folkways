package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class FireSurvey {
    @FunctionalInterface
    interface Inspection {
        void accept(ZoneView zone, BlockPos pos, BlockState state);
    }

    private final Map<UUID, Iterator<BlockPos>> cursors = new LinkedHashMap<>();
    private final Set<WorldPos> fires = new LinkedHashSet<>();
    private List<? extends ZoneView> surveyed = List.of();
    private int turn;

    int scan(ServerLevel level, List<? extends ZoneView> zones, int budget,
            Inspection inspect) {
        int read = 0;
        cursors.keySet().retainAll(zones.stream().map(ZoneView::id).toList());
        if (zones.isEmpty()) {
            fires.clear();
            return 0;
        }
        if (!surveyed.equals(zones)) {
            fires.removeIf(at -> !at.in(level) || zones.stream().noneMatch(zone -> zone.settings().flag("extinguish")
                && zone.cells().contains(at.block(level)) && zone.at(at.block(level)).equals(at)));
            cursors.clear();
            surveyed = List.copyOf(zones);
        }
        while (read < budget) {
            ZoneView zone = zones.get(Math.floorMod(turn++, zones.size()));
            if (zone.cells().isEmpty()) {
                break;
            }
            Iterator<BlockPos> cursor = cursors.computeIfAbsent(zone.id(), id -> zone.cells().iterator());
            if (!cursor.hasNext()) {
                cursor = zone.cells().iterator();
                cursors.put(zone.id(), cursor);
            }
            BlockPos pos = cursor.next();
            read++;
            WorldPos at = zone.at(pos);
            if (!level.isLoaded(pos)) {
                fires.remove(at);
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (fire(state) && zones.stream().anyMatch(other ->
                    other.settings().flag("extinguish") && other.cells().contains(pos) && other.at(pos).equals(at))) {
                if (fires.size() < 4096) {
                    fires.add(at);
                }
            } else {
                fires.remove(at);
            }
            inspect.accept(zone, pos, state);
        }
        return read;
    }

    Set<WorldPos> fires() {
        return Collections.unmodifiableSet(fires);
    }

    static boolean fire(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
    }
}
