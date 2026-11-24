package io.github.izakyl.folkways.assistant.sandbox;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.facility.api.control.Motion;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.RoutineTrial;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.facility.api.mechanism.Mechanisms;
import io.github.izakyl.folkways.core.world.api.CopiedIds;
import io.github.izakyl.folkways.core.world.api.FrameCopies;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.StructureBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * One sandbox probe: copy a mechanism into the sandbox, give one control one input, let it run a bounded number of
 * ticks, and tell what changed. Everything happens in the sandbox; the real world is only read, once, when the probe
 * starts. Moving structures (Sable sub-levels: ships, hulls, a walker with its legs) are copied whole where a copier is
 * installed, and move in the sandbox as they would in the world; the ground they stand on comes with them, so a walker
 * has somewhere to walk. Every UUID in the copy is fresh, so nothing copied can reach the real world. Server thread
 * only.
 */
public final class Probe {

    public static final int MAX_TICKS = 100;
    public static final int DEFAULT_TICKS = 40;
    public static final int DEFAULT_ROUTINE_TICKS = 600;
    public static final int MAX_ROUTINE_TICKS = 2400;
    static final int MAX_BLOCKS = 16 * 1024;
    static final int MAX_BLOCK_ENTITIES = 1024;
    static final int MAX_ENTITIES = 16;
    /** Moving structures one probe copies whole, a walker's legs included. */
    static final int MAX_STRUCTURES = 32;
    /** Non-air blocks of the ground copied around moving structures. */
    static final int MAX_GROUND = 32 * 1024;
    static final int MAX_CHUNKS = 64;
    // How far around a moving structure the ground comes with it, sideways, tried from the widest down.
    private static final int[] GROUND_REACH = {16, 12, 8, 4};
    private static final int GROUND_BELOW = 8;
    private static final int GROUND_ABOVE = 4;
    // Room around the copy for what it pushes out or swings into.
    private static final int MARGIN = 6;
    private static final int LOAD_TIMEOUT = 100;
    private static final int SETTLE = 3;
    // A copied structure needs a few more ticks to find its footing and its kinetic network.
    private static final int SETTLE_STRUCTURES = 10;
    private static final int BUSY_RETRIES = 20;
    private static final int LISTED = 24;
    private static final Gson JSON = new Gson();
    private static final int PLACE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    /** Blocks omitted from the trial, including blocks with effects beyond the sandbox. */
    public static final TagKey<Block> EXCLUDED = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways_assistant", "sandbox_excluded"));

    /** What to probe: the scanned mechanism, one input, and how long to watch. */
    public record Request(MechanismScan scan, Input input, int ticks, int releaseTick) {

        public Request(MechanismScan scan, Input input, int ticks) {
            this(scan, input, ticks, 0);
        }

        public static Request routine(MinecraftServer server, Input.Routine input, int ticks) {
            var facility = input.facility();
            var level = facility.anchor().level(server).filter(found -> !Sandbox.is(found))
                .orElseThrow(() -> new IllegalArgumentException("unloaded"));
            Realm realm = facility.anchor().realm();
            for (var element : facility.extent().elements()) {
                boolean supported = switch (element) {
                    case FacilityExtent.Block block -> realm.equals(block.pos().realm());
                    case FacilityExtent.Box box -> realm.equals(box.realm());
                    case FacilityExtent.Entity ignored -> true;
                };
                if (!supported) {
                    throw new IllegalArgumentException("unsupported_trial_extent");
                }
            }
            var bounds = facility.extent().bounds(realm)
                .orElseThrow(() -> new IllegalArgumentException("unsupported_trial_extent"));
            var scan = realm instanceof Realm.Frame frame
                ? MechanismScan.structure(level, frame.structure()) : MechanismScan.box(level, bounds.min(), bounds.max());
            return new Request(scan, input, ticks);
        }

        public Request {
            Objects.requireNonNull(scan, "scan");
            Objects.requireNonNull(input, "input");
            boolean routine = input instanceof Input.Routine;
            if (ticks < 1 || ticks > (routine ? MAX_ROUTINE_TICKS : MAX_TICKS)
                    || releaseTick < 0 || releaseTick >= ticks || routine && releaseTick != 0) {
                throw new IllegalArgumentException("invalid_ticks");
            }
        }
    }

    public sealed interface Input {

        /** Write a control as a resident would; an empty value presses a pulse. */
        record Write(WorldPos at, String part, Optional<Value> value) implements Input {
        }

        /** Put a redstone signal at an empty cell, or take it away. */
        record Redstone(WorldPos at, boolean on) implements Input {
        }

        record Routine(FacilityDefinition facility, String name, Map<String, Value> args,
                       Map<String, String> libraries, Optional<BoundingBox> keep) implements Input {

            public Routine {
                args = Map.copyOf(args);
                libraries = Map.copyOf(libraries);
                if (keep.isEmpty()) {
                    keep = argsBox(facility, name, args);
                }
            }

            private static Optional<BoundingBox> argsBox(FacilityDefinition facility, String name,
                    Map<String, Value> args) {
                var use = facility.routines().get(name);
                if (use == null) {
                    throw new IllegalArgumentException("unknown_routine");
                }
                var actual = use.resolve(facility.parameters()).arguments(args);
                return java.util.stream.Stream.concat(java.util.stream.Stream.of("target", "box", "bounds", "area"),
                        actual.keySet().stream().sorted().filter(key -> key.contains("target") || key.endsWith("_box")))
                    .flatMap(key -> box(actual.get(key)).stream()).findFirst();
            }

            public static Optional<BoundingBox> box(Value value) {
                List<Double> axes;
                if (value instanceof Value.Vector vector) {
                    axes = vector.values();
                } else if (value instanceof Value.Sequence sequence
                        && sequence.values().stream().allMatch(Value.Real.class::isInstance)) {
                    axes = sequence.values().stream().map(axis -> ((Value.Real) axis).value()).toList();
                } else {
                    return Optional.empty();
                }
                if (axes.size() != 6 || axes.stream().anyMatch(axis -> axis != (int) axis.doubleValue())) {
                    return Optional.empty();
                }
                var min = new BlockPos(axes.get(0).intValue(), axes.get(1).intValue(), axes.get(2).intValue());
                var max = new BlockPos(axes.get(3).intValue(), axes.get(4).intValue(), axes.get(5).intValue());
                return Optional.of(BoundingBox.fromCorners(min, max));
            }
        }
    }

    private enum Phase { LOAD, SETTLE, RUN, DONE }

    /** A copied cell; a carried one comes with its moving structure, and block marks a block entity in it. */
    private record Copied(BlockPos pos, BlockState state, CompoundTag block, boolean carried) {
    }

    /** A box the diff covers; a held one is loaded and swept by the probe, a structure's plot by its copy. */
    private record Region(BoundingBox box, Optional<WorldSpaces.Frame> frame, boolean held) {
    }

    /** A copied moving structure, followed from the settled copy to the end. */
    private static final class Structure {
        private final FrameCopies.Copy copy;
        private FrameCopies.Pose first;
        private FrameCopies.Pose last;
        private Integer endedAt;
        private boolean left;

