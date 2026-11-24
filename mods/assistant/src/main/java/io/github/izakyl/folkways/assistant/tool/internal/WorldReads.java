package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.api.ToolSpec;
import io.github.izakyl.folkways.core.script.api.Workers;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

public final class WorldReads {

    private WorldReads() {
    }

    static boolean permitted(ServerPlayer player) {
        return player.hasPermissions(2) || player.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    static List<ScriptApi.Function> functions() {
        JsonObject position = new JsonObject();
        position.add("dimension", type("string"));
        for (String axis : List.of("x", "y", "z")) {
            position.add(axis, type("integer"));
        }
        JsonObject at = ToolRegistry.spec("at", "Position", position, "dimension", "x", "y", "z").schema();
        JsonObject block = new JsonObject();
        block.add("at", at);
        JsonObject entities = new JsonObject();
        entities.add("at", at);
        entities.add("to", ToolRegistry.spec("to", "Cell", position.deepCopy(), "x", "y", "z").schema());
        entities.add("limit", type("integer"));
        JsonObject registry = new JsonObject();
        registry.add("kind", type("string"));
        registry.add("id", type("string"));
        registry.add("limit", type("integer"));
        String example = "{\"dimension\":\"minecraft:overworld\",\"x\":0,\"y\":64,\"z\":0}";
        JsonObject players = new JsonObject();
        players.add("limit", type("integer"));
        return List.of(
                function("block", "world.block(at)", block, new String[]{"at"},
                        "Read block state and block entity SNBT in a loaded chunk, independently of colony holdings.",
                        "dict: id, properties, nbt (SNBT or null)", "result = world.block(" + example + ")"),
                function("entities", "world.entities(at, to, limit=None)", entities, new String[]{"at", "to"},
                        "Read entities in an inclusive box of at most 64 blocks per axis; all chunks must be loaded. "
                                + "limit defaults to 64 and must be 1..128. NBT is SNBT.",
                        "dict: entities [{type,uuid,name,position,nbt}], limited",
                        "result = world.entities(" + example + ", {\"x\":5,\"y\":68,\"z\":5}, limit=10)"),
                function("registry", "world.registry(kind, id=None, limit=None)", registry, new String[]{"kind"},
                        "List blocks, items or entity_types. limit defaults to 64, range 1..128; ids are sorted.",
                        "dict: ids, limited", "result = world.registry(\"items\", limit=10)"),
                function("players", "world.players(limit=None)", players, new String[]{},
                        "List online players with UUID, localizable name and position, independently of colonies. "
                                + "limit defaults to 64 and must be 1..128.",
                        "dict: players [{uuid,name,position}], limited", "result = world.players(limit=10)"));
    }

    private static JsonObject type(String type) {
        JsonObject field = new JsonObject();
        field.addProperty("type", type);
        return field;
    }

    private static ScriptApi.Function function(String name, String signature, JsonObject fields, String[] required,
            String description, String returns, String example) {
        ToolSpec spec = ToolRegistry.spec("world." + name, description, fields, required);
        return new ScriptApi.Function("world." + name, name, signature, spec, returns, example);
    }

    public static ToolReply read(ServerPlayer player, String name, JsonObject args) {
        if (!permitted(player)) {
            return ToolReply.error("permission_denied");
        }
        try {
            return switch (name) {
                case "block" -> block(player, args.getAsJsonObject("at"));
                case "entities" -> entities(player, args);
                case "registry" -> registry(args);
                case "players" -> players(player, args);
                default -> ToolReply.error("unknown_function");
            };
        } catch (ActionRegistry.Refused refusal) {
            return ToolReply.error(refusal.getMessage());
        } catch (IllegalArgumentException refusal) {
            return ToolReply.error("invalid_arguments");
        }
    }

    private static ServerLevel level(ServerPlayer player, JsonObject at) {
        ServerLevel level = player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(at.get("dimension").getAsString())));
        if (level == null) {
            throw new ActionRegistry.Refused("unloaded");
        }
        return level;
    }

    private static BlockPos pos(JsonObject at) {
        return new BlockPos(integer(at.get("x")), integer(at.get("y")), integer(at.get("z")));
    }

    private static void loaded(ServerLevel level, BlockPos at) {
        if (!level.hasChunkAt(at) || !level.isInWorldBounds(at)) {
            throw new ActionRegistry.Refused("unloaded");
        }
    }

    private static ToolReply block(ServerPlayer player, JsonObject at) {
        ServerLevel level = level(player, at);
        BlockPos pos = pos(at);
        loaded(level, pos);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            throw new ActionRegistry.Refused("unloaded");
        }
        JsonObject result = new JsonObject();
        BlockState state = chunk.getBlockState(pos);
        result.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        JsonObject properties = new JsonObject();
        state.getProperties().forEach(key -> properties.addProperty(key.getName(), property(state, key)));
        result.add("properties", properties);
        BlockEntity entity = chunk.getBlockEntities().get(pos);
        result.addProperty("nbt", entity == null ? null
                : entity.saveWithFullMetadata(player.registryAccess()).toString());
        return new ToolReply(result, List.of());
    }

    private static <T extends Comparable<T>> String property(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static ToolReply entities(ServerPlayer player, JsonObject args) {
        JsonObject at = args.getAsJsonObject("at");
        ServerLevel level = level(player, at);
        BlockPos from = pos(at);
        BlockPos to = pos(args.getAsJsonObject("to"));
        for (int axis = 0; axis < 3; axis++) {
            long distance = switch (axis) {
                case 0 -> (long) from.getX() - to.getX();
                case 1 -> (long) from.getY() - to.getY();
                default -> (long) from.getZ() - to.getZ();
            };
            if (Math.abs(distance) >= 64) {
                throw new ActionRegistry.Refused("bounds_limit");
            }
        }
        loaded(level, from);
        loaded(level, to);
        for (int x = Math.min(from.getX(), to.getX()) >> 4; x <= Math.max(from.getX(), to.getX()) >> 4; x++) {
            for (int z = Math.min(from.getZ(), to.getZ()) >> 4; z <= Math.max(from.getZ(), to.getZ()) >> 4; z++) {
                loaded(level, new BlockPos(x * 16, from.getY(), z * 16));
            }
        }
        AABB box = new AABB(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()),
                Math.min(from.getZ(), to.getZ()), Math.max(from.getX(), to.getX()) + 1.0,
                Math.max(from.getY(), to.getY()) + 1.0, Math.max(from.getZ(), to.getZ()) + 1.0);
        int limit = limit(args);
        List<Entity> found = new ArrayList<>();
        level.getEntities(EntityTypeTest.forClass(Entity.class), box, entity -> true, found, limit + 1);
        ToolText text = new ToolText();
        JsonArray entities = new JsonArray();
        for (Entity entity : found) {
            Workers.checkTime();
            if (entities.size() == limit) {
                break;
            }
            JsonObject row = entity(entity, text);
            row.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            CompoundTag nbt = new CompoundTag();
            entity.saveWithoutId(nbt);
            row.addProperty("nbt", nbt.toString());
            entities.add(row);
        }
        JsonObject result = new JsonObject();
        result.add("entities", entities);
        result.addProperty("limited", found.size() > limit);
        return text.reply(result);
    }

    private static int limit(JsonObject args) {
        int limit = args.has("limit") ? integer(args.get("limit")) : 64;
        if (limit < 1 || limit > 128) {
            throw new ActionRegistry.Refused("bounds_limit");
        }
        return limit;
    }

    private static int integer(JsonElement value) {
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Expected a 32-bit integer", invalid);
        }
    }

    private static ToolReply registry(JsonObject args) {
        Registry<?> registry = switch (args.get("kind").getAsString()) {
            case "blocks" -> BuiltInRegistries.BLOCK;
            case "items" -> BuiltInRegistries.ITEM;
            case "entity_types" -> BuiltInRegistries.ENTITY_TYPE;
            default -> throw new ActionRegistry.Refused("unknown_registry");
        };
        JsonArray ids = new JsonArray();
        int limit = limit(args);
        List<String> found = registry.keySet().stream().map(ResourceLocation::toString).sorted()
                .filter(id -> !args.has("id") || id.equals(args.get("id").getAsString())).toList();
        found.stream().limit(limit).forEach(ids::add);
        JsonObject result = new JsonObject();
        result.add("ids", ids);
        result.addProperty("limited", found.size() > limit);
        return new ToolReply(result, List.of());
    }

    private static ToolReply players(ServerPlayer player, JsonObject args) {
        int limit = limit(args);
        ToolText text = new ToolText();
        JsonArray players = new JsonArray();
        List<ServerPlayer> online = player.getServer().getPlayerList().getPlayers();
        for (int index = 0; index < Math.min(limit, online.size()); index++) {
            players.add(entity(online.get(index), text));
        }
        JsonObject result = new JsonObject();
        result.add("players", players);
        result.addProperty("limited", online.size() > limit);
        return text.reply(result);
    }

    private static JsonObject entity(Entity entity, ToolText text) {
        JsonObject row = new JsonObject();
        row.addProperty("uuid", entity.getUUID().toString());
        row.add("name", text.text(entity.getDisplayName()));
        JsonObject position = new JsonObject();
        position.addProperty("dimension", entity.level().dimension().location().toString());
        position.addProperty("x", entity.getX());
        position.addProperty("y", entity.getY());
        position.addProperty("z", entity.getZ());
        row.add("position", position);
        return row;
    }
}
