package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroup;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Common packets and a client snapshot, without loading any client-only Minecraft classes. */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
public final class CombatPackets {
    public static final int MAX_MEMBERS = 256;
    public static final int MAX_POINTS = 16;
    // Selection leases, shortcut operations and immediate waypoint acknowledgements must match on both sides.
    private static final String NETWORK_VERSION = "6";
    private record Snapshot(UUID colony, WorldPos at, Set<UUID> residents) { }
    private static volatile Snapshot snapshot;
    private static long requested;
    private static long received;
    private static volatile long revision;
    private static volatile CombatControlReply control;
    private static volatile CombatWaypointReply route;
    private static volatile CombatOrderState status = CombatOrderState.NONE;
    private static long requestedControl, receivedControl, requestedRoute, receivedRoute;
    private static long lastControlMutation;
    private static volatile long controlRevision, routeRevision;
    private static final Map<ServerPlayer, Long> controlRequests = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ServerPlayer, Long> waypointRequests = Collections.synchronizedMap(new WeakHashMap<>());

    private CombatPackets() { }

    /** True means sent; server acceptance is reported separately in the action bar. */
    public static boolean submit(List<UUID> residents, List<WorldPos> points, boolean patrol) {
        return submit(residents, points, patrol ? 0 : -1);
    }

    public static boolean submit(List<UUID> residents, List<WorldPos> points, int cycleStart) {
        return submit(residents, points, cycleStart, false);
    }

    public static boolean submit(List<UUID> residents, List<WorldPos> points, int cycleStart, boolean attackMove) {
        Snapshot current = snapshot;
        if (current == null || current.colony() == null || residents.isEmpty() || residents.size() > MAX_MEMBERS
                || points.isEmpty() || points.size() > MAX_POINTS
                || !SelectionOrders.validCycle(points.size(), cycleStart) || !selected(residents)) { return false; }
        PacketDistributor.sendToServer(new CombatSelectionPacket(current.colony(), residents, points, cycleStart, ++requestedRoute, attackMove));
        return true;
    }

    public static boolean attack(List<UUID> residents, UUID target) {
        Snapshot current = snapshot;
        if (current == null || current.colony() == null || target == null || residents.isEmpty()
                || residents.size() > MAX_MEMBERS || !selected(residents)) { return false; }
        PacketDistributor.sendToServer(new CombatAttackPacket(current.colony(), residents, target, ++requestedRoute));
        return true;
    }

    public static boolean append(List<UUID> residents, WorldPos point) {
        return append(residents, point, false);
    }

    public static boolean append(List<UUID> residents, WorldPos point, boolean attackMove) {
        Snapshot current = snapshot;
        if (current == null || current.colony() == null || residents.isEmpty()
                || residents.size() > MAX_MEMBERS || !selected(residents)) { return false; }
        PacketDistributor.sendToServer(new CombatWaypointPacket(++requestedRoute, current.colony(), residents, point, attackMove));
        return true;
    }

    public static boolean stop(List<UUID> residents) {
        Snapshot current = snapshot;
        if (current == null || current.colony() == null || residents.isEmpty()
                || residents.size() > MAX_MEMBERS || !selected(residents)) { return false; }
        PacketDistributor.sendToServer(new CombatStopPacket(++requestedRoute, current.colony(), residents));
        return true;
    }

    public static boolean release(List<UUID> residents) {
        return !residents.isEmpty() && selected(residents)
            && control(CombatControlPacket.Operation.RELEASE, 0, residents);
    }

    public static CombatOrderState state() { return status; }

    private static boolean selected(List<UUID> residents) {
        CombatControlReply current = control;
        return !controlPending() && current != null && snapshot != null
            && current.colonyId().equals(snapshot.colony()) && current.at().sameRealm(snapshot.at())
            && Set.copyOf(current.selection()).containsAll(residents);
    }

