package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Tracks loaded attachment hosts independently of enrollment and network power. */
public final class ExternalAttachments {

    public record Target(BlockPos pos, Direction face, Object network) {
    }

    public interface Adapter {
        boolean host(BlockEntity entity);

        void targets(BlockEntity host, Consumer<Target> target);

        default boolean access(ServerLevel level, BlockPos pos, Consumer<Object> network) {
            return false;
        }

        default Object network(ServerLevel level, BlockPos pos, Direction face) {
            return null;
        }
    }

    private static final List<Adapter> ADAPTERS = new ArrayList<>();
    private static final Map<ServerLevel, ExternalAttachments> LEVELS = new WeakHashMap<>();

    private static final int RECHECK_INTERVAL = 20;

    private record Attachment(Object network, Set<WorldPos> body, Set<Object> links) {
    }

    private record Host(WorldPos at, Set<Object> access, Set<Attachment> attachments, Set<BlockPos> watched) {
    }

    private final Map<BlockPos, Adapter> hosts = new HashMap<>();
    private final Map<BlockPos, Host> resolved = new HashMap<>();
    private final Map<BlockPos, Set<BlockPos>> watchers = new HashMap<>();
    private final Set<BlockPos> pending = new LinkedHashSet<>();
    private final List<Set<BlockPos>> rechecks = IntStream.range(0, RECHECK_INTERVAL)
        .<Set<BlockPos>>mapToObj(ignored -> new HashSet<>()).toList();
    private List<ContainerFacility> facilities = List.of();
    private Map<Object, Set<Object>> groups = Map.of();
    private boolean refreshing;

    private ExternalAttachments() {
    }

