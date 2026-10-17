package io.github.izakyl.folkways.plugins.person.look;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestModelAssetsPacket(String fingerprint) implements CustomPacketPayload {
    private static final int MAX_FINGERPRINT_LENGTH = 64;

    public static final Type<RequestModelAssetsPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "resident_model_request")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestModelAssetsPacket> STREAM_CODEC =
        StreamCodec.ofMember(RequestModelAssetsPacket::encode, RequestModelAssetsPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(fingerprint, MAX_FINGERPRINT_LENGTH);
    }

    private static RequestModelAssetsPacket decode(RegistryFriendlyByteBuf buffer) {
        return new RequestModelAssetsPacket(buffer.readUtf(MAX_FINGERPRINT_LENGTH));
    }
}
