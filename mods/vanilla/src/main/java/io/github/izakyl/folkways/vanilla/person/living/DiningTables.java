package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.world.api.BlockChanges;
import io.github.izakyl.folkways.core.world.api.Keepouts;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Dining furniture integrations, separate from colony ingredient storage. */
public final class DiningTables {
    public interface Table {
        boolean matches(ServerLevel level, BlockPos table);
        Container inventory(ServerLevel level, BlockPos table);
        default Optional<BlockState> sample() { return Optional.empty(); }
        // Matching must only change on block changes or chunk replacement when opting into the cache.
        default boolean stable() { return false; }
    }
    public interface Seat {
        boolean matches(ServerLevel level, BlockPos chair);
        boolean sit(ServerLevel level, BlockPos chair, Mob body);
        default boolean available(ServerLevel level, BlockPos chair, Mob diner) { return true; }
    }
    private static final Map<ResourceLocation, Table> TABLES = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Seat> SEATS = new LinkedHashMap<>();
    private static final TagKey<Block> DINING_TABLES = tag("dining/tables");
    private static final TagKey<Block> HOUSING_TABLES = tag("housing/table");
    private static final String SEAT_MARKER = "folkways_vanilla:dining_seat";
    private record ChunkKey(ServerLevel level, int x, int z) { }
    private record TableCell(BlockState state, boolean table) { }
    private static final class TableChunk {
        final LevelChunk chunk;
        final Map<BlockPos, TableCell> cells = new HashMap<>();
        final BlockChanges.Heard watch;
        TableChunk(ServerLevel level, int x, int z, LevelChunk chunk) {
            this.chunk = chunk;
            BoundingBox box = new BoundingBox((x << 4) - 1, level.getMinBuildHeight(), (z << 4) - 1,
                (x << 4) + 16, level.getMaxBuildHeight(), (z << 4) + 16);
            watch = BlockChanges.listen(level, box, at -> cells.clear());
        }
    }
    private static final Map<ChunkKey, TableChunk> TABLE_CHUNKS = new LinkedHashMap<>(16, 0.75f, true);
    private static boolean keepoutInstalled;
    private DiningTables() { }

    /** Furniture remains reachable from beside it, but its top is never a work or walking footing. */
    static synchronized void installKeepout() {
        if (keepoutInstalled) { return; }
        Keepouts.install((level, x, y, z) -> level instanceof ServerLevel server
            && keepout(server, new BlockPos(x, y - 1, z)));
        keepoutInstalled = true;
    }

