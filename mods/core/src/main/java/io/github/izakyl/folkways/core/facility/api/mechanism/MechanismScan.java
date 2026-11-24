package io.github.izakyl.folkways.core.facility.api.mechanism;

import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Link;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Role;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The blocks and entities one mechanism read looks at, and the graph the providers build over them. A scan covers a box
 * of one dimension together with the moving structures standing in it, or one whole moving structure. Its cells are told
 * both as addresses (in their realm) and as the storage positions the level holds them at.
 *
 * <p>Server thread only; a scan is used for one read and dropped.
 */
public final class MechanismScan {

    /** Largest side of a scanned box, in blocks. */
    public static final int MAX_SIDE = 64;
    /** Most cells one scan reads, its own box and every structure in it together. */
    public static final int MAX_CELLS = 128 * 1024;
    /** Most blocks a seed grows over before the scan gives up on finding the mechanism's edge. */
    public static final int MAX_SEED = 2048;

    public record Cell(WorldPos at, BlockPos storage) {
    }

    private final ServerLevel level;
    private final List<Cell> cells;
    private final Map<BlockPos, WorldPos> addresses = new LinkedHashMap<>();
    private final List<Entity> entities;
    private final List<WorldSpaces.Frame> frames;
    private final BoundingBox box;
    private final Realm realm;
    private final Map<Object, NodeBuilder> nodes = new LinkedHashMap<>();
    private final List<EdgeBuilder> edges = new ArrayList<>();
    private final List<MechanismGraph.Path> paths = new ArrayList<>();
    private final Set<String> unsupported = new LinkedHashSet<>();

    private MechanismScan(ServerLevel level, Realm realm, BoundingBox box, List<Cell> cells,
                          List<WorldSpaces.Frame> frames, List<Entity> entities) {
        this.level = level;
        this.realm = realm;
        this.box = box;
        this.cells = List.copyOf(cells);
        this.frames = List.copyOf(frames);
        this.entities = List.copyOf(entities);
        cells.forEach(cell -> addresses.put(cell.storage(), cell.at()));
    }

    /** A box of a dimension, inclusive, and the moving structures that stand in it. */
    public static MechanismScan box(ServerLevel level, BlockPos from, BlockPos to) {
        BlockPos min = BlockPos.min(from, to);
        BlockPos max = BlockPos.max(from, to);
        BoundingBox box = BoundingBox.fromCorners(min, max);
        if (box.getXSpan() > MAX_SIDE || box.getYSpan() > MAX_SIDE || box.getZSpan() > MAX_SIDE) {
            throw new IllegalArgumentException("bounds_limit");
        }
        loaded(level, box);
        List<Cell> cells = new ArrayList<>();
        Realm realm = Realm.of(level);
        BlockPos.betweenClosed(min, max).forEach(pos -> cells.add(new Cell(new WorldPos(realm, pos), pos.immutable())));
        List<WorldSpaces.Frame> frames = new ArrayList<>();
        AABB world = AABB.of(box).inflate(0.5);
        for (WorldSpaces.Frame frame : WorldSpaces.frames(level)) {
            Optional<BoundingBox> bounds = frame.bounds();
            if (bounds.isEmpty() || !worldBounds(frame, bounds.get()).intersects(world)) {
                continue;
            }
            frames.add(frame);
            addFrame(level, frame, bounds.get(), cells);
        }
        return new MechanismScan(level, realm, box, cells, frames, entitiesIn(level, world, frames));
    }

    /** One whole moving structure, in its own coordinates. */
    public static MechanismScan structure(ServerLevel level, UUID id) {
        WorldSpaces.Frame frame = WorldSpaces.frame(level, id).filter(found -> found.bounds().isPresent())
            .orElseThrow(() -> new IllegalArgumentException("unloaded"));
        BoundingBox bounds = frame.bounds().orElseThrow();
        List<Cell> cells = new ArrayList<>();
        addFrame(level, frame, bounds, cells);
        AABB world = worldBounds(frame, bounds);
        return new MechanismScan(level, new Realm.Frame(id), bounds, cells, List.of(frame),
            entitiesIn(level, world, List.of(frame)));
    }

