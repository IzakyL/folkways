package io.github.izakyl.folkways.airline;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class TowerTestSite implements AutoCloseable {
    final ServerLevel level;
    final AirlineTestArea area;
    private final Set<ChunkPos> forced = new HashSet<>();
    private final Map<BlockPos, BlockState> originals = new HashMap<>();
    private final List<Colony> colonies = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();
    private final List<LaborFixture> labor = new ArrayList<>();
    private final List<Runnable> enrollments = new ArrayList<>();
    private boolean closed;
    private final boolean namespaced;

    TowerTestSite(GameTestHelper helper) {
        this(helper, true);
    }

    TowerTestSite(GameTestHelper helper, boolean namespaced) {
        this.namespaced = namespaced;
        level = helper.getLevel();
        area = AirlineTestArea.of(helper);
        area.onClose(this::close);
        GameTestTicks.onEachTick(helper, () -> {
            enrollments.forEach(Runnable::run);
            labor.forEach(LaborFixture::tick);
        });
    }

    BlockPos pos(int x, int y, int z) {
        return area.pos(x, y, z);
    }

    String name(String name) {
        return namespaced ? area.name(name) : name;
    }

    Colony colony() {
        Colony colony = Colonies.mint(level.getServer());
        colonies.add(colony);
        return colony;
    }

    void load(BlockPos tower) {
        for (int x = (tower.getX() - AirportScan.VALIDATION_RADIUS) >> 4;
                x <= (tower.getX() + AirportScan.VALIDATION_RADIUS) >> 4; x++) {
            for (int z = (tower.getZ() - AirportScan.VALIDATION_RADIUS) >> 4;
                    z <= (tower.getZ() + AirportScan.VALIDATION_RADIUS) >> 4; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                if (!forced.contains(chunk) && level.setChunkForced(x, z, true)) {
                    forced.add(chunk);
                }
                level.getChunk(x, z);
            }
        }
    }

    void set(BlockPos pos, BlockState state) {
        originals.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlockAndUpdate(pos, state);
    }

    void tower(BlockPos pos, Direction scanDirection, Colony colony, String name) {
        load(pos);
        set(pos, AirlineContent.AIRPORT_TOWER.get().defaultBlockState()
            .setValue(AirportTowerBlock.FACING, scanDirection.getOpposite()));
        if (colony != null) {
            colony.hold(AirportColony.TOWER, new Claimed.Block(WorldPos.of(level, pos)))
                .orElseThrow(() -> new IllegalStateException("Tower enrollment rejected at " + pos + ": "
                    + Colonies.all(level.getServer()).stream().flatMap(owner -> owner.claims().stream())
                        .filter(holding -> holding.what().cells().contains(WorldPos.of(level, pos))).toList()));
        }
        Airports.register(level, pos);
        Airports.rename(level, pos, name(name));
        Airports.request(level, pos);
    }

    Entity staff(BlockPos tower, Colony colony) {
        Direction facing = level.getBlockState(tower).getValue(AirportTowerBlock.FACING);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                set(tower.offset(x, -1, z), Blocks.GLASS.defaultBlockState());
            }
        }
        BlockPos seat = tower.relative(facing);
        set(seat, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:white_seat")).defaultBlockState());
        var resident = PersonBody.RESIDENT.get().create(level);
        BlockPos start = seat.relative(facing.getClockWise());
        resident.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        if (!level.addFreshEntity(resident)) {
            throw new IllegalStateException("Resident could not spawn at " + start);
        }
        entities.add(resident);
        boolean[] enrolled = {false};
        Runnable enroll = () -> {
            // Reused chunks can be FULL before their entity sections become visible to the colony census.
            if (closed || enrolled[0] || level.getEntity(resident.getUUID()) != resident) {
                return;
            }
            colony.hold(PersonContent.ID, Claimed.Entity.of(resident))
                .orElseThrow(() -> new IllegalStateException("Visible resident enrollment rejected at " + start));
            resident.joinColony(colony.id());
            labor.add(new LaborFixture(colony, level));
            TowerDuty.reconcile((AirportTower) level.getBlockEntity(tower), level);
            enrolled[0] = true;
        };
        enroll.run();
        enrollments.add(enroll);
        return resident;
    }

    void pad(BlockPos center) {
        pad(center, 3);
    }

    void pad(BlockPos center, int radius) {
        Map<BlockPos, Integer> cells = new java.util.HashMap<>();
        AirportMarkings.pad(cells, center, radius * 2 + 1, false, false);
        cells.forEach((pos, material) -> set(pos, (material == AirportSurface.WHITE
            ? Blocks.WHITE_CONCRETE : Blocks.GRAY_CONCRETE).defaultBlockState()));
    }

    void unloadAll() {
        forced.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, false));
        forced.clear();
    }

    void unloadExcept(BlockPos tower) {
        forced.removeIf(chunk -> {
            if (!chunk.equals(new ChunkPos(tower))) {
                level.setChunkForced(chunk.x, chunk.z, false);
                return true;
            }
            return false;
        });
    }

    void runway(BlockPos first, Direction along, int length, int width) {
        Map<BlockPos, Integer> cells = new java.util.HashMap<>();
        AirportMarkings.runway(cells, new Runway(first, first.relative(along, length - 1), width));
        cells.forEach((pos, material) -> set(pos, (material == AirportSurface.WHITE
            ? Blocks.WHITE_CONCRETE : Blocks.GRAY_CONCRETE).defaultBlockState()));
    }







    Airport scan(BlockPos tower) {
        AirportScan scan = new AirportScan(level, tower);
        for (int step = 0; step < 8_000_000; step++) {
            if (scan.advance(Runnable::run)) {
                Airport result = scan.result();
                AirportRegistry.of(level).cache(result);
                AirportRegistry.of(level).complete(scan);
                return result;
            }
        }
        throw new AssertionError("real scan exceeded eight million work units");
    }

    static void releaseChunk(ServerLevel level, ChunkPos pos) {
        // Restored fixture terrain is disposable; GameTestServer otherwise synchronously saves it at shutdown.
        var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk != null) {
            chunk.setUnsaved(false);
        }
        level.setChunkForced(pos.x, pos.z, false);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        labor.forEach(LaborFixture::close);
        entities.forEach(entity -> {
            entity.stopRiding();
            entity.discard();
        });
        colonies.forEach(Colony::raze);
        originals.forEach((pos, state) -> {
            Airports.cancelSurvey(level, pos);
            Airports.remove(level, pos);
            level.setBlockAndUpdate(pos, state);
        });
        forced.forEach(chunk -> releaseChunk(level, chunk));
    }
}