    public static synchronized void register(ResourceLocation id, Table adapter) {
        if (TABLES.putIfAbsent(id, adapter) != null) { throw new IllegalArgumentException("duplicate dining table: " + id); }
        TABLE_CHUNKS.values().forEach(chunk -> chunk.watch.close());
        TABLE_CHUNKS.clear();
    }
    public static synchronized void registerSeat(ResourceLocation id, Seat adapter) {
        if (SEATS.putIfAbsent(id, adapter) != null) { throw new IllegalArgumentException("duplicate dining seat: " + id); }
    }
    public static Optional<Container> inventory(ServerLevel level, BlockPos table) {
        if (!level.isLoaded(table)) { return Optional.empty(); }
        for (Table adapter : TABLES.values()) {
            if (adapter.matches(level, table)) { return Optional.ofNullable(adapter.inventory(level, table)); }
        }
        return Optional.empty();
    }
    /** Tags are synced to clients; adapters are installed during common setup. */
    public static boolean anyTable() {
        return !TABLES.isEmpty()
            || BuiltInRegistries.BLOCK.getTag(DINING_TABLES).filter(tables -> tables.size() > 0).isPresent()
            || BuiltInRegistries.BLOCK.getTag(HOUSING_TABLES).filter(tables -> tables.size() > 0).isPresent();
    }
    /** Resolve afresh so previews follow the current world's table tags. */
    public static Optional<BlockState> sample() {
        for (String path : List.of("dining/tables", "housing/table")) {
            var tagged = BuiltInRegistries.BLOCK.getTag(tag(path))
                .flatMap(tables -> tables.stream().findFirst()).map(table -> table.value().defaultBlockState());
            if (tagged.isPresent()) { return tagged; }
        }
        return TABLES.values().stream().flatMap(adapter -> adapter.sample().stream()).findFirst();
    }
    private static boolean keepout(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) { return false; }
        BlockState state = level.getBlockState(at);
        if (state.is(DINING_TABLES) || state.is(HOUSING_TABLES)) { return true; }
        if (TABLES.values().stream().anyMatch(adapter -> !adapter.stable())) { return table(level, at); }
        int x = at.getX() >> 4;
        int z = at.getZ() >> 4;
        LevelChunk loaded = level.getChunkSource().getChunkNow(x, z);
        if (loaded == null) { return false; }
        ChunkKey key = new ChunkKey(level, x, z);
        TableChunk chunk = TABLE_CHUNKS.get(key);
        if (chunk == null || chunk.chunk != loaded) {
            if (chunk != null) { chunk.watch.close(); }
            chunk = new TableChunk(level, x, z, loaded);
            TABLE_CHUNKS.put(key, chunk);
            if (TABLE_CHUNKS.size() > 256) {
                ChunkKey oldest = TABLE_CHUNKS.keySet().iterator().next();
                TABLE_CHUNKS.remove(oldest).watch.close();
            }
        }
        TableCell cached = chunk.cells.get(at);
        if (cached != null && cached.state == state) { return cached.table; }
        boolean found = false;
        for (Table adapter : TABLES.values()) {
            if (adapter.matches(level, at)) { found = true; break; }
        }
        chunk.cells.put(at.immutable(), new TableCell(state, found));
        return found;
    }

    public static boolean table(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) { return false; }
        var state = level.getBlockState(at);
        if (state.is(DINING_TABLES) || state.is(HOUSING_TABLES)) { return true; }
        for (Table adapter : TABLES.values()) {
            if (adapter.matches(level, at)) { return true; }
        }
        return false;
    }
    public static boolean chair(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) { return false; }
        var state = level.getBlockState(at);
        return state.is(tag("dining/chairs"))
            || state.getBlock() instanceof StairBlock && state.getValue(StairBlock.HALF) == Half.BOTTOM
            || SEATS.values().stream().anyMatch(adapter -> adapter.matches(level, at));
    }
    public static boolean available(ServerLevel level, BlockPos chair) {
        return available(level, chair, null);
    }
    /** An ongoing diner may keep its own seat; every other actual passenger blocks it. */
    public static boolean available(ServerLevel level, BlockPos chair, Mob diner) {
        if (!chair(level, chair)) { return false; }
        for (Seat adapter : SEATS.values()) {
            if (adapter.matches(level, chair)) { return adapter.available(level, chair, diner); }
        }
        return true;
    }
    public static boolean sit(ServerLevel level, BlockPos chair, Mob body) {
        if (body.level() != level || body.isPassenger() || !available(level, chair, body)) { return false; }
        for (Seat adapter : SEATS.values()) {
            if (adapter.matches(level, chair) && adapter.sit(level, chair, body)) {
                if (body.getVehicle() != null) { body.getPersistentData().putUUID(SEAT_MARKER, body.getVehicle().getUUID()); }
                return true;
            }
        }
        // Basic furniture uses the dining action's synchronized pose instead of an artificial mount.
        return false;
    }
    public static void stand(Mob body) {
        var data = body.getPersistentData();
        UUID vehicle = data.hasUUID(SEAT_MARKER) ? data.getUUID(SEAT_MARKER) : null;
        data.remove(SEAT_MARKER);
        if (vehicle != null && body.getVehicle() != null && vehicle.equals(body.getVehicle().getUUID())) {
            // Leave the resident on reachable footing beside the chair, ready for the next work route.
            io.github.izakyl.folkways.core.work.api.WorkGesture.SIT.release(body);
        }
    }
    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("folkways_vanilla", path));
    }
}
