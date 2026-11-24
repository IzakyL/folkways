package io.github.izakyl.folkways.core.testing;

import io.github.izakyl.folkways.core.colony.internal.ColonyRegistry;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.runtime.Labor;
import io.github.izakyl.folkways.vanilla.patrol.Wards;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class Purity {

    private Purity() {
    }

    public static void assertPure(GameTestHelper helper, String label, Runnable read) {
        Map<String, Object> before = capture(helper);
        read.run();
        Map<String, Object> after = capture(helper);
        List<String> changed = new ArrayList<>();
        before.forEach((key, value) -> {
            if (!Objects.equals(value, after.get(key))) {
                changed.add(key);
            }
        });
        after.keySet().stream().filter(key -> !before.containsKey(key)).forEach(changed::add);
        helper.assertTrue(changed.isEmpty(), label + " changed " + changed);
    }

    private static Map<String, Object> capture(GameTestHelper helper) {
        // Memoization is allowed; only observable colony state is compared.
        Map<String, Object> seen = new LinkedHashMap<>();
        var server = helper.getLevel().getServer();
        // Never call the registry lookup: a cold lookup is itself one of the effects under test.
        Map<?, ?> saved = (Map<?, ?>) field(server.overworld().getDataStorage(), "cache");
        Object registry = saved.get("folkways_colonies");
        if (registry instanceof ColonyRegistry colonies) {
            seen.put("colonies", colonies.save(new CompoundTag(), server.registryAccess()).copy());
            seen.put("dirty", colonies.isDirty());
            for (var colony : colonies.colonies()) {
                Map<?, ?> services = (Map<?, ?>) field(colony.works(), "services");
                services.forEach((owner, service) -> {
                    for (String name : List.of("rooms", "beds", "moods", "bellies", "wear", "unwritten")) {
                        Object value = optionalField(service, name);
                        if (value != null) {
                            seen.put(colony.colonyId() + "/" + owner + "/" + name, freeze(value, 10));
                        }
                    }
                });
                seen.put(colony.colonyId() + "/services", List.copyOf(services.keySet()));
            }
        } else {
            seen.put("colonies", "absent");
        }
        Map<?, ?> graphs = (Map<?, ?>) field(ColonyWays.class, "GRAPHS");
        seen.put("navigation graphs", List.copyOf(graphs.keySet()));
        graphs.forEach((key, graph) -> {
            for (String name : List.of("asked", "trodden", "pending", "connecting")) {
                seen.put(key + "/" + name, freeze(field(graph, name), 6));
            }
        });
        seen.put("wards", freeze(field(Wards.class, "HELD"), 6));
        Object labor = field(Labor.class, "running");
        if (labor != null) {
            Map<?, ?> sites = (Map<?, ?>) field(labor, "sites");
            sites.forEach((site, state) -> {
                seen.put(site + "/readout", freeze(field(state, "readout"), 5));
                seen.put(site + "/untaken", freeze(field(state, "untaken"), 5));
                Object runners = field(state, "runners");
                seen.put(site + "/runners", freeze(optionalField(runners, "byResident"), 4));
            });
        }
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            for (var entity : level.getAllEntities()) {
                List<Object> attachments = new ArrayList<>();
                for (var type : NeoForgeRegistries.ATTACHMENT_TYPES) {
                    if (entity.hasData(type)) {
                        attachments.add(NeoForgeRegistries.ATTACHMENT_TYPES.getKey(type));
                    }
                }
                attachments.add(entity.serializeAttachments(server.registryAccess()));
                seen.put(dimension + "/entity/" + entity.getUUID(), attachments);
            }
            var chunks = level.getChunkSource().chunkMap;
            for (String name : List.of("updatingChunkMap", "visibleChunkMap")) {
                Map<?, ?> holders = (Map<?, ?>) field(chunks, name);
                seen.put(dimension + "/" + name, List.copyOf(holders.keySet()));
                holders.forEach((pos, value) -> {
                    LevelChunk chunk = ((ChunkHolder) value).getFullChunkFuture()
                        .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
                    seen.put(dimension + "/loaded/" + pos, chunk != null);
                    if (level == helper.getLevel() && chunk != null) {
                        // Read the existing map, never getBlockEntity (which can create one).
                        chunk.getBlockEntities().forEach((at, entity) -> {
                            if (helper.getBounds().contains(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5)) {
                                seen.put(dimension + "/block/" + at,
                                    entity.saveWithFullMetadata(server.registryAccess()).copy());
                            }
                        });
                    }
                });
            }
            Object distance = field(level.getChunkSource(), "distanceManager");
            seen.put(dimension + "/tickets", freeze(field(distance, "tickets"), 5));
            seen.put(dimension + "/forced tickets", freeze(field(distance, "forcedTickets"), 5));
            seen.put(dimension + "/tickets to release", freeze(field(distance, "ticketsToRelease"), 3));
        }
        return seen;
    }

    private static Object freeze(Object value, int depth) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CompoundTag tag) {
            return tag.copy();
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, freeze(item, depth - 1)));
            return copy;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> copy = new ArrayList<>();
            items.forEach(item -> copy.add(freeze(item, depth - 1)));
            return copy;
        }
        String type = value.getClass().getName();
        if (depth <= 0 || value instanceof ServerLevel || type.contains("$$Lambda$")) {
            return type;
        }
        if (type.startsWith("io.github.izakyl.folkways") || type.equals("net.minecraft.server.level.Ticket")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Field member : value.getClass().getDeclaredFields()) {
                if (!Modifier.isStatic(member.getModifiers())) {
                    copy.put(member.getName(), freeze(field(value, member.getName()), depth - 1));
                }
            }
            return copy;
        }
        return value.toString();
    }

    private static Object optionalField(Object target, String name) {
        try {
            return field(target, name);
        } catch (IllegalStateException absent) {
            return null;
        }
    }

    private static Object field(Object target, String name) {
        Class<?> type = target instanceof Class<?> kind ? kind : target.getClass();
        while (type != null) {
            try {
                Field member = type.getDeclaredField(name);
                member.setAccessible(true);
                return member.get(target instanceof Class<?> ? null : target);
            } catch (NoSuchFieldException absent) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
        throw new IllegalStateException("Missing observer field " + name);
    }
}
