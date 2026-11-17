package io.github.izakyl.folkways.airline;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

// World access is metered on the server thread; pattern and graph work uses only the resulting copy.
final class AirportScan {
    // Runway entrance validation may read two blocks outward and one block along the edge.
    static final int VALIDATION_RADIUS = Airport.RADIUS + 3;
    private final ServerLevel level;
    private final BlockPos tower;
    private final BoundingBox3ic frameBounds;
    private final List<TowerCoverage.Tower> towers;
    private boolean unchanged = true;
    private final Set<BlockPos> sight = new HashSet<>();
    private final Map<Long, Double> approach = new HashMap<>();
    private List<BlockPos> sightCells;
    private final Set<BlockPos> blocked = new HashSet<>();
    private int sightCursor;
    private CompletableFuture<Airport> jurisdiction;
    private final Map<BlockPos, Integer> concrete = new HashMap<>();
    private final Set<Long> loaded = new HashSet<>();
    private final List<Airport.Problem> problems = new ArrayList<>();
    private int minChunkX;
    private int minChunkZ;
    private int maxChunkX;
    private int maxChunkZ;
    private final Set<Long> visited = new HashSet<>();
    private int region;
    private int chunkX;
    private int chunkZ;
    private int section;
    private int cell = -1;
    private CompletableFuture<Survey> survey;
    private Set<BlockPos> overlaps = Set.of();
    private Airport airport;
    private List<Airport.End> ends;
    private int end;
    private int distance = 1;
    private int across;
    private boolean copied;
    private int copySteps;
    private int copiedCells;

    AirportScan(ServerLevel level, BlockPos tower) {
        this.level = level;
        this.tower = tower;
        var frame = Sable.HELPER.getContaining(level, tower);
        frameBounds = frame == null ? null : frame.getPlot().getBoundingBox();
        towers = TowerDuty.group(level, tower);
        AirportRegistry.of(level).watch(this);
        region();
    }

    private void region() {
        BlockPos center = towers.get(region).pos();
        minChunkX = (center.getX() - VALIDATION_RADIUS) >> 4;
        minChunkZ = (center.getZ() - VALIDATION_RADIUS) >> 4;
        maxChunkX = (center.getX() + VALIDATION_RADIUS) >> 4;
        maxChunkZ = (center.getZ() + VALIDATION_RADIUS) >> 4;
        chunkX = minChunkX;
        chunkZ = minChunkZ;
    }

    boolean unchanged() {
        return unchanged;
    }

    void invalidate() {
        unchanged = false;
    }

    boolean changed(BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState,
                    net.minecraft.world.level.block.state.BlockState newState) {
        if (outsideFrame(pos)) {
            return false;
        }
        boolean surface = AirportSurface.material(oldState) != AirportSurface.material(newState)
            && towers.stream().anyMatch(value -> inside(value.pos(), pos, VALIDATION_RADIUS));
        boolean visibility = sight.contains(pos)
            && TowerCoverage.blocksSight(oldState, level, pos) != TowerCoverage.blocksSight(newState, level, pos);
        Double plane = approach.get(ChunkPos.asLong(pos.getX(), pos.getZ()));
        boolean glide = plane != null && pos.getY() + 1 > plane && oldState.isAir() != newState.isAir();
        if (surface || visibility || glide) {
            invalidate();
            return true;
        }
        return false;
    }

    ServerLevel level() {
        return level;
    }

    BlockPos tower() {
        return tower;
    }

