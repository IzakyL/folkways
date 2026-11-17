package io.github.izakyl.folkways.airline;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** A test owns its terrain and cleanup even when setup throws before it has produced a fixture. */
public final class AirlineTestArea {
    // Two complete 512-block surveys, their validation borders and fixture offsets fit between sites.
    private static final int SPACING = 2048;
    private static final Map<GameTestInfo, AirlineTestArea> ACTIVE = new IdentityHashMap<>();
    private static long sequence;
    private static int unloadedSequence;
    private final GameTestInfo info;
    private final ServerLevel level;
    private final BlockPos offset;
    private final String prefix;
    private BlockPos unloadedOffset;
    private final Map<BlockPos, BlockState> originals = new HashMap<>();
    private final List<Runnable> cleanup = new ArrayList<>();
    private final List<Entity> spawned = new ArrayList<>();
    private final Set<ChunkPos> forced = new HashSet<>();
    private boolean closed;
    private RuntimeException cleanupFailure;

    private AirlineTestArea(GameTestHelper helper) {
        info = helper.testInfo;
        level = helper.getLevel();
        int slot = 0;
        while (occupied(level, slot)) {
            slot++;
        }
        offset = offset(slot);
        prefix = "test" + ++sequence + "/";
    }

    public static void prepare(GameTestInfo info, BlockPos requested) {
        var area = of(new GameTestHelper(info));
        info.setNorthWestCorner(area.pos(0, requested == null ? 80 : requested.getY(), 0));
    }

    public static void spawned(ServerLevel level, Entity entity) {
        ACTIVE.values().stream().filter(area -> area.level == level && !area.closed
                && area.contains(entity.blockPosition())).findFirst().ifPresent(area -> area.spawned.add(entity));
    }

    public static void changing(ServerLevel level, BlockPos pos) {
        ACTIVE.values().stream().filter(area -> area.level == level && !area.closed
                && area.info.hasStarted() && !area.info.isDone() && area.contains(pos)).findFirst()
            .ifPresent(area -> area.originals.putIfAbsent(pos.immutable(), level.getBlockState(pos)));
    }

    public static void forced(ServerLevel level, int x, int z) {
        BlockPos pos = new ChunkPos(x, z).getMiddleBlockPosition(0);
        ACTIVE.values().stream().filter(area -> area.level == level && area.contains(pos)).findFirst()
            .ifPresent(area -> {
                ChunkPos chunk = new ChunkPos(x, z);
                if (!area.forced.contains(chunk) && !level.getForcedChunks().contains(chunk.toLong())) {
                    area.forced.add(chunk);
                }
            });
    }

    static AirlineTestArea of(GameTestHelper helper) {
        return ACTIVE.computeIfAbsent(helper.testInfo, ignored -> new AirlineTestArea(helper));
    }

    private static BlockPos offset(int slot) {
        return new BlockPos((slot % 8 + 1) * SPACING, 0, -(slot / 8 + 8) * SPACING);
    }

    private static boolean occupied(ServerLevel level, int slot) {
        return ACTIVE.values().stream().anyMatch(area -> area.level == level && area.offset.equals(offset(slot)));
    }

    BlockPos pos(int x, int y, int z) {
        return offset.offset(x, y, z);
    }

    /** Real unload probes must not inherit template tickets or a previous fixture's loaded chunks. */
    BlockPos unloadedPos(int x, int y, int z) {
        if (unloadedOffset == null) {
            unloadedOffset = new BlockPos(32768 + ++unloadedSequence * SPACING, 0, 0);
        }
        return unloadedOffset.offset(x, y, z);
    }

    static String testName(GameTestHelper helper, String name) {
        return of(helper).name(name);
    }

    String name(String name) {
        return name.isBlank() ? name : prefix + name;
    }


    void onClose(Runnable action) {
        cleanup.add(action);
    }

    void force(int x, int z) {
        ChunkPos chunk = new ChunkPos(x, z);
        forced(level, x, z);
        level.setChunkForced(x, z, true);
        level.getChunk(x, z);
    }

    private boolean contains(BlockPos pos) {
        return inside(pos, offset) || unloadedOffset != null && inside(pos, unloadedOffset);
    }

    private static boolean inside(BlockPos pos, BlockPos center) {
        return Math.abs((long) pos.getX() - center.getX()) < SPACING / 2
            && Math.abs((long) pos.getZ() - center.getZ()) < SPACING / 2;
    }

    public static void finish(GameTestInfo info) {
        var area = ACTIVE.get(info);
        if (area != null && info.isDone()) {
            area.close();
        }
    }

    private void clean(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            if (cleanupFailure == null) {
                cleanupFailure = error;
            } else {
                cleanupFailure.addSuppressed(error);
            }
        }
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        var towers = AirportRegistry.of(level).towers().stream().filter(this::contains).toList();
        try {
            for (int index = cleanup.size() - 1; index >= 0; index--) {
                clean(cleanup.get(index));
            }
            for (var ship : List.copyOf(SubLevelContainer.getContainer(level).getAllSubLevels())) {
                if (!ship.isRemoved() && contains(BlockPos.containing(ship.logicalPose().position().x(),
                        ship.logicalPose().position().y(), ship.logicalPose().position().z()))) {
                    clean(() -> {
                        SubLevelContainer.getContainer(level).removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
                    });
                }
            }
            var entities = new ArrayList<>(spawned);
            level.getAllEntities().forEach(entities::add);
            for (var entity : entities) {
                if (!entity.isRemoved() && (spawned.contains(entity) || contains(entity.blockPosition()))) {
                    clean(() -> {
                        entity.stopRiding();
                        entity.discard();
                    });
                }
            }
            originals.forEach((pos, state) -> clean(() -> level.setBlockAndUpdate(pos, state)));
            towers.forEach(tower -> clean(() -> {
                Airports.cancelSurvey(level, tower);
                Airports.remove(level, tower);
            }));
            forced.forEach(chunk -> clean(() -> TowerTestSite.releaseChunk(level, chunk)));
        } finally {
            ACTIVE.remove(info);
            originals.clear();
            spawned.clear();
            forced.clear();
            cleanup.clear();
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }
}