        private Structure(FrameCopies.Copy copy) {
            this.copy = copy;
        }
    }

    private static final class Track {
        private final String id;
        private double[] first;
        private double[] last;
        private Map<String, Object> before = Map.of();
        private Map<String, Object> after = Map.of();
        private boolean gone;

        private Track(String id) {
            this.id = id;
        }
    }

    private final ServerLevel sandbox;
    private final ServerLevel source;
    private final Request request;
    private final MechanismGraph graph;
    private final Map<WorldPos, String> ids = new HashMap<>();
    private final Map<UUID, String> entityIds = new HashMap<>();
    private final List<Copied> blocks = new ArrayList<>();
    private final List<CompoundTag> entities = new ArrayList<>();
    private final List<String> sourceEntities = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();
    private final Set<ChunkPos> held = new LinkedHashSet<>();
    private final Set<ChunkPos> swept = new LinkedHashSet<>();
    private final List<String> notes = new ArrayList<>();
    private final Map<String, Integer> suppressed = new LinkedHashMap<>();
    private final Map<UUID, Track> tracks = new LinkedHashMap<>();
    private final Map<BlockPos, Map<String, Object>> baseline = new LinkedHashMap<>();
    private final Map<BlockPos, double[]> peaks = new HashMap<>();
    private final List<BlockState[]> before = new ArrayList<>();
    private final CompletableFuture<JsonObject> result = new CompletableFuture<>();
    private final CopiedIds copiedIds = new CopiedIds();
    private final List<FrameCopies.Capture> captures = new ArrayList<>();
    private final Set<UUID> carried = new LinkedHashSet<>();
    private final List<FrameCopies.Placed> placed = new ArrayList<>();
    private final List<Structure> structures = new ArrayList<>();
    private final BlockPos target;
    private RoutineTrial routineTrial;
    private BoundingBox routineTarget;
    private Phase phase = Phase.LOAD;
    private int waited;
    private int elapsed;
    private boolean copying;
    private int spawned;
    private boolean ticketed;
    // The input as it went: how the write answered, and when the control showed its value.
    private String written;
    private Integer doneAt;
    private boolean continuing;
    private int retries;
    private Value value;
    private Value rest;
    private String releaseResult;
    private record MotionSample(double position, boolean moving) {

        static MotionSample of(Motion motion) {
            return new MotionSample(motion.position(), motion.moving());
        }
    }

    private final Map<BlockPos, List<MotionSample>> actuators = new LinkedHashMap<>();

    private Probe(ServerLevel sandbox, Request request) {
        this.sandbox = sandbox;
        this.request = request;
        this.source = request.scan().level();
        this.graph = Mechanisms.graph(request.scan());
        graph.nodes().forEach(node -> node.entity().ifPresentOrElse(id -> entityIds.put(id, node.id()),
            () -> ids.put(node.at(), node.id())));
        WorldPos at = switch (request.input()) {
            case Input.Write write -> write.at();
            case Input.Redstone redstone -> redstone.at();
            case Input.Routine routine -> routine.facility().anchor();
        };
        target = WorldSpaces.storage(source, at).orElseThrow(() -> new IllegalArgumentException("unloaded"));
        if (request.input() instanceof Input.Routine routine && !routine.args().containsKey("target")) {
            // A card pick hands its value over as `target` only; a trial under another name would pass while the card fails.
            routine.facility().card().elements().stream()
                .filter(element -> element instanceof Card.Element.Target picked && picked.routine().equals(routine.name()))
                .map(element -> ((Card.Element.Target) element).pick().key())
                .findFirst()
                .ifPresent(pick -> notes.add("the card's " + pick + " pick passes its value as args[\"target\"], "
                    + "but this trial passed no target, only " + routine.args().keySet()));
        }
    }

    static Probe start(ServerLevel sandbox, Request request, Predicate<BlockPos> permitted) {
        Probe probe = new Probe(sandbox, request);
        probe.capture(permitted);
        return probe;
    }

    static JsonObject error(String code) {
        JsonObject data = new JsonObject();
        data.addProperty("error", code);
        return data;
    }

    CompletableFuture<JsonObject> result() {
        return result;
    }