    boolean advance(Executor worker) {
        if (!copied) {
            copyStep();
            return false;
        }
        if (airport != null && survey == null) {
            return true;
        }
        if (survey == null) {
            Snapshot surface = new Snapshot(Collections.unmodifiableMap(concrete),
                Collections.unmodifiableSet(loaded), tower, towers.stream().map(TowerCoverage.Tower::pos).toList(),
                level.getMinBuildHeight(), level.getMaxBuildHeight());
            List<Airport> neighbors = AirportRegistry.of(level).neighbors(tower);
            survey = CompletableFuture.supplyAsync(() -> {
                Airport found = new AirportSurvey(surface, surface.cells().keySet(), tower, false).scan();
                Set<BlockPos> samples = new HashSet<>();
                found.footprints().forEach(area -> samples.addAll(TowerCoverage.samples(area)));
                found.ground().edges().forEach(edge -> samples.addAll(TowerCoverage.samples(edge)));
                Set<BlockPos> rays = new HashSet<>();
                for (BlockPos sample : samples) {
                    for (TowerCoverage.Tower observer : towers) {
                        if (observer.geometry(sample) == null) {
                            rays.addAll(TowerCoverage.ray(observer.pos(), sample));
                        }
                    }
                }
                return new Survey(found, AirportRegistry.overlapping(found, neighbors), List.copyOf(rays));
            }, worker);
            return false;
        }
        if (!survey.isDone()) {
            return false;
        }
        if (airport == null) {
            Survey found = survey.join();
            airport = found.airport();
            overlaps = found.overlaps();
            sightCells = found.sightCells();
            sight.addAll(sightCells);
            ends = airport.ends();
            problems.addAll(airport.flaws());
            across = ends.isEmpty() ? 0 : -ends.getFirst().runway().width() / 2;
        }
        if (sightCursor < sightCells.size()) {
            BlockPos pos = sightCells.get(sightCursor++);
            copySteps++;
            if (outsideFrame(pos)) {
                return false;
            }
            if (!level.hasChunkAt(pos)) {
                unloaded(pos);
                blocked.add(pos);
            } else {
                copiedCells++;
                if (TowerCoverage.blocksSight(level.getBlockState(pos), level, pos)) {
                    blocked.add(pos);
                }
            }
            return false;
        }
        if (end < ends.size()) {
            approachStep();
            return false;
        }
        if (jurisdiction == null) {
            Airport raw = airport.named("", List.copyOf(problems));
            Set<BlockPos> obstacles = Set.copyOf(blocked);
            jurisdiction = CompletableFuture.supplyAsync(
                () -> AirportJurisdiction.select(raw, towers, obstacles::contains, tower), worker);
            return false;
        }
        return jurisdiction.isDone();
    }

    void releaseCopy() {
        concrete.clear();
        loaded.clear();
        visited.clear();
        blocked.clear();
        survey = null;
        jurisdiction = null;
        airport = null;
        problems.clear();
        sightCells = null;
        ends = null;
    }

    boolean waiting() {
        return survey != null && !survey.isDone() || jurisdiction != null && !jurisdiction.isDone();
    }

    private record Survey(Airport airport, Set<BlockPos> overlaps, List<BlockPos> sightCells) {
    }

    Set<BlockPos> overlaps() {
        return overlaps;
    }

    Airport result() {
        return jurisdiction == null ? airport.named("", List.copyOf(problems)) : jurisdiction.join();
    }

    int copySteps() {
        return copySteps;
    }

    int copiedCells() {
        return copiedCells;
    }

    private void copyStep() {
        copySteps++;
        boolean intersects = towers.stream().anyMatch(value -> {
            int nearX = Math.max(chunkX << 4, Math.min(value.pos().getX(), (chunkX << 4) + 15));
            int nearZ = Math.max(chunkZ << 4, Math.min(value.pos().getZ(), (chunkZ << 4) + 15));
            return inside(value.pos(), new BlockPos(nearX, value.pos().getY(), nearZ), VALIDATION_RADIUS);
        });
        if (!intersects) {
            nextChunk();
            return;
        }
        if (section == 0 && cell == -1 && !visited.add(ChunkPos.asLong(chunkX, chunkZ))) {
            nextChunk();
            return;
        }
        if (frameBounds != null && ((chunkX << 4) > frameBounds.maxX()
            || (chunkX << 4) + 15 < frameBounds.minX() || (chunkZ << 4) > frameBounds.maxZ()
            || (chunkZ << 4) + 15 < frameBounds.minZ())) {
            loaded.add(ChunkPos.asLong(chunkX, chunkZ));
            nextChunk();
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            unloaded(towers.get(region).pos());
            nextChunk();
            return;
        }
        loaded.add(ChunkPos.asLong(chunkX, chunkZ));
        if (section >= chunk.getSectionsCount()) {
            nextChunk();
            return;
        }
        LevelChunkSection current = chunk.getSection(section);
        if (cell == -1) {
            if (!current.maybeHas(state -> AirportSurface.material(state) != AirportSurface.OTHER)) {
                section++;
                return;
            }
            cell = 0;
        }
        int x = cell & 15;
        int z = (cell >> 4) & 15;
        int y = cell >> 8;
        BlockPos pos = new BlockPos((chunkX << 4) + x, level.getSectionYFromSectionIndex(section) * 16 + y,
            (chunkZ << 4) + z);
        if (!outsideFrame(pos) && towers.stream().anyMatch(value -> inside(value.pos(), pos, VALIDATION_RADIUS))) {
            copiedCells++;
            var state = current.getBlockState(x, y, z);
            int material = AirportSurface.material(state);
            if (material != AirportSurface.OTHER) {
                concrete.put(pos, material);
                if (concrete.size() > Airport.MAX_SURFACE_BLOCKS) {
                    airport = new Airport("", tower, List.of(), List.of(), List.of(), GroundGraph.empty(), List.of());
                    problems.add(new Airport.Problem(AirportFlaw.SURVEY_TOO_LARGE, tower, ""));
                    copied = true;
                    return;
                }
            }
        }
        cell++;
        if (cell == 4096) {
            cell = -1;
            section++;
        }
    }

