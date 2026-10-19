package io.github.izakyl.folkways.plugins.person.look;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ModelAssetPacket(String fingerprint, String path, int index, int count, byte[] chunk)
        implements CustomPacketPayload {

    public static final int CHUNK_BYTES = 96 * 1024;
    private static final int MAX_FINGERPRINT_LENGTH = 64;

    public static final Type<ModelAssetPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "resident_model_asset")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ModelAssetPacket> STREAM_CODEC =
        StreamCodec.ofMember(ModelAssetPacket::encode, ModelAssetPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(fingerprint, MAX_FINGERPRINT_LENGTH);
        buffer.writeUtf(path);
        buffer.writeVarInt(index);
        buffer.writeVarInt(count);
        buffer.writeByteArray(chunk);
    }

    private static ModelAssetPacket decode(RegistryFriendlyByteBuf buffer) {
        String fingerprint = buffer.readUtf(MAX_FINGERPRINT_LENGTH);
        String path = buffer.readUtf();
        int index = buffer.readVarInt();
        int count = buffer.readVarInt();
        return new ModelAssetPacket(fingerprint, path, index, count, buffer.readByteArray(CHUNK_BYTES));
    }
}
