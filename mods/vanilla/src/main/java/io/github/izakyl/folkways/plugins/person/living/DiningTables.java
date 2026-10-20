package io.github.izakyl.folkways.plugins.person.living;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Dining furniture integrations, separate from colony ingredient storage. */
public final class DiningTables {
    public interface Table {
        boolean matches(ServerLevel level, BlockPos table);
        Container inventory(ServerLevel level, BlockPos table);
    }
    public interface Seat {
        boolean matches(ServerLevel level, BlockPos chair);
        boolean sit(ServerLevel level, BlockPos chair, Mob body);
        default boolean available(ServerLevel level, BlockPos chair, Mob diner) { return true; }
    }
    private static final Map<ResourceLocation, Table> TABLES = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Seat> SEATS = new LinkedHashMap<>();
    private static final String SEAT_MARKER = "folkways:dining_seat";
    private DiningTables() { }

    public static synchronized void register(ResourceLocation id, Table adapter) {
        if (TABLES.putIfAbsent(id, adapter) != null) { throw new IllegalArgumentException("duplicate dining table: " + id); }
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
    public static boolean table(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) { return false; }
        var state = level.getBlockState(at);
        return state.is(tag("dining/tables")) || state.is(tag("housing/table"))
            || state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.TOP
            || TABLES.values().stream().anyMatch(adapter -> adapter.matches(level, at));
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
            io.github.izakyl.folkways.core.api.work.WorkGesture.SIT.release(body);
        }
    }
    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("folkways", path));
    }
}