    /**
     * The box around a mechanism that reaches `seed`: the mechanical blocks joined to it (anything with a block entity,
     * redstone parts, doors) and one block around them, so a door leaf on a piston's face is in.
     */
    public static MechanismScan seed(ServerLevel level, BlockPos seed) {
        if (!level.isLoaded(seed)) {
            throw new IllegalArgumentException("unloaded");
        }
        Set<BlockPos> seen = new LinkedHashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(seed.immutable());
        seen.add(seed.immutable());
        BoundingBox grown = BoundingBox.fromCorners(seed, seed);
        while (!open.isEmpty() && seen.size() < MAX_SEED) {
            BlockPos at = open.poll();
            for (Direction direction : Direction.values()) {
                BlockPos next = at.relative(direction);
                if (seen.contains(next) || Math.abs(next.getX() - seed.getX()) >= MAX_SIDE / 2
                        || Math.abs(next.getY() - seed.getY()) >= MAX_SIDE / 2
                        || Math.abs(next.getZ() - seed.getZ()) >= MAX_SIDE / 2 || !level.isLoaded(next)) {
                    continue;
                }
                if (mechanical(level, next)) {
                    seen.add(next);
                    open.add(next);
                    grown.encapsulate(next);
                }
            }
        }
        BoundingBox padded = grown.inflatedBy(1);
        return box(level, new BlockPos(padded.minX(), padded.minY(), padded.minZ()),
            new BlockPos(padded.maxX(), padded.maxY(), padded.maxZ()));
    }

    private static boolean mechanical(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && (state.hasBlockEntity() || state.isSignalSource()
            || Mechanisms.mechanicalBlock(state));
    }