    public static void register(Adapter adapter) {
        if (ADAPTERS.isEmpty()) {
            NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, ExternalAttachments::loaded);
            NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, ExternalAttachments::unloaded);
            NeoForge.EVENT_BUS.addListener(LevelEvent.Unload.class, event -> LEVELS.remove(event.getLevel()));
            NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, event -> {
                if (event.getLevel() instanceof ServerLevel level) {
                    var cache = LEVELS.get(level);
                    if (cache != null) {
                        // Native grid, interface configuration and proxy changes can occur without a block write.
                        cache.pending.addAll(cache.rechecks.get(Math.floorMod(level.getGameTime(), RECHECK_INTERVAL)));
                    }
                }
            });
        }
        ADAPTERS.add(adapter);
    }

    private static void loaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            chunk.getBlockEntities().values().forEach(entity -> track(level, entity));
            var cache = LEVELS.get(level);
            if (cache != null) {
                cache.chunkChanged(chunk.getPos());
            }
        }
    }

    private static void unloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            var cache = LEVELS.get(level);
            if (cache != null) {
                chunk.getBlockEntitiesPos().forEach(pos -> cache.setHost(pos, null));
                cache.chunkChanged(chunk.getPos());
            }
        }
    }

    private void chunkChanged(ChunkPos chunk) {
        watchers.forEach((pos, dependents) -> {
            if (new ChunkPos(pos).equals(chunk)) {
                pending.addAll(dependents);
            }
        });
    }

    public static void blockChanged(ServerLevel level, BlockPos pos) {
        var cache = LEVELS.get(level);
        if (cache != null) {
            cache.changedAt(pos);
            for (Direction face : Direction.values()) {
                cache.changedAt(pos.relative(face));
            }
        }
    }

    private void changedAt(BlockPos pos) {
        if (hosts.containsKey(pos)) {
            pending.add(pos.immutable());
        }
        var dependents = watchers.get(pos);
        if (dependents != null) {
            pending.addAll(dependents);
        }
    }

    public static void track(ServerLevel level, BlockEntity entity) {
        for (Adapter adapter : ADAPTERS) {
            if (adapter.host(entity)) {
                LEVELS.computeIfAbsent(level, ignored -> new ExternalAttachments())
                    .setHost(entity.getBlockPos(), adapter);
                return;
            }
        }
    }

    private void setHost(BlockPos pos, Adapter adapter) {
        pos = pos.immutable();
        var bucket = rechecks.get(Math.floorMod(pos.hashCode(), RECHECK_INTERVAL));
        if (adapter != null) {
            hosts.put(pos, adapter);
            bucket.add(pos);
            pending.add(pos);
        } else if (hosts.remove(pos) != null) {
            bucket.remove(pos);
            pending.add(pos);
        }
    }

    public static List<ContainerFacility> facilities(ServerLevel level) {
        var cache = current(level);
        return cache == null ? List.of() : cache.facilities;
    }

    public static Set<Object> group(ServerLevel level, Object network) {
        var cache = current(level);
        return cache == null ? Set.of() : cache.groups.getOrDefault(network, Set.of());
    }

    private static ExternalAttachments current(ServerLevel level) {
        var cache = LEVELS.get(level);
        if (cache != null && !cache.refreshing && !cache.pending.isEmpty()) {
            cache.refreshing = true;
            try {
                cache.refresh(level);
            } finally {
                cache.refreshing = false;
            }
        }
        return cache;
    }

    private Host resolve(ServerLevel level, BlockPos pos, Adapter adapter, BlockEntity entity) {
        WorldPos host = WorldPos.of(level, pos);
        Set<Object> access = new HashSet<>();
        Set<Attachment> attachments = new HashSet<>();
        Set<BlockPos> watched = new HashSet<>();
        adapter.access(level, pos, network -> access.add(network == null ? host : network));
        adapter.targets(entity, target -> {
            watched.add(target.pos().immutable());
            Set<WorldPos> body = new HashSet<>();
            Set<Object> links = new HashSet<>();
            if (level.isLoaded(target.pos())) {
                WorldPos attached = WorldPos.of(level, target.pos());
                body.addAll(ContainerFacility.chestCells(level, attached));
                Ports.itemAt(level, attached).flatMap(port -> port.proxyMembers()).ifPresent(members ->
                    members.forEach(member -> body.addAll(ContainerFacility.chestCells(level, member))));
                if (target.network() != null) {
                    for (Adapter candidate : ADAPTERS) {
                        Object inner = candidate.network(level, target.pos(), target.face());
                        if (inner != null && !inner.equals(target.network())) {
                            links.add(inner);
                        }
                    }
                }
            }
            body.forEach(cell -> {
                if (cell.in(level)) {
                    watched.add(cell.block(level));
                }
            });
            attachments.add(new Attachment(target.network() == null ? host : target.network(), body, links));
        });
        return new Host(host, access, attachments, watched);
    }

    private void refresh(ServerLevel level) {
        boolean changed = false;
        for (BlockPos pos : List.copyOf(pending)) {
            Adapter adapter = hosts.get(pos);
            BlockEntity entity = adapter != null && level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
            Host next = null;
            if (entity != null && adapter.host(entity)) {
                next = resolve(level, pos, adapter, entity);
            } else {
                setHost(pos, null);
            }
            pending.remove(pos);
            Host previous = next == null ? resolved.remove(pos) : resolved.put(pos, next);
            if (java.util.Objects.equals(previous, next)) {
                continue;
            }
            changed |= projects(previous) || projects(next);
            if (previous != null) {
                for (BlockPos watched : previous.watched()) {
                    var dependents = watchers.get(watched);
                    dependents.remove(pos);
                    if (dependents.isEmpty()) {
                        watchers.remove(watched);
                    }
                }
            }
            if (next != null) {
                next.watched().forEach(watched ->
                    watchers.computeIfAbsent(watched, ignored -> new HashSet<>()).add(pos));
            }
        }
        if (changed) {
            regroup(level);
        }
    }

    private static boolean projects(Host host) {
        return host != null && (!host.access().isEmpty() || !host.attachments().isEmpty());
    }

    private void regroup(ServerLevel level) {
        Map<Object, Set<WorldPos>> bodies = new HashMap<>();
        Map<Object, Set<WorldPos>> access = new HashMap<>();
        Map<Object, WorldPos> anchors = new HashMap<>();
        Map<Object, Set<Object>> edges = new HashMap<>();
        Map<Object, Set<Object>> reverse = new HashMap<>();
        for (Host host : resolved.values()) {
            for (Object network : host.access()) {
                bodies.computeIfAbsent(network, ignored -> new HashSet<>()).add(host.at());
                access.computeIfAbsent(network, ignored -> new HashSet<>()).add(host.at());
                anchor(anchors, network, host.at());
            }
            for (Attachment attachment : host.attachments()) {
                Object network = attachment.network();
                var body = bodies.computeIfAbsent(network, ignored -> new HashSet<>());
                body.add(host.at());
                body.addAll(attachment.body());
                anchor(anchors, network, host.at());
                for (Object inner : attachment.links()) {
                    edges.computeIfAbsent(network, ignored -> new HashSet<>()).add(inner);
                    reverse.computeIfAbsent(inner, ignored -> new HashSet<>()).add(network);
                }
            }
        }
        Map<Object, Set<Object>> components = new HashMap<>();
        Set<Object> owned = new HashSet<>();
        Set<Object> networks = new HashSet<>(edges.keySet());
        networks.addAll(reverse.keySet());
        for (Object network : networks) {
            if (components.containsKey(network)) {
                continue;
            }
            Set<Object> component = reachable(network, edges);
            component.retainAll(reachable(network, reverse));
            Set<Object> group = Set.copyOf(component);
            boolean incoming = group.stream().anyMatch(member ->
                reverse.getOrDefault(member, Set.of()).stream().anyMatch(parent -> !group.contains(parent)));
            for (Object member : group) {
                components.put(member, group);
                if (incoming) {
                    owned.add(member);
                }
            }
        }
        groups = components;
        List<ContainerFacility> next = new ArrayList<>();
        Set<Object> grouped = new HashSet<>();
        anchors.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.comparing(WorldPos::toString)))
            .forEach(entry -> {
                Object network = entry.getKey();
                if (owned.contains(network) || !grouped.add(network)) {
                    return;
                }
                Set<Object> component = components.getOrDefault(network, Set.of(network));
                grouped.addAll(component);
                Set<WorldPos> exposed = new HashSet<>();
                component.forEach(key -> exposed.addAll(access.getOrDefault(key, Set.of())));
                Set<WorldPos> body = new HashSet<>();
                reachable(network, edges).forEach(key -> body.addAll(bodies.getOrDefault(key, Set.of())));
                next.add(ContainerFacility.network(entry.getValue(), body, exposed));
            });
        if (!facilities.equals(next)) {
            facilities = List.copyOf(next);
            Facilities.ownershipChanged(level);
        }
    }

    private static void anchor(Map<Object, WorldPos> anchors, Object network, WorldPos at) {
        anchors.merge(network, at, (first, second) ->
            first.toString().compareTo(second.toString()) <= 0 ? first : second);
    }

    private static Set<Object> reachable(Object start, Map<Object, Set<Object>> edges) {
        Set<Object> visited = new HashSet<>();
        var pending = new ArrayDeque<Object>();
        pending.add(start);
        while (!pending.isEmpty()) {
            Object next = pending.removeFirst();
            if (visited.add(next)) {
                pending.addAll(edges.getOrDefault(next, Set.of()));
            }
        }
        return visited;
    }
}