    public static boolean select(List<UUID> residents) { return control(CombatControlPacket.Operation.SELECT, 0, residents); }
    public static boolean bind(int slot) { return control(CombatControlPacket.Operation.BIND, slot, List.of()); }
    public static boolean recall(int slot) { return control(CombatControlPacket.Operation.RECALL, slot, List.of()); }
    public static boolean heartbeat() { return control(CombatControlPacket.Operation.HEARTBEAT, 0, List.of()); }
    public static boolean suspendControl() { return control(CombatControlPacket.Operation.SUSPEND, 0, List.of()); }
    public static void releaseControl() { control(CombatControlPacket.Operation.CLEAR, 0, List.of()); }
    private static boolean control(CombatControlPacket.Operation operation, int slot, List<UUID> residents) {
        Snapshot current = snapshot;
        if (current == null || current.colony() == null || residents.size() > MAX_MEMBERS
                || slot < 0 || slot > 9
                || (operation == CombatControlPacket.Operation.BIND || operation == CombatControlPacket.Operation.RECALL) && slot == 0) {
            return false;
        }
        long request = ++requestedControl;
        if (operation != CombatControlPacket.Operation.HEARTBEAT) { lastControlMutation = request; }
        PacketDistributor.sendToServer(new CombatControlPacket(request, current.colony(), operation, slot, residents));
        return true;
    }
    public static Optional<CombatControlReply> controls() { return Optional.ofNullable(control); }
    public static boolean controlPending() { return lastControlMutation > receivedControl; }
    public static long controlRevision() { return controlRevision; }
    public static Optional<CombatWaypointReply> route() { return Optional.ofNullable(route); }
    public static long routeRevision() { return routeRevision; }
    /** Correlates one sent order with its acknowledgement without inventing a second client counter. */
    public static long lastRouteRequest() { return requestedRoute; }
    public static boolean routePending() { return requestedRoute > receivedRoute; }
    public static void clearRoute() {
        receivedRoute = requestedRoute;
        route = null;
        routeRevision++;
    }

