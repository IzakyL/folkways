package io.github.izakyl.folkways.core.card.net;

import io.github.izakyl.folkways.core.card.api.notice.Notice;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Whether a press was carried out, and if not, the refusal notice. */
public record CardReply(long request, UUID facility, Optional<Notice> refused) implements CustomPacketPayload {
    public static final Type<CardReply> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("folkways_core", "card_reply"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CardReply> STREAM_CODEC = StreamCodec.ofMember(
        (reply, b) -> {
            b.writeVarLong(reply.request);
            b.writeUUID(reply.facility);
            b.writeOptional(reply.refused, (out, refusal) -> refusal.encode(out));
        },
        b -> new CardReply(b.readVarLong(), b.readUUID(), b.readOptional(Notice::decode)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
