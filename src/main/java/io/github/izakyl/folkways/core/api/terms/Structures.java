package io.github.izakyl.folkways.core.api.terms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class Structures {

    public interface Obstacles {
        List<AABB> in(ServerLevel level, AABB area);
    }

    private static final List<Obstacles> SOURCES = new CopyOnWriteArrayList<>();

    private Structures() {
    }

    public static Runnable install(Obstacles source) {
        SOURCES.add(source);
        return () -> SOURCES.remove(source);
    }

    public static List<AABB> obstacles(ServerLevel level, AABB area) {
        List<AABB> found = new ArrayList<>();
        for (Obstacles source : SOURCES) {
            found.addAll(source.in(level, area));
        }
        return List.copyOf(found);
    }

    public static boolean blocked(List<AABB> obstacles, BlockPos cell) {
        AABB body = new AABB(cell);
        return obstacles.stream().anyMatch(box -> box.intersects(body));
    }

    public static PathNavigationRegion region(Level level, BlockPos from, BlockPos to) {
        PathNavigationRegion world = new PathNavigationRegion(level, from, to);
        if (SOURCES.isEmpty() || !(level instanceof ServerLevel server)) {
            return world;
        }
        AABB searched = AABB.encapsulatingFullBlocks(from, to);
        Map<BlockPos, BlockState> blocked = new HashMap<>();
        for (AABB box : obstacles(server, searched)) {
            BlockPos min = BlockPos.containing(Math.max(box.minX, searched.minX),
                Math.max(box.minY, searched.minY), Math.max(box.minZ, searched.minZ));
            BlockPos max = new BlockPos(Mth.ceil(Math.min(box.maxX, searched.maxX)) - 1,
                Mth.ceil(Math.min(box.maxY, searched.maxY)) - 1,
                Mth.ceil(Math.min(box.maxZ, searched.maxZ)) - 1);
            for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
                blocked.put(cell.immutable(), Blocks.BARRIER.defaultBlockState());
            }
        }
        return blocked.isEmpty() ? world : new ObstructedPathRegion(level, from, world, blocked);
    }
}
