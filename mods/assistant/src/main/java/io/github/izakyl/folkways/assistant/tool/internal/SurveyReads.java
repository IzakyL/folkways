package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.script.api.Workers;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

public final class SurveyReads {

    private SurveyReads() {
    }

    static void register(ToolRegistry registry) {
        JsonObject block = new JsonObject();
        block.add("at", position(true));
        registry.register(ToolRegistry.spec("colony_block", "Read an authorized loaded block's id, properties, "
                + "fluid id and collision (none/partial/full). No NBT or container contents.", block, "at"),
                (context, args) -> guarded(() -> block(context, args.getAsJsonObject("at"))));
        JsonObject terrain = block.deepCopy();
        terrain.add("to", position(true));
        terrain.add("step", type("integer"));
        registry.register(ToolRegistry.spec("terrain", "Read XZ columns between inclusive corners, highest first "
                + "within the authorized vertical range (corner Y does not clip the search). "
                + "Both corners include the same dimension. step defaults to 1; at most 4096 sampled columns. "
                + "Ground excludes foliage and plants and passes through fluids. Clearance is capped at 8. "
                + "Unauthorized, unloaded or incomplete columns are unknown; no chunks are loaded.",
                terrain, "at", "to"), (context, args) -> guarded(() -> terrain(context, args)));
        registry.register(ToolRegistry.spec("surveys", "List this session's confirmed survey corridors for your "
                + "current colony, oldest first; half_width=16, below=32, above=32; at most 4 per player.",
                new JsonObject()), SurveyReads::surveys);
    }

    static JsonObject position(boolean dimension) {
        JsonObject fields = new JsonObject();
        for (String axis : List.of("x", "y", "z")) {
            fields.add(axis, type("integer"));
        }
        if (dimension) {
            fields.add("dimension", type("string"));
        }
        return ToolRegistry.spec("position", "Position", fields, dimension
                ? new String[]{"dimension", "x", "y", "z"} : new String[]{"x", "y", "z"}).schema();
    }

    private static JsonObject type(String name) {
        JsonObject field = new JsonObject();
        field.addProperty("type", name);
        return field;
    }

