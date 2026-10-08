package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class PatternPackets {

    private PatternPackets() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(FolkwaysMod.NETWORK_VERSION)
            .playToClient(TemplateLibraryPacket.TYPE, TemplateLibraryPacket.STREAM_CODEC,
                PatternPackets::handlePieces)
            .playToClient(PatternLibraryPacket.TYPE, PatternLibraryPacket.STREAM_CODEC,
                PatternPackets::handleLibrary);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            offerTo(player);
        }
    }

    public static void offerToEveryone(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            offerTo(player);
        }
    }

    private static void offerTo(ServerPlayer player) {
        if (player.connection.hasChannel(TemplateLibraryPacket.TYPE)) {
            PacketDistributor.sendToPlayer(player, new TemplateLibraryPacket(Templates.files()));
        }
        if (player.connection.hasChannel(PatternLibraryPacket.TYPE)) {
            PacketDistributor.sendToPlayer(player, new PatternLibraryPacket(Patterns.sources()));
        }
    }

    private static void handlePieces(TemplateLibraryPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> Templates.acceptFromServer(packet.files()));
    }

    private static void handleLibrary(PatternLibraryPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> Patterns.acceptFromServer(packet.sources()));
    }
}