    private void nextChunk() {
        cell = -1;
        section = 0;
        chunkZ++;
        if (chunkZ > maxChunkZ) {
            chunkZ = minChunkZ;
            chunkX++;
        }
        if (chunkX > maxChunkX) {
            region++;
            copied = region >= towers.size();
            if (!copied) {
                region();
            }
        }
    }

    private void approachStep() {
        Airport.End runwayEnd = ends.get(end);
        double angle = Math.toRadians(runwayEnd.approach().outward());
        double dx = Math.sin(angle);
        double dz = -Math.cos(angle);
        BlockPos pos = BlockPos.containing(runwayEnd.threshold().getX() + 0.5 + dx * distance - dz * across,
            runwayEnd.threshold().getY(), runwayEnd.threshold().getZ() + 0.5 + dz * distance + dx * across);
        double plane = runwayEnd.threshold().getY() + 1 + (double) distance / Airport.APPROACH_SLOPE;
        approach.merge(ChunkPos.asLong(pos.getX(), pos.getZ()), plane, Math::min);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (outsideFrame(pos)) {
            // Empty space beyond this structure is not another plot or an unloaded terrain column.
        } else if (chunk == null) {
            unloaded(runwayEnd.threshold());
        } else {
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX() & 15, pos.getZ() & 15) + 1;
            if (top > plane && problems.stream().noneMatch(problem -> problem.kind() == AirportFlaw.APPROACH_OBSTRUCTED
                    && problem.pos().equals(runwayEnd.threshold()))) {
                problems.add(new Airport.Problem(AirportFlaw.APPROACH_OBSTRUCTED, runwayEnd.threshold(),
                    runwayEnd.number()));
            }
        }
        across++;
        if (across > runwayEnd.runway().width() / 2) {
            across = -runwayEnd.runway().width() / 2;
            distance++;
        }
        if (distance > Airport.APPROACH_LENGTH) {
            end++;
            distance = 1;
            if (end < ends.size()) {
                across = -ends.get(end).runway().width() / 2;
            }
        }
    }

    private boolean outsideFrame(BlockPos pos) {
        return frameBounds != null && !frameBounds.contains(pos.getX(), pos.getY(), pos.getZ());
    }

    private void unloaded(BlockPos pos) {
        if (problems.stream().noneMatch(problem -> problem.kind() == AirportFlaw.UNLOADED
                && problem.pos().equals(pos))) {
            problems.add(new Airport.Problem(AirportFlaw.UNLOADED, pos, ""));
        }
    }

    static boolean inside(BlockPos tower, BlockPos pos) {
        return inside(tower, pos, Airport.RADIUS);
    }

    private static boolean inside(BlockPos tower, BlockPos pos, int radius) {
        long dx = (long) pos.getX() - tower.getX();
        long dz = (long) pos.getZ() - tower.getZ();
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    record Snapshot(Map<BlockPos, Integer> cells, Set<Long> chunks, BlockPos tower, List<BlockPos> towers,
                    int minY, int maxY)
            implements AirportSurface {
        @Override
        public int material(BlockPos pos) {
            return cells.getOrDefault(pos, OTHER);
        }

        @Override
        public boolean loaded(BlockPos pos) {
            return pos.getY() >= minY && pos.getY() < maxY && towers.stream().anyMatch(value -> inside(value, pos, VALIDATION_RADIUS))
                && chunks.contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        }
    }
}
