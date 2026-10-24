package io.github.izakyl.folkways.plugins.groundwork.plan;

import io.github.izakyl.folkways.core.api.ground.BlockChanges;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Box;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Budget;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Capability;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Cell;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Problem;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.State;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Voxel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/** Main-thread, bounded bridge. Pure construction classes never retain a Level or invoke block callbacks. */
public final class ConstructionSnapshot {

    public enum Status {
        PENDING, READY, VALID, INVALID, WAITING, UNSUPPORTED
    }

    // Deliberately explicit. Equal collision shapes, namespace, and solidity do not establish this domain.
    private static final Set<Block> FULL = Set.of(Blocks.STONE, Blocks.DIRT, Blocks.COARSE_DIRT,
        Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
        Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE,
        Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.CALCITE, Blocks.TUFF, Blocks.BEDROCK,
        Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
        Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.MANGROVE_PLANKS, Blocks.CHERRY_PLANKS,
        Blocks.BAMBOO_PLANKS, Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS, Blocks.WHITE_WOOL,
        Blocks.GLASS);

    private ConstructionSnapshot() {
    }

    public static Voxel project(BlockState state) {
        if (state.is(Blocks.AIR) || state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR)) return Voxel.AIR;
        return FULL.contains(state.getBlock()) ? Voxel.FULL : Voxel.UNKNOWN;
    }

    public static boolean supportedMaterial(BlockState state) {
        return FULL.contains(state.getBlock());
    }

    private static BlockPos position(Cell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    private static BoundingBox bounds(Box box) {
        return new BoundingBox(box.min().x(), box.min().y(), box.min().z(),
            box.max().x(), box.max().y(), box.max().z());
    }

    private static void mainThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("construction world reads require the server thread");
        }
    }

    private static long chunkKey(Cell cell) {
        return ChunkPos.asLong(cell.x() >> 4, cell.z() >> 4);
    }

    private static LevelChunk chunk(ServerLevel level, long key) {
        return level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
    }

    private static boolean environment(ServerLevel level, Box box, long keepouts, Map<Long, LevelChunk> chunks) {
        Keepouts.refresh(level);
        if (Keepouts.revision() != keepouts || !Structures.obstacles(level,
                AABB.encapsulatingFullBlocks(position(box.min()), position(box.max()))).isEmpty()) return false;
        // A hard 256-chunk domain cap bounds this atomic freshness check; it never loads a chunk.
        return chunks.entrySet().stream().allMatch(entry -> chunk(level, entry.getKey()) == entry.getValue());
    }

    // Known farmland moisture never changes this domain's UNKNOWN barrier. No other unknown state is equated.
    private static boolean sameUnknown(BlockState earlier, BlockState current) {
        return earlier == current || earlier != null && earlier.is(Blocks.FARMLAND) && current.is(Blocks.FARMLAND)
            && earlier.setValue(FarmBlock.MOISTURE, 7) == current.setValue(FarmBlock.MOISTURE, 7);
    }

    public static final class Captured {
        private final Problem problem;
        private final String fingerprint;
        private final Map<Cell, BlockState> rawStates;
        private final Set<Cell> unloaded;
        private final BlockState material;
        private final Map<Long, LevelChunk> chunks;
        private final long keepouts;
        private final Map<String, Object> facts;

        private Captured(Problem problem, String fingerprint, Map<Cell, BlockState> rawStates,
                Set<Cell> unloaded, BlockState material, long coherentTick, long maxReadNanos,
                Map<Long, LevelChunk> chunks, long keepouts) {
            this.problem = problem;
            this.fingerprint = fingerprint;
            this.rawStates = Map.copyOf(rawStates);
            this.unloaded = Set.copyOf(unloaded);
            this.material = material;
            this.chunks = Map.copyOf(chunks);
            this.keepouts = keepouts;
            facts = Map.of("domain", "AIR_FULL_UNKNOWN_CARDINAL_WALK_V1", "coherentAtWorldTick", coherentTick,
                "unloadedCells", unloaded.size(), "unknownCells", problem.baseVoxels().values().stream()
                    .filter(value -> value == Voxel.UNKNOWN).count(),
                "maxAtomicReadNanos", maxReadNanos, "temporaryFacilityDomainEmpty", true,
                "nativeEvaluatorUsed", false, "safeAnchorOrigin", "caller-declared real external supply approaches");
        }

        public Problem problem() { return problem; }
        public String fingerprint() { return fingerprint; }
        public Map<Cell, BlockState> rawStates() { return rawStates; }
        public BlockState material() { return material; }
        public Map<String, Object> facts() { return facts; }

        public Validation validate(ServerLevel level, State expected, Set<Cell> dependencies) {
            return new Validation(level, this, expected, dependencies);
        }
    }

    public static final class Capture implements AutoCloseable {
        private final ServerLevel level;
        private final Box box;
        private final List<Cell> targets;
        private final Set<Cell> protectedCells;
        private final Set<Cell> safeCells;
        private final Cell start;
        private final int materials;
        private final Capability capability;
        private final BlockState material;
        private final Map<Cell, BlockState> raw = new HashMap<>();
        private final Map<Cell, Voxel> voxels = new HashMap<>();
        private final Set<Cell> unloaded = new HashSet<>();
        private BlockChanges.Heard heard;
        private final Set<Cell> dirty = new LinkedHashSet<>();
        private final Map<Long, LevelChunk> chunks = new HashMap<>();
        private Status status = Status.PENDING;
        private String reason = "collecting bounded world input";
        private long cell;
        private long keepouts;
        private boolean modelChanged;
        private long coherentTick;
        private long maxReadNanos;
        private int stage;
        private int target;
        private int safe;
        private final List<Cell> anchors;
        private Problem problem;
        private ConstructionRules.Fingerprint fingerprint;
        private Captured result;

        public Capture(ServerLevel level, Box box, List<Cell> targets, Set<Cell> protectedCells,
                Set<Cell> safeCells, Cell start, int materialCount, Capability capability, BlockState material) {
            mainThread(level);
            this.level = level;
            this.box = box;
            this.targets = List.copyOf(targets);
            this.protectedCells = Set.copyOf(protectedCells);
            this.safeCells = Set.copyOf(safeCells);
            this.start = start;
            this.materials = materialCount;
            this.capability = capability;
            this.material = material;
            anchors = new ArrayList<>(safeCells);
            long chunkCount = ((long) (box.max().x() >> 4) - (box.min().x() >> 4) + 1L)
                * ((long) (box.max().z() >> 4) - (box.min().z() >> 4) + 1L);
            if (box.volume() > Construction.MAX_CELLS || targets.size() > Construction.MAX_TARGETS
                    || chunkCount > 256L || !supportedMaterial(material) || materialCount < 0
                    || !box.contains(start) || targets.stream().anyMatch(at -> !box.contains(at))
                    || protectedCells.stream().anyMatch(at -> !box.contains(at))
                    || safeCells.stream().anyMatch(at -> !box.contains(at) || targets.contains(at))
                    || new HashSet<>(targets).size() != targets.size()) {
                status = Status.UNSUPPORTED;
                reason = "input volume or material is outside the stable full-cube domain";
            }
        }

        public Status advance(Budget budget) {
            mainThread(level);
            while (status == Status.PENDING && !budget.exhausted()) {
                if (stage == 0) {
                    if (!budget.tryStep()) break;
                    long began = System.nanoTime();
                    Keepouts.refresh(level);
                    keepouts = Keepouts.revision();
                    if (!(WorldPos.of(level, position(start)).realm() instanceof Realm.Dimension)
                            || !Structures.obstacles(level, AABB.encapsulatingFullBlocks(position(box.min()),
                                position(box.max()))).isEmpty()) {
                        fail(Status.UNSUPPORTED, "moving structures are outside the static voxel domain");
                        break;
                    }
                    heard = BlockChanges.listen(level, bounds(box), changed -> dirty.add(
                        new Cell(changed.getX(), changed.getY(), changed.getZ())));
                    maxReadNanos = Math.max(maxReadNanos, System.nanoTime() - began);
                    stage++;
                } else if (stage == 1 || stage == 2) {
                    if (cell < box.volume()) {
                        if (!budget.tryStep()) break;
                        Cell at = ConstructionRules.cellAt(box, cell++);
                        read(at);
                    } else {
                        if (!budget.tryStep()) break;
                        if (stage == 1) {
                            cell = 0L;
                            stage = 2;
                        } else if (!dirty.isEmpty()) {
                            read(dirty.iterator().next());
                        } else {
                            if (!environment(level, box, keepouts, chunks)) {
                                fail(Status.WAITING, "chunk or dynamic environment changed during capture");
                                continue;
                            }
                            coherentTick = level.getGameTime();
                            problem = new Problem(box, voxels, targets, protectedCells, safeCells, start,
                                materials, capability, Construction.RULE_VERSION);
                            modelChanged = false;
                            target = 0;
                            safe = 0;
                            if (!ConstructionRules.supported(problem)) {
                                fail(Status.UNSUPPORTED, "body capability is outside walk/step/drop v1");
                            } else if (anchors.isEmpty()) {
                                fail(Status.WAITING, "no stable external supply approach was supplied");
                            } else {
                                stage = 3;
                            }
                        }
                    }
                } else if (stage == 3) {
                    if (target < targets.size()) {
                        if (!budget.tryStep()) break;
                        Cell at = targets.get(target++);
                        if (!(WorldPos.of(level, position(at)).realm() instanceof Realm.Dimension)) {
                            fail(Status.UNSUPPORTED, "a target belongs to a moving frame");
                        } else if (problem.voxel(at) != Voxel.AIR || protectedCells.contains(at)) {
                            fail(Status.UNSUPPORTED, "pending targets must be known replaceable air and unprotected");
                        }
                    } else if (safe < anchors.size()) {
                        if (!budget.tryStep()) break;
                        if (!ConstructionRules.standable(problem, problem.initialState(), anchors.get(safe++))) {
                            fail(Status.UNSUPPORTED, "a declared safe approach lacks full support/body/halo clearance");
                        }
                    } else {
                        if (!budget.tryStep()) break;
                        if (!ConstructionRules.standable(problem, problem.initialState(), start)) {
                            fail(Status.UNSUPPORTED, "the worker start is outside the supported static body domain");
                        } else {
                            fingerprint = new ConstructionRules.Fingerprint(problem);
                            stage = 4;
                        }
                    }
                } else if (stage == 4 && fingerprint.advance(budget)) {
                    stage = 5;
                } else if (stage == 5 && !dirty.isEmpty()) {
                    if (!budget.tryStep()) break;
                    read(dirty.iterator().next());
                    if (modelChanged) {
                        modelChanged = false;
                        cell = box.volume();
                        stage = 2;
                    }
                } else if (stage == 5) {
                    if (!budget.tryStep()) break;
                    if (!environment(level, box, keepouts, chunks)) {
                        fail(Status.WAITING, "chunk or dynamic environment changed before snapshot publication");
                        continue;
                    }
                    if (!dirty.isEmpty()) continue;
                    coherentTick = level.getGameTime();
                    result = new Captured(problem, fingerprint.value().orElseThrow(), raw, unloaded, material,
                        coherentTick, maxReadNanos, chunks, keepouts);
                    status = Status.READY;
                    reason = "immutable bounded input ready; live dependencies still need execution validation";
                    close();
                    raw.clear();
                    voxels.clear();
                    unloaded.clear();
                }
            }
            return status;
        }

        private void read(Cell at) {
            dirty.remove(at);
            long began = System.nanoTime();
            LevelChunk present = chunk(level, chunkKey(at));
            Voxel projected = Voxel.UNKNOWN;
            if (present == null) {
                unloaded.add(at);
                raw.remove(at);
            } else {
                LevelChunk earlier = chunks.putIfAbsent(chunkKey(at), present);
                if (earlier != null && earlier != present) {
                    fail(Status.WAITING, "a chunk was replaced while capturing");
                    return;
                }
                BlockState state = level.getBlockState(position(at));
                unloaded.remove(at);
                raw.put(at, state);
                projected = Keepouts.forbidden(level, position(at)) ? Voxel.UNKNOWN : project(state);
            }
            Voxel previous = voxels.put(at, projected);
            if (problem != null && previous != projected) modelChanged = true;
            maxReadNanos = Math.max(maxReadNanos, System.nanoTime() - began);
        }

        private void fail(Status outcome, String detail) {
            status = outcome;
            reason = detail;
            close();
        }

        public Optional<Captured> result() { return Optional.ofNullable(result); }
        public Status status() { return status; }
        public String reason() { return reason; }

        @Override
        public void close() {
            mainThread(level);
            if (status == Status.PENDING) {
                status = Status.INVALID;
                reason = "capture canceled";
            }
            if (heard != null) {
                heard.close();
                heard = null;
            }
        }
    }

    public static final class Validation implements AutoCloseable {
        private final ServerLevel level;
        private final Captured captured;
        private final State expected;
        private final List<Cell> dependencies;
        private BlockChanges.Heard heard;
        private final Set<Cell> dependencySet;
        private final Set<Cell> dirty = new LinkedHashSet<>();
        private Status status = Status.PENDING;
        private String reason = "checking live projection dependencies";
        private int cell;
        private long validatedAtTick = Long.MIN_VALUE;

        private Validation(ServerLevel level, Captured captured, State expected, Set<Cell> dependencies) {
            mainThread(level);
            this.level = level;
            this.captured = captured;
            this.expected = expected;
            this.dependencies = List.copyOf(dependencies);
            dependencySet = Set.copyOf(dependencies);
            if (dependencies.size() > Construction.MAX_CELLS
                    || dependencies.stream().anyMatch(at -> !captured.problem.bounds().contains(at))
                    || expected.usedMaterials() != expected.placed().size()
                    || expected.usedMaterials() > captured.problem.materialCount()
                    || !captured.problem.orderedTargets().containsAll(expected.placed())) {
                status = Status.INVALID;
                reason = "certificate dependencies exceed the captured bounded domain";
            }
        }

        public Status advance(Budget budget) {
            mainThread(level);
            if (status == Status.VALID && heard != null && budget.tryStep()) {
                if (!environment(level, captured.problem.bounds(), captured.keepouts, captured.chunks)) {
                    fail(Status.INVALID, "the chunk or dynamic environment no longer matches");
                } else if (!dirty.isEmpty()) {
                    status = Status.PENDING;
                    reason = "checking changed certificate dependencies";
                } else {
                    validatedAtTick = level.getGameTime();
                }
            }
            while (status == Status.PENDING && !budget.exhausted()) {
                if (heard == null) {
                    if (!budget.tryStep()) break;
                    heard = BlockChanges.listen(level, bounds(captured.problem.bounds()), changed -> {
                        Cell at = new Cell(changed.getX(), changed.getY(), changed.getZ());
                        if (dependencySet.contains(at)) dirty.add(at);
                    });
                } else if (cell < dependencies.size() || !dirty.isEmpty()) {
                    if (!budget.tryStep()) break;
                    Cell at = cell < dependencies.size() ? dependencies.get(cell++) : dirty.iterator().next();
                    dirty.remove(at);
                    LevelChunk present = chunk(level, chunkKey(at));
                    if (present == null) {
                        fail(Status.WAITING, "a certificate dependency chunk is unavailable");
                        break;
                    }
                    if (present != captured.chunks.get(chunkKey(at))) {
                        fail(Status.INVALID, "a dependency chunk was replaced since capture");
                        break;
                    }
                    BlockState current = level.getBlockState(position(at));
                    if (expected.placed().contains(at)) {
                        if (current != captured.material) fail(Status.INVALID, "an executed target changed material");
                    } else if (captured.unloaded.contains(at)) {
                        fail(Status.INVALID, "an unknown captured chunk became available; recapture its model");
                    } else {
                        Voxel earlier = captured.problem.voxel(at);
                        Voxel projected = Keepouts.forbidden(level, position(at)) ? Voxel.UNKNOWN : project(current);
                        if (projected != earlier || earlier == Voxel.UNKNOWN
                                && !sameUnknown(captured.rawStates.get(at), current)) {
                            fail(Status.INVALID, "a required voxel or unknown block behavior changed");
                        }
                    }
                } else {
                    if (!budget.tryStep()) break;
                    if (!environment(level, captured.problem.bounds(), captured.keepouts, captured.chunks)) {
                        fail(Status.INVALID, "the chunk or dynamic environment no longer matches");
                    } else if (dirty.isEmpty()) {
                        status = Status.VALID;
                        reason = "dependencies matched in a coherent live pass";
                        validatedAtTick = level.getGameTime();
                    }
                }
            }
            return status;
        }

        public boolean validNow() {
            mainThread(level);
            return status == Status.VALID && heard != null && validatedAtTick == level.getGameTime()
                && dirty.isEmpty() && environment(level, captured.problem.bounds(), captured.keepouts, captured.chunks)
                && dirty.isEmpty();
        }

        private void fail(Status outcome, String detail) {
            status = outcome;
            reason = detail;
            close();
        }

        public Status status() { return status; }
        public String reason() { return reason; }

        @Override
        public void close() {
            mainThread(level);
            if (status == Status.PENDING || status == Status.VALID) {
                status = Status.INVALID;
                reason = "live validation canceled";
            }
            if (heard != null) {
                heard.close();
                heard = null;
            }
        }
    }
}
