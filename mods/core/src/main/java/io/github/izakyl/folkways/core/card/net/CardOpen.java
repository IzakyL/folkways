package io.github.izakyl.folkways.core.card.net;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Asks the client to open a window on a facility's card; the window then watches it while it stays open. */
public record CardOpen(UUID colony, UUID facility) implements CustomPacketPayload {
    public static final Type<CardOpen> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("folkways_core", "card_open"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CardOpen> STREAM_CODEC = StreamCodec.ofMember(
        (open, b) -> {
            b.writeUUID(open.colony);
            b.writeUUID(open.facility);
        },
        b -> new CardOpen(b.readUUID(), b.readUUID()));

    /** Opens the card for the player; its status follows once the window watches it. */
    public static void send(ServerPlayer player, UUID colony, UUID facility) {
        PacketDistributor.sendToPlayer(player, new CardOpen(colony, facility));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
