package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Books;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class RailPackets {

    private static volatile List<UUID> recognized = List.of();

    private static volatile Map<UUID, TrainCrew> crews = Map.of();

    private RailPackets() {
    }
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(FolkwaysMod.NETWORK_VERSION);
        registrar.playToServer(RequestRosterPacket.TYPE, RequestRosterPacket.STREAM_CODEC,
            RailPackets::handleRequest);
        registrar.playToClient(RosterSyncPacket.TYPE, RosterSyncPacket.STREAM_CODEC,
            RailPackets::handleSync);
    }

    private static void handleRequest(RequestRosterPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> Books.ofHeldBook(player, packet.colonyId())
            .flatMap(colony -> RailContent.presenceIn(colony.service(RailContent.ID, Object.class)))
            .ifPresent(trains -> PacketDistributor.sendToPlayer(player,
                new RosterSyncPacket(trains.taken(), trains.crews()))));
    }

    private static void handleSync(RosterSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            recognized = packet.trains();
            crews = packet.crews();
        });
    }

    static List<UUID> recognized() {
        return recognized;
    }

    static Optional<TrainCrew> crewOf(UUID train) {
        return Optional.ofNullable(crews.get(train));
    }

    static void forget() {
        recognized = List.of();
        crews = Map.of();
    }
}
