package io.github.izakyl.folkways.core.card.net;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A window opened or closed on a facility's card; the server keeps the watcher told while it is open. */
public record CardWatch(UUID colony, UUID facility, boolean open) implements CustomPacketPayload {
    public static final Type<CardWatch> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("folkways_core", "card_watch"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CardWatch> STREAM_CODEC = StreamCodec.ofMember(
        (watch, b) -> {
            b.writeUUID(watch.colony);
            b.writeUUID(watch.facility);
            b.writeBoolean(watch.open);
        },
        b -> new CardWatch(b.readUUID(), b.readUUID(), b.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