    /** Reads everything the copy needs from the real world, now, and checks the input against it. */
    private void capture(Predicate<BlockPos> permitted) {
        MechanismScan scan = request.scan();
        if (!scan.frames().isEmpty() && FrameCopies.available()) {
            captures.addAll(FrameCopies.capture(source, scan.frames().stream().map(WorldSpaces.Frame::id).toList(),
                copiedIds, new FrameCopies.Limits(MAX_STRUCTURES, MAX_BLOCKS)));
            for (FrameCopies.Capture capture : captures) {
                for (FrameCopies.Captured frame : capture.frames()) {
                    // The copier brings the whole structure, including dependencies outside the scan.
                    BoundingBox box = frame.storage();
                    for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(),
                            box.maxX(), box.maxY(), box.maxZ())) {
                        if (!permitted.test(pos)) {
                            throw new IllegalArgumentException("outside_survey");
                        }
                        BlockState state = source.getBlockState(pos);
                        if (unsafeBlock(state, source.getBlockEntity(pos))) {
                            throw new IllegalArgumentException("moving structure contains " + id(state)
                                + "; the trial cannot copy it safely");
                        }
                    }
                    carried.add(frame.original());
                }
            }
        }
        List<MechanismScan.Cell> cells = new ArrayList<>(scan.cells());
        if (request.input() instanceof Input.Routine routine) {
            routineTarget = RoutineTrial.targetBounds(source, routine.facility(), routine.name(), routine.args())
                .orElse(null);
            if (routineTarget != null) {
                Set<BlockPos> included = new LinkedHashSet<>();
                for (var cell : scan.cells()) {
                    if (cell.at().realm() instanceof Realm.Dimension) {
                        included.add(cell.storage());
                    }
                }
                Realm world = Realm.of(source);
                for (BlockPos pos : BlockPos.betweenClosed(routineTarget.minX(), routineTarget.minY(),
                        routineTarget.minZ(), routineTarget.maxX(), routineTarget.maxY(), routineTarget.maxZ())) {
                    var at = pos.immutable();
                    if (!permitted.test(at)) {
                        throw new IllegalArgumentException("outside_survey");
                    }
                    if (included.add(at)) {
                        cells.add(new MechanismScan.Cell(new WorldPos(world, at), at));
                    }
                }
            }
        }
        int blockEntities = 0;
        Map<Optional<UUID>, BoundingBox> spans = new LinkedHashMap<>();
        Map<String, Integer> excluded = new LinkedHashMap<>();
        for (MechanismScan.Cell cell : cells) {
            BlockState state = source.getBlockState(cell.storage());
            Optional<UUID> frame = cell.at().realm() instanceof Realm.Frame(UUID structure)
                ? Optional.of(structure) : Optional.empty();
            boolean aboard = frame.filter(carried::contains).isPresent();
            if (!aboard) {
                spans.compute(frame, (key, box) -> box == null
                    ? BoundingBox.fromCorners(cell.storage(), cell.storage()) : box.encapsulate(cell.storage()));
            }
            if (state.isAir()) {
                continue;
            }
            if (aboard) {
                // Its structure brings it, block entity and all; it is listed to be watched.
                blocks.add(new Copied(cell.storage(), state, state.hasBlockEntity() ? new CompoundTag() : null, true));
                continue;
            }
            BlockEntity block = state.hasBlockEntity() ? source.getBlockEntity(cell.storage()) : null;
            if (state.is(EXCLUDED) || unsafeBlock(state, block)) {
                excluded.merge(id(state), 1, Integer::sum);
                continue;
            }
            CompoundTag tag = null;
            if (block != null) {
                tag = copiedIds.retag(block.saveWithFullMetadata(source.registryAccess()));
                blockEntities++;
            }
            blocks.add(new Copied(cell.storage(), state, tag, false));
        }
        if (blocks.size() > MAX_BLOCKS || blockEntities > MAX_BLOCK_ENTITIES) {
            throw new IllegalArgumentException("copy_too_large");
        }
        Map<String, Integer> skipped = new LinkedHashMap<>();
        for (Entity entity : scan.entities()) {
            if (!Mechanisms.mechanical(entity)) {
                skipped.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), 1, Integer::sum);
                continue;
            }
            CompoundTag tag = new CompoundTag();
            if (entity.isPassenger() || !entity.save(tag)) {
                continue;
            }
            tag.remove("Passengers");
            tag.remove("UUID");
            CompoundTag copied = copiedIds.retag(tag);
            copied.putUUID("UUID", copiedIds.of(entity.getUUID()));
            entities.add(copied);
            sourceEntities.add(entityIds.getOrDefault(entity.getUUID(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath() + "#"
                    + entity.getUUID().toString().substring(0, 8)));
        }
        if (entities.size() > MAX_ENTITIES) {
            throw new IllegalArgumentException("copy_too_large");
        }
        if (!skipped.isEmpty()) {
            notes.add("omitted from the trial (outside the mechanism): " + skipped);
        }
        if (!excluded.isEmpty()) {
            notes.add("omitted from the trial (sandbox exclusions): " + excluded);
        }
        for (WorldSpaces.Frame frame : scan.frames()) {
            if (!carried.contains(frame.id())) {
                notes.add("structure " + frame.id().toString().substring(0, 8)
                    + " stayed fixed during the trial");
            }
        }
        for (Map.Entry<Optional<UUID>, BoundingBox> span : spans.entrySet()) {
            if (span.getValue().minY() < sandbox.getMinBuildHeight()
                    || span.getValue().maxY() >= sandbox.getMaxBuildHeight()) {
                throw new IllegalArgumentException("bounds_limit");
            }
            regions.add(new Region(clip(span.getValue().inflatedBy(MARGIN)), span.getKey().flatMap(id ->
                scan.frames().stream().filter(frame -> frame.id().equals(id)).findFirst()), true));
        }
        for (FrameCopies.Capture capture : captures) {
            for (FrameCopies.Captured frame : capture.frames()) {
                regions.add(new Region(frame.storage(), WorldSpaces.frame(source, frame.original()), false));
            }
        }
        if (!captures.isEmpty()) {
            ground(permitted);
        }
        for (Region region : regions) {
            if (!region.held()) {
                continue;
            }
            held.addAll(chunks(region.box()));
        }
        for (ChunkPos chunk : held) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    swept.add(new ChunkPos(chunk.x + dx, chunk.z + dz));
                }
            }
        }
        if (held.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("copy_too_large");
        }
        switch (request.input()) {
            case Input.Routine routine -> {
                if (!routine.facility().routines().containsKey(routine.name())) {
                    throw new IllegalArgumentException("unknown_routine");
                }
                if (routine.facility().anchor().realm() instanceof Realm.Frame frame && !carried.contains(frame.structure())) {
                    throw new IllegalArgumentException("structure_trial_unavailable");
                }
            }
            case Input.Write write -> {
                if (blocks.stream().noneMatch(copied -> copied.pos().equals(target))) {
                    throw new IllegalArgumentException("control_outside_copy");
                }
                Optional<Control> control = Controls.resolve(source, new ControlRef(write.at(), write.part()));
                if (control.isEmpty()) {
                    throw new IllegalArgumentException("unknown_control");
                }
                if (!(control.get() instanceof Signal signal)) {
                    throw new IllegalArgumentException("not_a_signal");
                }
                value = write.value().orElse(signal.type() instanceof SignalType.Pulse ? Value.PULSE : null);
                if (value == null) {
                    throw new IllegalArgumentException("value_required");
                }
                signal.type().check(value).ifPresent(wrong -> {
                    throw new IllegalArgumentException("invalid_value: " + wrong);
                });
            }
            case Input.Redstone redstone -> {
                BlockState there = source.getBlockState(target);
                if (!there.isAir() && !there.is(Blocks.REDSTONE_BLOCK)) {
                    throw new IllegalArgumentException("redstone_cell_occupied");
                }
                if (regions.stream().noneMatch(region -> region.box().isInside(target))) {
                    throw new IllegalArgumentException("control_outside_copy");
                }
            }
        }
    }

    /**
     * The ground around the copied structures: what a walker stands and steps on, what a ship rests on. It reaches as
     * far as the copy's limits allow, from {@link #GROUND_REACH}'s widest down, and joins the world region.
     */
    private void ground(Predicate<BlockPos> permitted) {
        AABB around = null;
        for (FrameCopies.Capture capture : captures) {
            for (FrameCopies.Captured frame : capture.frames()) {
                around = around == null ? frame.world() : around.minmax(frame.world());
            }
        }
        Optional<Region> world = regions.stream().filter(region -> region.held() && region.frame().isEmpty())
            .findFirst();
        if (around == null) {
            return;
        }
        Set<BlockPos> taken = new HashSet<>();
        blocks.forEach(copied -> taken.add(copied.pos()));
        for (int reach : GROUND_REACH) {
            BoundingBox box = clip(new BoundingBox(floor(around.minX) - reach, floor(around.minY) - GROUND_BELOW,
                floor(around.minZ) - reach, floor(around.maxX) + reach, floor(around.maxY) + GROUND_ABOVE,
                floor(around.maxZ) + reach));
            if (world.isPresent()) {
                box = box.encapsulate(world.get().box());
            }
            Set<ChunkPos> chunks = new LinkedHashSet<>(chunks(box));
            regions.stream().filter(region -> region.held() && region.frame().isPresent())
                .forEach(region -> chunks.addAll(chunks(region.box())));
            if (chunks.size() > MAX_CHUNKS) {
                continue;
            }
            List<Copied> found = new ArrayList<>();
            Map<String, Integer> excluded = new LinkedHashMap<>();
            boolean unloaded = false;
            boolean outOfScope = false;
            int blockEntities = (int) blocks.stream().filter(copied -> !copied.carried() && copied.block() != null)
                .count();
            for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                    box.maxZ())) {
                if (taken.contains(pos)) {
                    continue;
                }
                if (!permitted.test(pos)) {
                    outOfScope = true;
                    continue;
                }
                if (!source.isLoaded(pos)) {
                    unloaded = true;
                    continue;
                }
                BlockState state = source.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }
                BlockEntity block = state.hasBlockEntity() ? source.getBlockEntity(pos) : null;
                if (state.is(EXCLUDED) || unsafeBlock(state, block)) {
                    excluded.merge(id(state), 1, Integer::sum);
                    continue;
                }
                CompoundTag tag = null;
                if (block != null) {
                    tag = copiedIds.retag(block.saveWithFullMetadata(source.registryAccess()));
                    blockEntities++;
                }
                found.add(new Copied(pos.immutable(), state, tag, false));
                if (found.size() > MAX_GROUND || blockEntities > MAX_BLOCK_ENTITIES) {
                    break;
                }
            }
            if (found.size() > MAX_GROUND || blockEntities > MAX_BLOCK_ENTITIES) {
                continue;
            }
            blocks.addAll(found);
            world.ifPresent(regions::remove);
            regions.addFirst(new Region(box, Optional.empty(), true));
            notes.add("the ground within " + reach + " blocks around the moving structures was included ("
                + found.size() + " blocks); the trial has no ground beyond that");
            if (unloaded) {
                notes.add("part of the ground around the moving structures was unloaded and is missing");
            }
            if (outOfScope) {
                notes.add("ground outside the colony's read scope was omitted");
            }
            if (!excluded.isEmpty()) {
                notes.add("ground omitted from the trial (sandbox exclusions): " + excluded);
            }
            return;
        }
        notes.add("the trial omitted the ground around the moving structures because the area was too large");
    }

    private static boolean unsafeBlock(BlockState state, BlockEntity block) {
        return state.getBlock() instanceof CommandBlock || state.getBlock() instanceof StructureBlock
            || state.getBlock() instanceof JigsawBlock || block instanceof CommandBlockEntity;
    }

    private BoundingBox clip(BoundingBox box) {
        return new BoundingBox(box.minX(), Math.max(box.minY(), sandbox.getMinBuildHeight()), box.minZ(),
            box.maxX(), Math.min(box.maxY(), sandbox.getMaxBuildHeight() - 1), box.maxZ());
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static List<ChunkPos> chunks(BoundingBox box) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        return chunks;
    }

    void tick() {
        switch (phase) {
            case LOAD -> load();
            case SETTLE -> {
                sandbox.resetEmptyTime();
                if (++waited >= (structures.isEmpty() ? SETTLE : SETTLE_STRUCTURES)) {
                    snapshot();
                    apply();
                    phase = Phase.RUN;
                }
            }
            case RUN -> {
                sandbox.resetEmptyTime();
                elapsed++;
                if (routineTrial == null) {
                    drive();
                } else {
                    routineTrial.tick(elapsed);
                }
                sample();
                sampleActuators();
                if (elapsed == request.releaseTick()) {
                    release();
                }
                follow();
                if (elapsed >= request.ticks() || routineTrial != null && routineTrial.finished()) {
                    finish();
                }
            }
            case DONE -> {
            }
        }
    }

    private void load() {
        if (!ticketed) {
            for (ChunkPos chunk : held) {
                sandbox.getChunkSource().addRegionTicket(Sandbox.TICKET, chunk, 2, chunk, true);
            }
            ticketed = true;
            for (ChunkPos chunk : swept) {
                sandbox.getChunk(chunk.x, chunk.z);
            }
        }
        sandbox.resetEmptyTime();
        boolean ready = held.stream().allMatch(chunk -> sandbox.isPositionEntityTicking(chunk.getWorldPosition()));
        if (!ready) {
            if (++waited > LOAD_TIMEOUT) {
                fail("sandbox_unavailable");
            }
            return;
        }
        sweep();
        copy();
        waited = 0;
        phase = Phase.SETTLE;
    }

    /** Empties every chunk the probe loads: no stray block or entity may tick beside the copy. */
    private void sweep() {
        int stale = FrameCopies.clear(sandbox);
        if (stale > 0 && phase == Phase.LOAD) {
            notes.add("removed " + stale + " leftover structures before the trial");
        }
        for (Entity entity : List.copyOf(collect(sandbox.getAllEntities()))) {
            if (!(entity instanceof ServerPlayer)) {
                entity.discard();
            }
        }
        for (ChunkPos chunk : swept) {
            LevelChunk loaded = sandbox.getChunkSource().getChunkNow(chunk.x, chunk.z);
            if (loaded == null) {
                continue;
            }
            LevelChunkSection[] sections = loaded.getSections();
            for (int index = 0; index < sections.length; index++) {
                if (sections[index].hasOnlyAir()) {
                    continue;
                }
                int bottom = sandbox.getSectionYFromSectionIndex(index) << 4;
                for (BlockPos pos : BlockPos.betweenClosed(chunk.getMinBlockX(), bottom, chunk.getMinBlockZ(),
                        chunk.getMaxBlockX(), bottom + 15, chunk.getMaxBlockZ())) {
                    if (!sandbox.getBlockState(pos).isAir()) {
                        sandbox.removeBlockEntity(pos);
                        sandbox.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE | Block.UPDATE_MOVE_BY_PISTON);
                    }
                }
            }
        }
    }

    private static List<Entity> collect(Iterable<Entity> entities) {
        List<Entity> found = new ArrayList<>();
        entities.forEach(found::add);
        return found;
    }

    private void copy() {
        for (Copied copied : blocks) {
            if (!copied.carried()) {
                sandbox.setBlock(copied.pos(), copied.state(), PLACE);
            }
        }
        for (Copied copied : blocks) {
            if (copied.block() == null || copied.carried()) {
                continue;
            }
            BlockEntity loaded = BlockEntity.loadStatic(copied.pos(), copied.state(), copied.block(),
                sandbox.registryAccess());
            if (loaded != null) {
                sandbox.setBlockEntity(loaded);
            }
        }
        copying = true;
        try {
            // The ground is down before the structures land on it; their own entities come after them.
            for (FrameCopies.Capture capture : captures) {
                FrameCopies.Placed made = capture.place(sandbox);
                placed.add(made);
                made.copies().forEach(copy -> structures.add(new Structure(copy)));
                for (FrameCopies.Captured frame : capture.frames()) {
                    if (made.copies().stream().noneMatch(copy -> copy.original().equals(frame.original()))) {
                        notes.add("could not include structure in the trial: "
                            + frame.original().toString().substring(0, 8));
                    }
                }
            }
            for (int index = 0; index < entities.size(); index++) {
                String id = sourceEntities.get(index);
                Entity copy = EntityType.loadEntityRecursive(entities.get(index), sandbox, entity -> entity);
                if (copy != null && sandbox.addFreshEntity(copy)) {
                    tracks.put(copy.getUUID(), new Track(id));
                } else {
                    notes.add("could not include in the trial: " + id);
                }
            }
        } finally {
            copying = false;
        }
    }

    /** Whether an entity may join the sandbox now: the probe's own copies, and contraptions its mechanism makes. */
    boolean admits(Entity entity) {
        if (phase == Phase.DONE) {
            return false;
        }
        if (copying) {
            return true;
        }
        if ((phase == Phase.SETTLE || phase == Phase.RUN) && Mechanisms.mechanical(entity)) {
            tracks.put(entity.getUUID(), new Track(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath()
                + "#new" + ++spawned));
            return true;
        }
        if (phase != Phase.LOAD) {
            suppressed(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                + (entity instanceof Projectile ? " (projectile, not launched)" : ""));
        }
        return false;
    }

    void suppressed(String what) {
        suppressed.merge(what, 1, Integer::sum);
    }

    private void snapshot() {
        for (Region region : regions) {
            BoundingBox box = region.box();
            BlockState[] states = new BlockState[box.getXSpan() * box.getYSpan() * box.getZSpan()];
            int index = 0;
            for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                    box.maxZ())) {
                states[index++] = sandbox.getBlockState(pos);
            }
            before.add(states);
        }
        for (Copied copied : blocks) {
            if (request.releaseTick() > 0 && copied.block() != null) {
                motion(copied.pos()).ifPresent(reading -> {
                    var samples = new ArrayList<MotionSample>();
                    samples.add(MotionSample.of(reading));
                    actuators.put(copied.pos(), samples);
                });
            }
            if (copied.block() != null || ids.containsKey(address(copied.pos()))) {
                Map<String, Object> values = Mechanisms.observe(sandbox, copied.pos());
                if (!values.isEmpty()) {
                    baseline.put(copied.pos(), values);
                }
            }
        }
        if (request.input() instanceof Input.Redstone) {
            baseline.put(target, Mechanisms.observe(sandbox, target));
        }
        for (Map.Entry<UUID, Track> entry : tracks.entrySet()) {
            Entity entity = sandbox.getEntity(entry.getKey());
            if (entity != null) {
                entry.getValue().before = Mechanisms.observe(entity);
                entry.getValue().first = position(entity);
                entry.getValue().last = entry.getValue().first;
            }
        }
        for (Structure structure : structures) {
            structure.copy.pose().ifPresent(pose -> {
                structure.first = pose;
                structure.last = pose;
            });
        }
    }

    private boolean observesTarget(BoundingBox target) {
        if (routineTarget == null) {
            return false;
        }
        var min = new BlockPos(target.minX(), target.minY(), target.minZ());
        var max = new BlockPos(target.maxX(), target.maxY(), target.maxZ());
        if (routineTarget.isInside(min) && routineTarget.isInside(max)) {
            return true;
        }
        // Moving trials may enter fresh parts of the terrain copied around their structure.
        return !captures.isEmpty() && regions.stream().anyMatch(region -> region.held() && region.frame().isEmpty()
            && region.box().isInside(min) && region.box().isInside(max));
    }

    private void apply() {
        switch (request.input()) {
            case Input.Routine routine -> {
                var facility = routine.facility();
                Realm realm = switch (facility.anchor().realm()) {
                    case Realm.Dimension ignored -> Realm.of(sandbox);
                    case Realm.Frame frame -> new Realm.Frame(copiedIds.of(frame.structure()));
                };
                var anchor = new WorldPos(realm, facility.anchor().cell());
                if (!anchor.in(sandbox)) {
                    throw new IllegalArgumentException("structure_trial_unavailable");
                }
                Map<String, FacilityDefinition.Placed> controls = new LinkedHashMap<>();
                facility.controls().forEach((name, part) -> controls.put(name, new FacilityDefinition.Placed(
                    part.offset(), part.part(), part.carrier(), part.entity().map(copiedIds::of))));
                var trial = new FacilityDefinition(copiedIds.of(facility.id()), anchor, controls, facility.routines(),
                    facility.parameters(), facility.probes(), facility.name(),
                    FacilityExtent.EMPTY, facility.card(),
                    List.of(), Map.of(), Optional.empty(), List.of());
                routineTrial = new RoutineTrial(sandbox, trial, routine.name(), routine.args(), routine.libraries(),
                    (signal, value) -> Sandbox.write(sandbox, signal.anchor().block(sandbox), signal, value),
                    this::observesTarget);
                routineTrial.tick(0);
            }
            case Input.Write write -> {
                Signal signal = signal(write).orElse(null);
                if (signal == null) {
                    written = "gone";
                    notWorkable(write);
                    return;
                }
                try {
                    rest = signal.commandFeedback().flatMap(read -> read.read(sandbox)).orElse(null);
                    if (signal.type() instanceof SignalType.Pulse) {
                        Signal.Write first = signal.press(sandbox, null, settled -> written = name(settled));
                        if (written == null) {
                            written = first == Signal.Write.WRITTEN ? "pressed" : name(first);
                        }
                        doneAt = 0;
                        return;
                    }
                    Signal.Write first = Sandbox.write(sandbox, target, signal, value);
                    written = name(first);
                    continuing = first == Signal.Write.WRITTEN && signal.cost() instanceof Control.Cost.UntilRead;
                    if (first == Signal.Write.WRITTEN && !continuing) {
                        doneAt = 0;
                    }
                    if (first == Signal.Write.REFUSED) {
                        notWorkable(write);
                    }
                } catch (RuntimeException failure) {
                    written = "write_failed";
                    notes.add("the control failed without a resident writer: " + failure.getClass().getSimpleName());
                }
            }
            case Input.Redstone redstone -> {
                sandbox.setBlock(target, redstone.on() ? Blocks.REDSTONE_BLOCK.defaultBlockState()
                    : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                written = redstone.on() ? "powered" : "unpowered";
                doneAt = 0;
            }
        }
    }

    private Optional<Motion> motion(BlockPos pos) {
        return Controls.resolve(sandbox, new ControlRef(WorldSpaces.at(sandbox, pos), "motion"))
            .filter(Motion.class::isInstance).map(Motion.class::cast);
    }

    private void sampleActuators() {
        actuators.forEach((pos, samples) -> samples.add(motion(pos).map(MotionSample::of)
            .orElseGet(() -> new MotionSample(Double.NaN, false))));
    }

    private void release() {
        continuing = false;
        if (request.input() instanceof Input.Redstone redstone) {
            sandbox.setBlock(target, redstone.on() ? Blocks.AIR.defaultBlockState() : Blocks.REDSTONE_BLOCK.defaultBlockState(),
                Block.UPDATE_ALL);
            releaseResult = "returned";
        } else if (request.input() instanceof Input.Write write) {
            Signal signal = signal(write).orElse(null);
            if (signal == null) {
                releaseResult = "gone";
            } else if (signal.type() instanceof SignalType.Pulse || signal.cost() instanceof Control.Cost.UntilRead) {
                releaseResult = "ended";
                doneAt = doneAt == null ? elapsed : doneAt;
            } else if (rest != null) {
                releaseResult = name(Sandbox.write(sandbox, target, signal, rest));
            } else {
                releaseResult = "unknown_rest";
            }
        }
    }

    private JsonArray response() {
        JsonArray report = new JsonArray();
        int release = request.releaseTick();
        actuators.forEach((pos, samples) -> {
            if (report.size() >= LISTED) {
                return;
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", label(pos));
            row.addProperty("part", "motion");
            if (samples.stream().anyMatch(sample -> !Double.isFinite(sample.position()))) {
                row.addProperty("result", "reading_lost");
                report.add(row);
                return;
            }
            int first = 0;
            int active = 0;
            int stop = 0;
            double movement = 0;
            double firstStep = 0;
            for (int tick = 1; tick < samples.size(); tick++) {
                var previous = samples.get(tick - 1);
                var current = samples.get(tick);
                double step = current.position() - previous.position();
                if (Math.abs(step) <= 1e-6) {
                    continue;
                }
                if (first == 0) {
                    first = tick;
                    firstStep = step;
                }
                if (tick <= release && previous.moving() && current.moving()) {
                    active++;
                    movement += step;
                } else if (tick > release) {
                    stop = tick - release;
                }
            }
            double atRelease = samples.get(release).position();
            double velocity = active == 0 ? first <= release ? firstStep : 0 : movement / active;
            row.addProperty("velocity", velocity);
            row.addProperty("dead_zone", first == 0);
            if (first > 0) {
                row.addProperty("start_ticks", first - 1);
            }
            row.addProperty("stop_ticks", stop);
            row.addProperty("overshoot", samples.getLast().position() - atRelease);
            row.addProperty("still_moving", samples.getLast().moving() || stop > 0 && stop + release == samples.size() - 1);
            report.add(row);
        });
        return report;
    }

    private void notWorkable(Input.Write write) {
        if (write.at().realm() instanceof Realm.Frame(UUID structure) && !carried.contains(structure)) {
            notes.add("the control belongs to a moving structure, which stayed fixed during the trial; "
                + "it may need its structure to work");
        }
    }

    private Optional<Signal> signal(Input.Write write) {
        WorldPos at = new WorldPos(Realm.of(sandbox), target);
        return Controls.resolve(sandbox, new ControlRef(at, write.part())).filter(Signal.class::isInstance)
            .map(Signal.class::cast);
    }

    private static String name(Signal.Write write) {
        return write.name().toLowerCase(java.util.Locale.ROOT);
    }

    // A crank keeps being worked until its reading shows the value, as a resident would; a busy write is tried again.
    private void drive() {
        if (!(request.input() instanceof Input.Write write) || doneAt != null
            || request.releaseTick() > 0 && elapsed > request.releaseTick()) {
            return;
        }
        if (!continuing && !"busy".equals(written)) {
            return;
        }
        Signal signal = signal(write).orElse(null);
        if (signal == null) {
            continuing = false;
            return;
        }
        try {
            if (continuing) {
                Signal.Write again = signal.write(sandbox, null, value);
                if (signal.feedback().flatMap(reading -> reading.read(sandbox))
                        .filter(read -> signal.type().agrees(value, read)).isPresent()) {
                    continuing = false;
                    doneAt = elapsed;
                } else if (again == Signal.Write.REFUSED) {
                    continuing = false;
                    written = "refused";
                }
            } else if (retries++ < BUSY_RETRIES) {
                Signal.Write again = signal.write(sandbox, null, value);
                written = name(again);
                continuing = again == Signal.Write.WRITTEN && signal.cost() instanceof Control.Cost.UntilRead;
                if (again == Signal.Write.WRITTEN && !continuing) {
                    doneAt = elapsed;
                }
            }
        } catch (RuntimeException failure) {
            continuing = false;
            written = "write_failed";
        }
    }

    private void sample() {
        for (BlockPos pos : baseline.keySet()) {
            Object speed = Mechanisms.observe(sandbox, pos).get("speed");
            if (speed instanceof Number number && number.doubleValue() != 0) {
                double[] peak = peaks.computeIfAbsent(pos, key -> new double[1]);
                if (Math.abs(number.doubleValue()) > Math.abs(peak[0])) {
                    peak[0] = number.doubleValue();
                }
            }
        }
        for (Map.Entry<UUID, Track> entry : tracks.entrySet()) {
            Entity entity = sandbox.getEntity(entry.getKey());
            Track track = entry.getValue();
            if (entity == null || !entity.isAlive()) {
                track.gone = true;
                continue;
            }
            if (track.first == null) {
                track.first = position(entity);
                track.before = Mechanisms.observe(entity);
            }
            track.last = position(entity);
            track.after = Mechanisms.observe(entity);
        }
    }

    /** Follows each copied structure; one that leaves the loaded area is removed before it can unload. */
    private void follow() {
        for (Structure structure : structures) {
            if (structure.endedAt != null) {
                continue;
            }
            Optional<FrameCopies.Pose> pose = structure.copy.pose();
            if (pose.isEmpty()) {
                structure.endedAt = elapsed;
                continue;
            }
            if (structure.first == null) {
                structure.first = pose.get();
            }
            structure.last = pose.get();
            if (!held.contains(new ChunkPos(BlockPos.containing(pose.get().position())))) {
                structure.copy.remove();
                structure.left = true;
                structure.endedAt = elapsed;
            }
        }
    }

    private static double[] position(Entity entity) {
        return new double[]{entity.getX(), entity.getY(), entity.getZ()};
    }

    private void finish() {
        JsonObject data = new JsonObject();
        try {
            data = report();
        } finally {
            clean();
            phase = Phase.DONE;
            result.complete(data);
        }
    }

    void fail(String code) {
        if (phase == Phase.DONE) {
            return;
        }
        try {
            clean();
        } finally {
            phase = Phase.DONE;
            result.complete(error(code));
        }
    }

    private void clean() {
        if (routineTrial != null) {
            routineTrial.close();
        }
        try {
            placed.forEach(FrameCopies.Placed::remove);
        } finally {
            placed.clear();
        }
        for (Entity entity : collect(sandbox.getAllEntities())) {
            if (!(entity instanceof ServerPlayer)) {
                entity.discard();
            }
        }
        if (ticketed) {
            sweep();
            for (ChunkPos chunk : held) {
                sandbox.getChunkSource().removeRegionTicket(Sandbox.TICKET, chunk, 2, chunk, true);
            }
            ticketed = false;
        }
    }

    private JsonObject report() {
        JsonObject data = new JsonObject();
        List<String> summary = new ArrayList<>();
        if (routineTrial != null) {
            JsonObject routine = JSON.toJsonTree(routineTrial.report()).getAsJsonObject();
            data.add("routine", routine);
            summary.add("Routine trial ended " + routine.get("ending").getAsString()
                + (routine.has("reason") ? ": " + routine.get("reason").getAsString() : ""));
        }
        JsonObject input = new JsonObject();
        switch (request.input()) {
            case Input.Routine routine -> input.addProperty("routine", routine.name());
            case Input.Write write -> {
                input.addProperty("control", label(target));
                input.addProperty("part", write.part());
                input.add("value", JSON.toJsonTree(Mechanisms.describe(value)));
                // What the control reads once the trial is over, such as where a limb's end came to rest.
                signal(write).flatMap(Signal::feedback).flatMap(reading -> reading.read(sandbox))
                    .ifPresent(now -> {
                        var read = JSON.toJsonTree(Mechanisms.describe(now));
                        input.add("reading", read);
                        if (read.isJsonObject()) {
                            read = read.deepCopy();
                            read.getAsJsonObject().remove("snbt");
                        }
                        summary.add(write.part() + " reads " + read + " at the end");
                    });
            }
            case Input.Redstone redstone -> {
                input.addProperty("redstone", label(target));
                input.addProperty("on", redstone.on());
            }
        }
        input.addProperty("result", written == null ? "none" : written);
        if (doneAt != null) {
            input.addProperty("done_tick", doneAt);
        } else if (request.input() instanceof Input.Write) {
            input.addProperty("done_tick", (String) null);
            notes.add("the control's reading did not show the value within the probe");
        }
        if (request.releaseTick() > 0) {
            input.addProperty("release_tick", request.releaseTick());
            input.addProperty("release_result", releaseResult);
            if (rest != null) {
                input.add("rest", JSON.toJsonTree(Mechanisms.describe(rest)));
            }
            data.add("response", response());
        }
        data.add("input", input);
        data.addProperty("ticks", elapsed);
        JsonObject copied = new JsonObject();
        copied.addProperty("blocks", blocks.size());
        copied.addProperty("block_entities", blocks.stream().filter(block -> block.block() != null).count());
        copied.addProperty("entities", tracks.size() - spawned);
        copied.addProperty("structures", structures.size());
        data.add("copied", copied);
        JsonArray changes = new JsonArray();
        for (Map.Entry<BlockPos, Map<String, Object>> entry : baseline.entrySet()) {
            Map<String, Object> now = Mechanisms.observe(sandbox, entry.getKey());
            Map<String, Object> was = entry.getValue();
            Map<String, Object> from = new LinkedHashMap<>();
            Map<String, Object> to = new LinkedHashMap<>();
            Set<String> keys = new LinkedHashSet<>(was.keySet());
            keys.addAll(now.keySet());
            for (String key : keys) {
                if (key.equals("speed")) {
                    continue;
                }
                if (!Objects.equals(was.get(key), now.get(key))) {
                    from.put(key, was.get(key));
                    to.put(key, now.get(key));
                }
            }
            double[] peak = peaks.get(entry.getKey());
            if (from.isEmpty() && peak == null) {
                continue;
            }
            JsonObject change = new JsonObject();
            String id = label(entry.getKey());
            change.addProperty("id", id);
            if (!from.isEmpty()) {
                change.add("before", JSON.toJsonTree(from));
                change.add("after", JSON.toJsonTree(to));
            }
            if (peak != null) {
                change.addProperty("peak_speed", peak[0]);
            }
            if (changes.size() < 48) {
                changes.add(change);
            }
            StringBuilder line = new StringBuilder(id);
            List<String> moved = new ArrayList<>();
            from.forEach((key, old) -> moved.add(key + " " + old + " -> " + to.get(key)));
            if (!moved.isEmpty()) {
                line.append(": ").append(String.join(", ", moved));
            }
            if (peak != null) {
                line.append(" (turned at up to ").append(peak[0]).append(" rpm)");
            }
            summary.add(line.toString());
        }
        data.add("changes", changes);
        JsonArray moved = new JsonArray();
        for (Track track : tracks.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", track.id);
            if (track.first != null && track.last != null) {
                double dx = round(track.last[0] - track.first[0]);
                double dy = round(track.last[1] - track.first[1]);
                double dz = round(track.last[2] - track.first[2]);
                row.add("moved", JSON.toJsonTree(List.of(dx, dy, dz)));
                if (dx != 0 || dy != 0 || dz != 0) {
                    summary.add(track.id + " moved " + direction(dx, dy, dz));
                }
            }
            Map<String, Object> turned = new LinkedHashMap<>();
            track.after.forEach((key, now) -> {
                if (!List.of("x", "y", "z").contains(key) && !Objects.equals(track.before.get(key), now)) {
                    turned.put(key, List.of(Objects.requireNonNullElse(track.before.get(key), "none"), now));
                }
            });
            if (!turned.isEmpty()) {
                row.add("changed", JSON.toJsonTree(turned));
                summary.add(track.id + " changed " + turned);
            }
            if (track.gone) {
                row.addProperty("ended", true);
                summary.add(track.id + " ended (disassembled or removed)");
            }
            moved.add(row);
        }
        data.add("entities", moved);
        data.add("structures", structureReport(summary));
        data.add("blocks", blockDiff(data, summary));
        JsonObject blocked = new JsonObject();
        suppressed.forEach(blocked::addProperty);
        data.add("suppressed", blocked);
        if (!suppressed.isEmpty()) {
            summary.add("suppressed during the trial: " + suppressed);
        }
        notes.addAll(graph.unsupported());
        data.add("notes", JSON.toJsonTree(notes));
        if (summary.isEmpty()) {
            summary.add("nothing observable changed");
        }
        data.add("summary", JSON.toJsonTree(summary));
        return data;
    }

    private JsonObject blockDiff(JsonObject data, List<String> summary) {
        Optional<BoundingBox> keep = request.input() instanceof Input.Routine routine
            ? routine.keep() : Optional.empty();
        int listed = routineTrial == null ? LISTED : 64;
        int outside = 0;
        JsonArray stray = new JsonArray();
        Set<BlockPos> changed = new HashSet<>();
        JsonArray added = new JsonArray();
        JsonArray removed = new JsonArray();
        JsonArray altered = new JsonArray();
        int[] counts = new int[3];
        for (int regionIndex = 0; regionIndex < regions.size(); regionIndex++) {
            Region region = regions.get(regionIndex);
            BoundingBox box = region.box();
            if (!region.held() && region.frame().map(frame -> structures.stream().anyMatch(structure ->
                    structure.left && structure.copy.original().equals(frame.id()))).orElse(false)) {
                continue;
            }
            BlockState[] states = before.get(regionIndex);
            int index = 0;
            for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                    box.maxZ())) {
                BlockState was = states[index++];
                BlockState now = sandbox.getBlockState(pos);
                if (was == now || routineTrial != null && !changed.add(pos.immutable())) {
                    continue;
                }
                JsonObject row = new JsonObject();
                row.add("at", at(pos));
                if (keep.isPresent() && (address(pos).realm() instanceof Realm.Frame || !keep.get().isInside(pos))) {
                    if (outside++ < LISTED) {
                        stray.add(row.get("at"));
                    }
                }
                if (was.isAir()) {
                    row.addProperty("block", id(now));
                    if (counts[0]++ < listed) {
                        added.add(row);
                    }
                } else if (now.isAir()) {
                    row.addProperty("block", id(was));
                    if (counts[1]++ < listed) {
                        removed.add(row);
                    }
                } else {
                    row.addProperty("from", describe(was));
                    row.addProperty("to", describe(now));
                    if (counts[2]++ < listed) {
                        altered.add(row);
                    }
                }
            }
        }
        if (keep.isPresent()) {
            JsonObject report = new JsonObject();
            report.addProperty("count", outside);
            report.add("cells", stray);
            data.add("outside", report);
        }
        JsonObject diff = new JsonObject();
        diff.addProperty("added_count", counts[0]);
        diff.addProperty("removed_count", counts[1]);
        diff.addProperty("altered_count", counts[2]);
        diff.add("added", added);
        diff.add("removed", removed);
        diff.add("altered", altered);
        if (counts[0] + counts[1] + counts[2] > 0) {
            summary.add("blocks: " + counts[0] + " added, " + counts[1] + " removed, " + counts[2] + " altered");
        }
        return diff;
    }

    /** How each copied structure moved: its translation, its turn and where it ended up, in the world. */
    private JsonArray structureReport(List<String> summary) {
        // The structures the mechanism is on: those with a part of the graph, or the control, aboard.
        Set<UUID> scanned = new HashSet<>();
        graph.nodes().forEach(node -> {
            if (node.at().realm() instanceof Realm.Frame(UUID structure)) {
                scanned.add(structure);
            }
        });
        WorldPos input = switch (request.input()) {
            case Input.Write write -> write.at();
            case Input.Redstone redstone -> redstone.at();
            case Input.Routine routine -> routine.facility().anchor();
        };
        if (input.realm() instanceof Realm.Frame(UUID structure)) {
            scanned.add(structure);
        }
        if (scanned.isEmpty()) {
            request.scan().frames().forEach(frame -> scanned.add(frame.id()));
        }
        JsonArray rows = new JsonArray();
        int along = 0;
        double farthest = 0;
        for (Structure structure : structures) {
            UUID original = structure.copy.original();
            String name = "structure " + original.toString().substring(0, 8);
            JsonObject row = new JsonObject();
            row.addProperty("id", original.toString());
            // What came along (a walker's legs) is told briefly: how far it went, and whether it held together.
            if (!scanned.contains(original)) {
                row.addProperty("brought_along", true);
                along++;
                if (structure.first != null && structure.last != null) {
                    Vec3 shift = structure.last.position().subtract(structure.first.position());
                    row.add("moved", JSON.toJsonTree(List.of(round(shift.x), round(shift.y), round(shift.z))));
                    farthest = Math.max(farthest, shift.length());
                }
            } else if (structure.first != null && structure.last != null) {
                Vec3 from = structure.first.position();
                Vec3 to = structure.last.position();
                double dx = round(to.x - from.x);
                double dy = round(to.y - from.y);
                double dz = round(to.z - from.z);
                row.add("moved", JSON.toJsonTree(List.of(dx, dy, dz)));
                row.add("at", JSON.toJsonTree(List.of(round(to.x), round(to.y), round(to.z))));
                Quaterniond turn = new Quaterniond(structure.last.orientation())
                    .mul(new Quaterniond(structure.first.orientation()).conjugate());
                Vector3d euler = turn.getEulerAnglesYXZ(new Vector3d());
                // Minecraft's yaw grows clockwise seen from above; a rotation about +Y turns the other way.
                double yaw = round(-Math.toDegrees(euler.y));
                double pitch = round(Math.toDegrees(euler.x));
                double roll = round(Math.toDegrees(euler.z));
                Map<String, Double> turned = new LinkedHashMap<>();
                turned.put("yaw", yaw);
                turned.put("pitch", pitch);
                turned.put("roll", roll);
                row.add("turned", JSON.toJsonTree(turned));
                List<String> parts = new ArrayList<>();
                if (Math.abs(dx) >= 0.05 || Math.abs(dy) >= 0.05 || Math.abs(dz) >= 0.05) {
                    parts.add("moved " + direction(dx, dy, dz));
                }
                if (Math.abs(yaw) >= 0.5) {
                    parts.add("turned " + signed(yaw) + " degrees " + (yaw > 0 ? "clockwise" : "anticlockwise")
                        + " seen from above");
                }
                if (Math.abs(pitch) >= 0.5 || Math.abs(roll) >= 0.5) {
                    parts.add("tilted " + signed(pitch) + " pitch, " + signed(roll) + " roll");
                }
                if (!parts.isEmpty()) {
                    summary.add(name + " " + String.join(", ", parts));
                }
            }
            if (structure.endedAt != null) {
                row.addProperty("ended_tick", structure.endedAt);
                if (structure.left) {
                    row.addProperty("left_area", true);
                    summary.add(name + " left the copied area at tick " + structure.endedAt + " and was removed");
                } else {
                    summary.add(name + " ended at tick " + structure.endedAt + " (broke apart or was removed)");
                }
            }
            rows.add(row);
        }
        if (along > 0) {
            summary.add(along + " attached structures came along with them"
                + (farthest >= 0.05 ? " and moved up to " + round(farthest) + " blocks" : ""));
        }
        return rows;
    }

    private static String direction(double dx, double dy, double dz) {
        List<String> parts = new ArrayList<>();
        if (dx != 0) {
            parts.add(signed(dx) + " X (" + (dx > 0 ? "east" : "west") + ")");
        }
        if (dy != 0) {
            parts.add(signed(dy) + " Y (" + (dy > 0 ? "up" : "down") + ")");
        }
        if (dz != 0) {
            parts.add(signed(dz) + " Z (" + (dz > 0 ? "south" : "north") + ")");
        }
        return String.join(", ", parts);
    }

    private static String signed(double value) {
        return (value > 0 ? "+" : "") + value;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String describe(BlockState state) {
        String text = state.toString();
        int start = text.indexOf('{');
        int end = text.indexOf('}');
        String block = id(state);
        return start >= 0 && end > start && end + 1 < text.length() ? block + text.substring(end + 1) : block;
    }

    /** Where a sandbox cell is in the real world's terms: its realm and coordinates there. */
    private WorldPos address(BlockPos pos) {
        Optional<WorldPos> scanned = request.scan().address(pos);
        if (scanned.isPresent()) {
            return scanned.get();
        }
        for (Region region : regions) {
            if (region.box().isInside(pos) && region.frame().isPresent()) {
                WorldSpaces.Frame frame = region.frame().get();
                return new WorldPos(new Realm.Frame(frame.id()), frame.local(pos));
            }
        }
        return new WorldPos(Realm.of(source), pos);
    }

    private JsonElement at(BlockPos pos) {
        WorldPos address = address(pos);
        BlockPos cell = address.cell();
        if (address.realm() instanceof Realm.Frame(UUID structure)) {
            JsonObject at = new JsonObject();
            at.add("cell", JSON.toJsonTree(List.of(cell.getX(), cell.getY(), cell.getZ())));
            at.addProperty("structure", structure.toString());
            return at;
        }
        return JSON.toJsonTree(List.of(cell.getX(), cell.getY(), cell.getZ()));
    }

    private String label(BlockPos pos) {
        WorldPos address = address(pos);
        String known = ids.get(address);
        if (known != null) {
            return known;
        }
        BlockPos cell = address.cell();
        String id = BuiltInRegistries.BLOCK.getKey(sandbox.getBlockState(pos).getBlock()).getPath() + "@"
            + cell.getX() + "," + cell.getY() + "," + cell.getZ();
        return address.realm() instanceof Realm.Frame(UUID structure)
            ? id + "~" + structure.toString().substring(0, 8) : id;
    }
}