    private static void loaded(ServerLevel level, BoundingBox box) {
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                if (!level.hasChunk(x, z)) {
                    throw new IllegalArgumentException("unloaded");
                }
            }
        }
        if (box.minY() < level.getMinBuildHeight() || box.maxY() >= level.getMaxBuildHeight()) {
            throw new IllegalArgumentException("bounds_limit");
        }
    }

    private static void addFrame(ServerLevel level, WorldSpaces.Frame frame, BoundingBox bounds, List<Cell> cells) {
        long volume = (long) bounds.getXSpan() * bounds.getYSpan() * bounds.getZSpan();
        if (cells.size() + volume > MAX_CELLS) {
            throw new IllegalArgumentException("bounds_limit");
        }
        Realm realm = new Realm.Frame(frame.id());
        BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(),
            bounds.maxZ()).forEach(local -> {
                BlockPos storage = frame.storage(local).immutable();
                if (level.isLoaded(storage)) {
                    cells.add(new Cell(new WorldPos(realm, local.immutable()), storage));
                }
            });
    }

    static AABB worldBounds(WorldSpaces.Frame frame, BoundingBox local) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 point = frame.toWorld(new Vec3((corner & 1) == 0 ? local.minX() : local.maxX() + 1,
                (corner & 2) == 0 ? local.minY() : local.maxY() + 1, (corner & 4) == 0 ? local.minZ() : local.maxZ() + 1));
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static List<Entity> entitiesIn(ServerLevel level, AABB world, List<WorldSpaces.Frame> frames) {
        List<Entity> found = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, world.inflate(1), entity -> !(entity instanceof ServerPlayer)
                && entity.isAlive())) {
            found.add(entity);
        }
        for (WorldSpaces.Frame frame : frames) {
            frame.bounds().ifPresent(bounds -> {
                BlockPos min = frame.storage(new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()));
                BlockPos max = frame.storage(new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
                for (Entity entity : level.getEntities((Entity) null, AABB.encapsulatingFullBlocks(min, max),
                        entity -> !(entity instanceof ServerPlayer) && entity.isAlive() && !found.contains(entity))) {
                    found.add(entity);
                }
            });
        }
        return found;
    }

    public ServerLevel level() {
        return level;
    }

    /** The realm the scan was asked about: a dimension for a box, a structure for a whole structure. */
    public Realm realm() {
        return realm;
    }

    /** The box asked about, in the realm's coordinates. */
    public BoundingBox box() {
        return box;
    }

    public List<Cell> cells() {
        return cells;
    }

    public List<WorldSpaces.Frame> frames() {
        return frames;
    }

    public List<Entity> entities() {
        return entities;
    }

    /** Where a storage position is in the scan, if it is scanned. */
    public Optional<WorldPos> address(BlockPos storage) {
        return Optional.ofNullable(addresses.get(storage));
    }

    public boolean covers(BlockPos storage) {
        return addresses.containsKey(storage);
    }

    /** The node of a scanned block, made on first ask. */
    public NodeBuilder node(BlockPos storage) {
        WorldPos at = address(storage).orElseThrow(() -> new IllegalArgumentException(storage + " is not scanned"));
        return nodes.computeIfAbsent(at, key -> new NodeBuilder(at, Optional.empty(),
            BuiltInRegistries.BLOCK.getKey(level.getBlockState(storage).getBlock())));
    }

    /** The node of an entity, made on first ask. */
    public NodeBuilder node(Entity entity) {
        return nodes.computeIfAbsent(entity.getUUID(), key -> new NodeBuilder(WorldSpaces.at(entity),
            Optional.of(entity.getUUID()), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
    }

    /** The node of a moving structure as a whole. */
    public NodeBuilder node(WorldSpaces.Frame frame) {
        return nodes.computeIfAbsent(frame.id(), key -> new NodeBuilder(WorldSpaces.at(level,
            BlockPos.containing(frame.toWorld(Vec3.ZERO))), Optional.of(frame.id()),
            ResourceLocation.fromNamespaceAndPath("folkways_core", "structure")));
    }

    public Optional<NodeBuilder> existing(BlockPos storage) {
        return address(storage).map(nodes::get);
    }

    public Optional<NodeBuilder> existing(Entity entity) {
        return Optional.ofNullable(nodes.get(entity.getUUID()));
    }

    public Collection<NodeBuilder> nodes() {
        return nodes.values();
    }

    public void edge(NodeBuilder from, NodeBuilder to, Link link, Map<String, Object> details) {
        if (from != to) {
            edges.add(new EdgeBuilder(from, to, link, new LinkedHashMap<>(details)));
        }
    }

    List<EdgeBuilder> edges() {
        return edges;
    }

    public void path(NodeBuilder from, NodeBuilder to, double factor, int hops, List<NodeBuilder> through,
                     Map<String, Object> details) {
        if (paths.size() < MechanismGraph.MAX_PATHS) {
            paths.add(new MechanismGraph.Path(from.id(), to.id(), factor, hops,
                through.stream().map(NodeBuilder::id).toList(), details));
        }
    }

    List<MechanismGraph.Path> paths() {
        return paths;
    }

    /** Says the scan saw something it cannot describe, in a short plain phrase. */
    public void unsupported(String what) {
        unsupported.add(what);
    }

    Set<String> unsupportedNotes() {
        return unsupported;
    }

    public static final class NodeBuilder {

        private final WorldPos at;
        private final Optional<UUID> entity;
        private final ResourceLocation block;
        private final EnumSet<Role> roles = EnumSet.noneOf(Role.class);
        private final Map<String, Object> state = new LinkedHashMap<>();
        private String kind;
        private boolean foldable;

        private NodeBuilder(WorldPos at, Optional<UUID> entity, ResourceLocation block) {
            this.at = at;
            this.entity = entity;
            this.block = block;
            this.kind = block.getPath();
        }

        public NodeBuilder kind(String kind) {
            this.kind = kind;
            return this;
        }

        public NodeBuilder role(Role role) {
            roles.add(role);
            return this;
        }

        public NodeBuilder put(String key, Object value) {
            if (value != null) {
                state.put(key, value);
            }
            return this;
        }

        /** A plain relay: with exactly two neighbours and nothing else to say, it is folded into its edge. */
        public NodeBuilder foldable(boolean foldable) {
            this.foldable = foldable;
            return this;
        }

        public boolean has(Role role) {
            return roles.contains(role);
        }

        public String kind() {
            return kind;
        }

        public WorldPos at() {
            return at;
        }

        public Optional<UUID> entity() {
            return entity;
        }

        public Map<String, Object> state() {
            return state;
        }

        public boolean folds() {
            return foldable && roles.equals(EnumSet.of(Role.TRANSMISSION));
        }

        public String id() {
            if (entity.isPresent()) {
                return kind + "#" + entity.get().toString().substring(0, 8);
            }
            String id = kind + "@" + at.cell().getX() + "," + at.cell().getY() + "," + at.cell().getZ();
            return at.realm() instanceof Realm.Frame frame ? id + "~" + frame.structure().toString().substring(0, 8) : id;
        }

        MechanismGraph.Node build() {
            return new MechanismGraph.Node(id(), kind, roles, block, at, entity, state);
        }
    }

    record EdgeBuilder(NodeBuilder from, NodeBuilder to, Link link, Map<String, Object> details) {
    }
}
