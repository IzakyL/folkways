package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

public record UploadBlueprintPacket(UUID colonyId, String name, String siteName, byte[] file, BlockPos anchor,
        boolean airIsEmpty, Rotation rotation, Mirror mirror, boolean raiseInCreative)
        implements CustomPacketPayload {

    public static final int MAX_BYTES = 24 * 1024;

    private static final int MAX_NAME = 64;

    public static final Type<UploadBlueprintPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "upload_blueprint"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UploadBlueprintPacket> STREAM_CODEC =
        StreamCodec.ofMember(UploadBlueprintPacket::encode, UploadBlueprintPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(colonyId);
        buffer.writeUtf(name, MAX_NAME);
        buffer.writeUtf(SiteNames.typed(siteName), SiteNames.WIRE);
        buffer.writeByteArray(file);
        buffer.writeBlockPos(anchor);
        buffer.writeBoolean(airIsEmpty);
        buffer.writeEnum(rotation);
        buffer.writeEnum(mirror);
        buffer.writeBoolean(raiseInCreative);
    }

    private static UploadBlueprintPacket decode(RegistryFriendlyByteBuf buffer) {
        return new UploadBlueprintPacket(
            buffer.readUUID(),
            buffer.readUtf(MAX_NAME),
            buffer.readUtf(SiteNames.WIRE),
            buffer.readByteArray(MAX_BYTES),
            buffer.readBlockPos(),
            buffer.readBoolean(),
            buffer.readEnum(Rotation.class),
            buffer.readEnum(Mirror.class),
            buffer.readBoolean());
    }
}