    static ServerLevel level(ToolContext context, String name) {
        ServerLevel level = context.player().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(name)));
        if (level == null) {
            throw new ActionRegistry.Refused("unloaded");
        }
        return level;
    }

    static BlockPos pos(JsonObject at) {
        return new BlockPos(integer(at.get("x")), integer(at.get("y")), integer(at.get("z")));
    }

    private static int integer(JsonElement value) {
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Expected a 32-bit integer", invalid);
        }
    }

    private static ToolReply guarded(Supplier<ToolReply> read) {
        try {
            return read.get();
        } catch (ActionRegistry.Refused refusal) {
            return ToolReply.error(refusal.getMessage());
        }
    }

    private static ToolReply block(ToolContext context, JsonObject at) {
        ServerLevel level = level(context, at.get("dimension").getAsString());
        BlockPos pos = pos(at);
        ReadScope scope = new ReadScope(context);
        if (!scope.contains(level, pos)) {
            return ToolReply.error("outside_survey");
        }
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !level.isInWorldBounds(pos)) {
            return ToolReply.error("unloaded");
        }
        BlockState state = chunk.getBlockState(pos);
        JsonObject data = new JsonObject();
        data.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        JsonObject properties = new JsonObject();
        state.getProperties().forEach(key -> properties.addProperty(key.getName(), property(state, key)));
        data.add("properties", properties);
        data.addProperty("fluid", BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString());
        LoadedBlocks blocks = new LoadedBlocks(level, scope);
        var collision = state.getCollisionShape(blocks, pos);
        if (blocks.error != null) {
            return ToolReply.error(blocks.error);
        }
        data.addProperty("collision", collision.isEmpty() ? "none"
                : !Shapes.joinIsNotEmpty(Shapes.block(), collision, BooleanOp.NOT_SAME) ? "full" : "partial");
        return new ToolReply(data, List.of());
    }

    private static <T extends Comparable<T>> String property(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    public static ToolReply terrain(ToolContext context, JsonObject args) {
        JsonObject at = args.getAsJsonObject("at");
        JsonObject end = args.getAsJsonObject("to");
        if (!at.get("dimension").getAsString().equals(end.get("dimension").getAsString())) {
            return ToolReply.error("mixed_dimensions");
        }
        int step = args.has("step") ? integer(args.get("step")) : 1;
        if (step < 1) {
            return ToolReply.error("bounds_limit");
        }
        BlockPos from = pos(at);
        BlockPos to = pos(end);
        long nx = Math.abs((long) from.getX() - to.getX()) / step + 1;
        long nz = Math.abs((long) from.getZ() - to.getZ()) / step + 1;
        if (nx > 4096 || nz > 4096 || nx * nz > 4096) {
            return ToolReply.error("column_limit");
        }
        ServerLevel level = level(context, at.get("dimension").getAsString());
        ReadScope scope = new ReadScope(context);
        JsonArray columns = new JsonArray();
        for (long x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()); x += step) {
            for (long z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()); z += step) {
                Workers.checkTime();
                columns.add(column(level, scope, (int) x, (int) z));
            }
        }
        JsonObject data = new JsonObject();
        data.add("columns", columns);
        data.addProperty("limited", false);
        return new ToolReply(data, List.of());
    }

    private static JsonObject column(ServerLevel level, ReadScope scope, int x, int z) {
        JsonObject row = new JsonObject();
        row.addProperty("x", x);
        row.addProperty("z", z);
        row.add("ground_y", JsonNull.INSTANCE);
        row.addProperty("surface", "unknown");
        row.add("clearance", JsonNull.INSTANCE);
        var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return row;
        }
        List<ReadScope.Interval> intervals = scope.column(level, x, z);
        if (intervals.isEmpty()) {
            return row;
        }
        // A gap before ground still makes the column unknown, rather than exposing a lower authorized band.
        ReadScope.Interval interval = intervals.getFirst();
        String surface = null;
        LoadedBlocks blocks = new LoadedBlocks(level, scope);
        for (int y = (int) interval.max(); y >= interval.min(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isInWorldBounds(pos)) {
                return row;
            }
            BlockState state = chunk.getBlockState(pos);
            if (!state.isAir() && surface == null) {
                surface = surface(state);
            }
            if (!state.isAir() && !plant(state) && !state.canBeReplaced()
                    && !state.getCollisionShape(blocks, pos).isEmpty()) {
                int clearance = 0;
                for (int above = y + 1; above <= y + 8; above++) {
                    BlockPos cell = new BlockPos(x, above, z);
                    if (!level.isInWorldBounds(cell) || !scope.contains(level, cell)) {
                        return row;
                    }
                    BlockState over = chunk.getBlockState(cell);
                    if (!over.getFluidState().isEmpty() || !over.getCollisionShape(blocks, cell).isEmpty()) {
                        break;
                    }
                    clearance++;
                }
                if (blocks.error != null) {
                    return row;
                }
                row.addProperty("ground_y", y);
                row.addProperty("surface", surface);
                row.addProperty("clearance", clearance);
                return row;
            }
        }
        return row;
    }

    private static final class LoadedBlocks implements BlockGetter {

        private final ServerLevel level;

        private final ReadScope scope;

        private String error;

        private LoadedBlocks(ServerLevel level, ReadScope scope) {
            this.level = level;
            this.scope = scope;
        }

        private LevelChunk chunk(BlockPos pos) {
            if (!scope.contains(level, pos)) {
                error = "outside_survey";
                return null;
            }
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || !level.isInWorldBounds(pos)) {
                error = "unloaded";
                return null;
            }
            return chunk;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            var chunk = chunk(pos);
            return chunk == null ? null : chunk.getBlockEntities().get(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            var chunk = chunk(pos);
            return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }
    }

    private static boolean plant(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS) || state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    private static String surface(BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return "fluid";
        }
        if (plant(state)) {
            return "plant";
        }
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.TERRACOTTA)
                || state.is(BlockTags.SNOW) || state.is(BlockTags.ICE) || state.is(BlockTags.COAL_ORES)
                || state.is(BlockTags.IRON_ORES) ? "solid" : "built";
    }

    static ToolReply surveys(ToolContext context, JsonObject args) {
        JsonArray corridors = new JsonArray();
        for (var survey : Surveys.of(context)) {
            JsonObject row = new JsonObject();
            JsonArray points = new JsonArray();
            survey.points().forEach(point -> points.add(ToolText.position(point, context.player().getServer())));
            row.addProperty("id", survey.id().toString());
            row.add("points", points);
            row.addProperty("half_width", 16);
            row.addProperty("below", 32);
            row.addProperty("above", 32);
            corridors.add(row);
        }
        JsonObject data = new JsonObject();
        data.add("surveys", corridors);
        return new ToolReply(data, List.of());
    }
}
