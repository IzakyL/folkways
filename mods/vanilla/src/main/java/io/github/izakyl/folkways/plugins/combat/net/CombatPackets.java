package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private record Snapshot(UUID colony, WorldPos at, Set<UUID> residents) { }
    private static volatile Snapshot snapshot;
    private static long requested;
    private static long received;
    private static volatile long revision;

    private CombatPackets() { }

    /** True means sent; server acceptance is reported separately in the action bar. */
    public static boolean submit(List<UUID> residents, List<WorldPos> points, boolean patrol) {
        Snapshot current = snapshot;
        if (current == null || current.colony() == null || residents.isEmpty() || residents.size() > MAX_MEMBERS
                || points.isEmpty() || points.size() > MAX_POINTS) { return false; }
        PacketDistributor.sendToServer(new CombatSelectionPacket(current.colony(), residents, points, patrol));
        return true;
    }

    public static void requestRoster() { PacketDistributor.sendToServer(new RequestRoster(++requested)); }
    /** Idempotently forget this context and reject every reply already in flight. */
    public static void clearRoster() {
        received = requested;
        if (snapshot != null) { snapshot = null; revision++; }
    }
    public static Set<UUID> roster() { Snapshot current = snapshot; return current == null ? Set.of() : current.residents(); }
    public static Optional<UUID> rosterColony() { Snapshot current = snapshot; return current == null ? Optional.empty() : Optional.ofNullable(current.colony()); }
    public static Optional<WorldPos> rosterAt() { Snapshot current = snapshot; return current == null ? Optional.empty() : Optional.of(current.at()); }
    public static long rosterRevision() { return revision; }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(FolkwaysMod.NETWORK_VERSION)
            .playToServer(CombatSelectionPacket.TYPE, CombatSelectionPacket.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        boolean accepted = SelectionOrders.submit(player, packet.colonyId(), packet.residents(), packet.points(), packet.patrol());
                        player.displayClientMessage(Component.translatable(accepted ? "folkways.combat.saved" : "folkways.combat.error.rejected"), true);
                    }
                }))
            .playToServer(RequestRoster.TYPE, RequestRoster.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        var colony = Books.heldColony(player).flatMap(id -> SelectionOrders.authorized(player, id));
                        PacketDistributor.sendToPlayer(player, new RosterReply(packet.request(),
                            colony.map(found -> found.id()).orElse(null), WorldSpaces.at(player),
                            colony.map(found -> SelectionOrders.roster(player, found)).orElse(Set.of())));
                    }
                }))
            .playToClient(RosterReply.TYPE, RosterReply.STREAM_CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    // Accept the newest received reply even when another poll is already in flight.
                    // The consumer also matches colony and realm before using this snapshot.
                    if (packet.request() <= received || packet.request() > requested) { return; }
                    received = packet.request();
                    snapshot = new Snapshot(packet.colony(), packet.at(), packet.residents());
                    revision++;
                }));
    }

    public record RequestRoster(long request) implements CustomPacketPayload {
        static final Type<RequestRoster> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "combat_roster_request"));
        static final StreamCodec<RegistryFriendlyByteBuf, RequestRoster> STREAM_CODEC = StreamCodec.ofMember(
            (packet, buffer) -> buffer.writeVarLong(packet.request()), buffer -> new RequestRoster(buffer.readVarLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RosterReply(long request, UUID colony, WorldPos at, Set<UUID> residents) implements CustomPacketPayload {
        static final Type<RosterReply> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "combat_roster"));
        static final StreamCodec<RegistryFriendlyByteBuf, RosterReply> STREAM_CODEC = StreamCodec.ofMember(RosterReply::encode, RosterReply::decode);
        public RosterReply { residents = Set.copyOf(residents); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
        private void encode(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarLong(request);
            buffer.writeBoolean(colony != null);
            if (colony != null) { buffer.writeUUID(colony); }
            at.write(buffer);
            CombatSelectionPacket.writeIds(buffer, residents);
        }
        private static RosterReply decode(RegistryFriendlyByteBuf buffer) {
            long request = buffer.readVarLong();
            UUID colony = buffer.readBoolean() ? buffer.readUUID() : null;
            WorldPos at = WorldPos.read(buffer);
            return new RosterReply(request, colony, at, Set.copyOf(CombatSelectionPacket.readIds(buffer)));
        }
    }
}