    public static void requestRoster() { PacketDistributor.sendToServer(new RequestRoster(++requested)); }
    /** Idempotently forget this context and reject every reply already in flight. */
    public static void clearRoster() {
        received = requested;
        if (snapshot != null) { snapshot = null; revision++; }
        receivedControl = requestedControl;
        control = null;
        status = CombatOrderState.NONE;
        controlRevision++;
        clearRoute();
    }
    public static Set<UUID> roster() { Snapshot current = snapshot; return current == null ? Set.of() : current.residents(); }
    public static Optional<UUID> rosterColony() { Snapshot current = snapshot; return current == null ? Optional.empty() : Optional.ofNullable(current.colony()); }
    public static Optional<WorldPos> rosterAt() { Snapshot current = snapshot; return current == null ? Optional.empty() : Optional.of(current.at()); }
    public static long rosterRevision() { return revision; }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(NETWORK_VERSION)
            .playToServer(CombatSelectionPacket.TYPE, CombatSelectionPacket.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        boolean accepted = fresh(waypointRequests, player, packet.request())
                            && SelectionOrders.submit(player, packet.colonyId(), packet.residents(), packet.points(), packet.cycleStart(), packet.attackMove());
                        replyRoute(player, packet.request(), packet.colonyId(), packet.residents(), accepted, CombatWaypointReply.Intent.MOVE);
                        player.displayClientMessage(Component.translatable(accepted ? "folkways.combat.saved" : "folkways.combat.error.rejected"), true);
                    }
                }))
            .playToServer(CombatAttackPacket.TYPE, CombatAttackPacket.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        boolean accepted = fresh(waypointRequests, player, packet.request())
                            && SelectionOrders.attack(player, packet.colonyId(), packet.residents(), packet.target());
                        replyRoute(player, packet.request(), packet.colonyId(), packet.residents(), accepted, CombatWaypointReply.Intent.ATTACK);
                        player.displayClientMessage(Component.translatable(accepted ? "folkways.combat.saved" : "folkways.combat.error.rejected"), true);
                    }
                }))
            .playToServer(CombatStopPacket.TYPE, CombatStopPacket.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        boolean accepted = fresh(waypointRequests, player, packet.request())
                            && SelectionOrders.stop(player, packet.colonyId(), packet.residents());
                        replyRoute(player, packet.request(), packet.colonyId(), packet.residents(), accepted, CombatWaypointReply.Intent.STOP);
                        if (!accepted) { player.displayClientMessage(Component.translatable("folkways.combat.error.rejected"), true); }
                    }
                }))
            .playToServer(CombatControlPacket.TYPE, CombatControlPacket.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) { control(player, packet); }
                }))
            .playToClient(CombatControlReply.TYPE, CombatControlReply.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (packet.request() <= receivedControl || packet.request() > requestedControl) { return; }
                    receivedControl = packet.request();
                    control = packet;
                    status = packet.state();
                    controlRevision++;
                }))
            .playToServer(CombatWaypointPacket.TYPE, CombatWaypointPacket.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        boolean fresh = fresh(waypointRequests, player, packet.request());
                        boolean accepted = fresh && SelectionOrders.append(player, packet.colonyId(), packet.residents(), packet.point(), packet.attackMove());
                        replyRoute(player, packet.request(), packet.colonyId(), packet.residents(), accepted, CombatWaypointReply.Intent.WAYPOINT);
                        if (!accepted) { player.displayClientMessage(Component.translatable("folkways.combat.error.rejected"), true); }
                    }
                }))
            .playToClient(CombatWaypointReply.TYPE, CombatWaypointReply.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (packet.request() <= receivedRoute || packet.request() > requestedRoute) { return; }
                    receivedRoute = packet.request();
                    route = packet;
                    status = packet.state();
                    routeRevision++;
                }))
            .playToServer(RequestRoster.TYPE, RequestRoster.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        var colony = Books.heldColony(player).flatMap(id -> SelectionOrders.authorized(player, id));
                        CombatOrderState state = colony.flatMap(found -> SelectionOrders.route(player, found.id(),
                            CombatSelectionOwnership.of(found).snapshot(player).selection()))
                            .map(CombatOrderState::of).orElse(CombatOrderState.NONE);
                        PacketDistributor.sendToPlayer(player, new RosterReply(packet.request(),
                            colony.map(found -> found.id()).orElse(null), WorldSpaces.at(player),
                            colony.map(found -> SelectionOrders.roster(player, found)).orElse(Set.of()), state));
                    }
                }))
            .playToClient(RosterReply.TYPE, RosterReply.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    // Accept the newest received reply even when another poll is already in flight.
                    // The consumer also matches colony and realm before using this snapshot.
                    if (packet.request() <= received || packet.request() > requested) { return; }
                    received = packet.request();
                    snapshot = new Snapshot(packet.colony(), packet.at(), packet.residents());
                    status = packet.state();
                    revision++;
                }));
    }

    private static boolean fresh(Map<ServerPlayer, Long> requests, ServerPlayer player, long request) {
        synchronized (requests) {
            if (request <= requests.getOrDefault(player, 0L)) { return false; }
            requests.put(player, request);
            return true;
        }
    }

    private static void control(ServerPlayer player, CombatControlPacket packet) {
        var continuing = SelectionOrders.continuing(player, packet.colonyId());
        var colony = switch (packet.operation()) {
            case HEARTBEAT, SUSPEND, CLEAR -> continuing;
            default -> SelectionOrders.authorized(player, packet.colonyId());
        };
        boolean accepted = false;
        CombatSelectionOwnership.Snapshot state = new CombatSelectionOwnership.Snapshot(0, List.of(),
            Collections.nCopies(9, List.of()));
        if (fresh(controlRequests, player, packet.request()) && colony.isPresent()) {
            CombatSelectionOwnership ownership = CombatSelectionOwnership.of(colony.get());
            accepted = switch (packet.operation()) {
                case SELECT -> ownership.select(player, packet.residents());
                case BIND -> ownership.bindGroup(player, packet.slot() - 1);
                case RECALL -> ownership.recallGroup(player, packet.slot() - 1);
                case HEARTBEAT -> ownership.heartbeat(player);
                case SUSPEND -> ownership.suspend(player);
                case CLEAR -> { ownership.clearContext(player); yield true; }
                case RELEASE -> ownership.releaseSelected(player, packet.residents());
            };
            state = ownership.snapshot(player);
        } else if (continuing.isPresent()) {
            // Reject new input without erasing the keeper's already accepted background task.
            state = CombatSelectionOwnership.of(continuing.get()).snapshot(player);
        }
        Optional<UnitGroup> group = SelectionOrders.route(player, packet.colonyId(), state.selection());
        PacketDistributor.sendToPlayer(player, new CombatControlReply(packet.request(), packet.colonyId(),
            WorldSpaces.at(player), state.revision(), state.selection(), state.groups(), group.map(UnitGroup::pointRoute).orElse(List.of()),
            group.map(UnitGroup::cycleStart).orElse(-1), group.map(UnitGroup::pointQueueOpen).orElse(false), accepted,
            packet.operation(), group.map(CombatOrderState::of).orElse(CombatOrderState.NONE)));
        if (!accepted && packet.operation() != CombatControlPacket.Operation.HEARTBEAT) {
            player.displayClientMessage(Component.translatable("folkways.combat.error.rejected"), true);
        }
    }

    private static void replyRoute(ServerPlayer player, long request, UUID colony, List<UUID> residents,
            boolean accepted, CombatWaypointReply.Intent intent) {
        Optional<UnitGroup> group = SelectionOrders.route(player, colony, residents);
        PacketDistributor.sendToPlayer(player, new CombatWaypointReply(request, colony, WorldSpaces.at(player), residents,
            group.map(UnitGroup::pointRoute).orElse(List.of()), group.map(UnitGroup::cycleStart).orElse(-1),
            group.map(UnitGroup::pointQueueOpen).orElse(false), accepted, intent,
            group.map(CombatOrderState::of).orElse(CombatOrderState.NONE)));
    }

    public record RequestRoster(long request) implements CustomPacketPayload {
        static final Type<RequestRoster> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "combat_roster_request"));
        static final StreamCodec<RegistryFriendlyByteBuf, RequestRoster> STREAM_CODEC = StreamCodec.ofMember(
            (packet, buffer) -> buffer.writeVarLong(packet.request()), buffer -> new RequestRoster(buffer.readVarLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RosterReply(long request, UUID colony, WorldPos at, Set<UUID> residents,
            CombatOrderState state) implements CustomPacketPayload {
        static final Type<RosterReply> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "combat_roster"));
        static final StreamCodec<RegistryFriendlyByteBuf, RosterReply> STREAM_CODEC = StreamCodec.ofMember(RosterReply::encode, RosterReply::decode);
        public RosterReply { residents = Set.copyOf(residents); }
        public RosterReply(long request, UUID colony, WorldPos at, Set<UUID> residents) {
            this(request, colony, at, residents, CombatOrderState.NONE);
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
        private void encode(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarLong(request);
            buffer.writeBoolean(colony != null);
            if (colony != null) { buffer.writeUUID(colony); }
            at.write(buffer);
            CombatSelectionPacket.writeIds(buffer, residents);
            state.write(buffer);
        }
        private static RosterReply decode(RegistryFriendlyByteBuf buffer) {
            long request = buffer.readVarLong();
            UUID colony = buffer.readBoolean() ? buffer.readUUID() : null;
            WorldPos at = WorldPos.read(buffer);
            Set<UUID> residents = Set.copyOf(CombatSelectionPacket.readIds(buffer));
            return new RosterReply(request, colony, at, residents, CombatOrderState.read(buffer));
        }
    }
}
