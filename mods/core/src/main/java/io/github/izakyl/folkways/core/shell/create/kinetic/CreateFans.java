package io.github.izakyl.folkways.core.shell.create.kinetic;

import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Create's encased fans as processing machines: the air a fan moves through lava or fire blasts the items it passes,
 * through a campfire smokes them, through water washes them. Two readings let a facility work such a stream: which
 * processing the air applies at a cell ({@link #PROCESSING}) and how many items held there it can still change
 * ({@link #UNPROCESSED}). A fan's mechanism node describes its stream, heat sources and the depots or belts in it.
 */
public final class CreateFans {

    public static final ResourceLocation PROCESSING = id("air_processing");
    public static final ResourceLocation UNPROCESSED = id("air_unprocessed");
    static final String NONE = "none";
    // Create's own push distance by default; a stream never reaches a cell further from its fan than this.
    private static final int REACH = 32;
    // How far a still fan's line is followed to name the heat sources it would blow through.
    private static final float STILL_REACH = 20;

    private CreateFans() {
    }

    public static void install() {
        Readings.register(PROCESSING, (level, at) -> Optional.of(new Value.Text(
            stream(level, at.block(level)).map(Stream::typeId).orElse(NONE))));
        Readings.register(UNPROCESSED, new Readings.ConfiguredProbe() {
            @Override
            public Optional<Value> read(ServerLevel level, WorldPos at, Map<String, Value> config) {
                BlockPos pos = at.block(level);
                FanProcessingType type = config.get("type") instanceof Value.Text(String text) ? type(text)
                    : stream(level, pos).map(Stream::type).orElse(null);
                return Optional.of(Value.of(type == null ? 0 : unprocessed(level, pos, type)));
            }

            @Override
            public boolean valid(Map<String, Value> config) {
                return config.keySet().stream().allMatch("type"::equals)
                    && (!config.containsKey("type") || config.get("type") instanceof Value.Text(String text)
                        && type(text) != null);
            }
        });
        Readings.registerHints(CreateFans::hints);
    }

    /** The stream through a cell: the fan whose air reaches it, how far along, and what the air does there. */
    record Stream(EncasedFanBlockEntity fan, int distance, @Nullable FanProcessingType type) {

        String typeId() {
            return type == null ? NONE : id(type);
        }
    }

    static Optional<Stream> stream(ServerLevel level, BlockPos cell) {
        for (Direction flow : Direction.values()) {
            for (int below = 0; below <= 1; below++) {
                for (int distance = 1; distance <= REACH; distance++) {
                    BlockPos from = cell.above(below).relative(flow.getOpposite(), distance);
                    if (!level.isLoaded(from) || !(level.getBlockEntity(from) instanceof EncasedFanBlockEntity fan)) {
                        continue;
                    }
                    AirCurrent air = fan.getAirCurrent();
                    if (air == null || air.direction != flow || distance - 1 > air.maxDistance) {
                        continue;
                    }
                    // As Create finds the depots and belts it processes: the cell at `distance` reads the air one short.
                    return Optional.of(new Stream(fan, distance, air.getTypeAt(distance - 1)));
                }
            }
        }
        return Optional.empty();
    }

    /** Items held at the cell (on a depot or belt, in its inventory, or lying there) the processing can still change. */
    static int unprocessed(ServerLevel level, BlockPos cell, FanProcessingType type) {
        int count = 0;
        for (ItemStack stack : held(level, cell)) {
            if (changes(level, stack, type)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    // Blasting claims anything that is not fire resistant, but only a recipe turns it into something else; what comes
    // out of a stream is not taken again.
    private static boolean changes(ServerLevel level, ItemStack stack, FanProcessingType type) {
        if (!type.canProcess(stack, level)) {
            return false;
        }
        List<ItemStack> results = type.process(stack.copyWithCount(1), level);
        return results != null && !results.isEmpty() && !(results.size() == 1 && ItemStack.isSameItemSameComponents(results.getFirst(), stack));
    }

    static List<ItemStack> held(ServerLevel level, BlockPos cell) {
        List<ItemStack> stacks = new ArrayList<>();
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, cell, null);
        if (handler != null) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    stacks.add(stack);
                }
            }
        }
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(cell))) {
            stacks.add(item.getItem());
        }
        return stacks;
    }

    /** The fan's stream as its mechanism node tells it: direction, reach, heat sources, zones and what holds items there. */
    static Map<String, Object> describe(ServerLevel level, EncasedFanBlockEntity fan) {
        Map<String, Object> air = new LinkedHashMap<>();
        BlockPos at = fan.getBlockPos();
        Direction facing = fan.getBlockState().getValue(BlockStateProperties.FACING);
        AirCurrent current = fan.getAirCurrent();
        boolean running = blowing(current);
        air.put("running", running);
        air.put("side", facing.getSerializedName());
        if (running) {
            air.put("pushing", current.pushing);
            air.put("reach", CreateMechanisms.round(current.maxDistance));
        }
        float line = running ? current.maxDistance : AirCurrent.getFlowLimit(level, at, STILL_REACH, facing);
        List<Map<String, Object>> sources = new ArrayList<>();
        List<Map<String, Object>> zones = new ArrayList<>();
        Map<String, Object> zone = null;
        for (int distance = 1; distance <= Math.ceil(line); distance++) {
            BlockPos cell = at.relative(facing, distance);
            FanProcessingType source = FanProcessingType.getAt(level, cell);
            if (source != null) {
                sources.add(Map.of("type", id(source), "at", cell(cell),
                    "block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(cell).getBlock()).toString()));
            }
            FanProcessingType type = running ? current.getTypeAt(distance - 1) : null;
            if (type == null) {
                zone = null;
                continue;
            }
            if (zone == null || !zone.get("type").equals(id(type))) {
                zone = new LinkedHashMap<>(Map.of("type", id(type), "from", cell(cell)));
                zone.put("holders", new ArrayList<List<Integer>>());
                zones.add(zone);
            }
            zone.put("to", cell(cell));
            for (int below = 0; below <= 1; below++) {
                if (holds(level, cell.below(below))) {
                    @SuppressWarnings("unchecked")
                    List<List<Integer>> holders = (List<List<Integer>>) zone.get("holders");
                    holders.add(cell(cell.below(below)));
                }
            }
        }
        air.put("heat_sources", sources);
        air.put("zones", zones);
        if (!running) {
            List<List<Integer>> holders = new ArrayList<>();
            for (int distance = 1; distance <= Math.ceil(line); distance++) {
                for (int below = 0; below <= 1; below++) {
                    BlockPos cell = at.relative(facing, distance).below(below);
                    if (holds(level, cell)) {
                        holders.add(cell(cell));
                    }
                }
            }
            air.put("holders_in_line", holders);
        }
        air.put("note", "While the fan turns, items on a depot or belt in a zone (or one block under the stream), or "
            + "dropped there, take the zone's processing: blasting through lava or fire smelts ores. Read probe "
            + PROCESSING + " at a holder for the processing there and " + UNPROCESSED + " for the items it can still change.");
        return air;
    }

    /** The processing the fan's stream applies first, or none while it stands still. */
    static String processing(EncasedFanBlockEntity fan) {
        AirCurrent current = fan.getAirCurrent();
        if (!blowing(current)) {
            return NONE;
        }
        for (int distance = 0; distance <= Math.ceil(current.maxDistance); distance++) {
            FanProcessingType type = current.getTypeAt(distance);
            if (type != null) {
                return id(type);
            }
        }
        return NONE;
    }

    /** At a fan: the readings each depot or belt in its line offers, for a facility that works the stream. */
    private static List<Readings.Hint> hints(ServerLevel level, WorldPos pos) {
        if (!(level.getBlockEntity(pos.block(level)) instanceof EncasedFanBlockEntity fan)) {
            return List.of();
        }
        BlockPos at = fan.getBlockPos();
        Direction facing = fan.getBlockState().getValue(BlockStateProperties.FACING);
        AirCurrent current = fan.getAirCurrent();
        boolean running = blowing(current);
        float line = running ? current.maxDistance : AirCurrent.getFlowLimit(level, at, STILL_REACH, facing);
        List<Readings.Hint> found = new ArrayList<>();
        FanProcessingType heat = null;
        for (int distance = 1; distance <= Math.ceil(line); distance++) {
            BlockPos cell = at.relative(facing, distance);
            FanProcessingType source = FanProcessingType.getAt(level, cell);
            heat = source != null ? source : heat;
            for (int below = 0; below <= 1; below++) {
                BlockPos holder = cell.below(below);
                if (!holds(level, holder)) {
                    continue;
                }
                WorldPos where = pos.at(pos.cell().offset(holder.subtract(at)));
                found.add(new Readings.Hint(PROCESSING, where, Map.of(),
                    "the processing the fan's stream applies to items held here; none while the fan stands"));
                FanProcessingType expected = running ? current.getTypeAt(distance - 1) : heat;
                if (expected != null) {
                    found.add(new Readings.Hint(UNPROCESSED, where, Map.of("type", new Value.Text(id(expected))),
                        "items held here that " + id(expected) + " can still change; 0 once all are done"));
                }
            }
        }
        return found;
    }

    // The stream itself, not the fan's speed: Create reads every speed as 0 while the server's ticks are frozen, and the
    // stream keeps the reach it had when the fan last turned (none once it stops).
    private static boolean blowing(@Nullable AirCurrent current) {
        return current != null && current.maxDistance > 0;
    }

    private static boolean holds(ServerLevel level, BlockPos cell) {
        return BlockEntityBehaviour.get(level, cell, TransportedItemStackHandlerBehaviour.TYPE) != null;
    }

    @Nullable
    static FanProcessingType type(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : CreateBuiltInRegistries.FAN_PROCESSING_TYPE.get(key);
    }

    static String id(FanProcessingType type) {
        ResourceLocation key = CreateBuiltInRegistries.FAN_PROCESSING_TYPE.getKey(type);
        return key == null ? NONE : key.toString();
    }

    private static List<Integer> cell(BlockPos pos) {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways_core", "create/" + path);
    }
}
