package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

// One slice of a file too big for an UploadBlueprintPacket; the upload that follows them carries no file of its own.
public record BlueprintChunkPacket(int index, int count, byte[] chunk) implements CustomPacketPayload {

    public static final Type<BlueprintChunkPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "blueprint_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintChunkPacket> STREAM_CODEC =
        StreamCodec.ofMember(BlueprintChunkPacket::encode, BlueprintChunkPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(index);
        buffer.writeVarInt(count);
        buffer.writeByteArray(chunk);
    }

    private static BlueprintChunkPacket decode(RegistryFriendlyByteBuf buffer) {
        return new BlueprintChunkPacket(buffer.readVarInt(), buffer.readVarInt(),
            buffer.readByteArray(UploadBlueprintPacket.MAX_BYTES));
    }
}
