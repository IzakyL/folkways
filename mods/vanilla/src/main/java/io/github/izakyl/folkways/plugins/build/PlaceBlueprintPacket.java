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

// A blueprint off the server's own shelf, set down by its name: the server has the file, so none is sent.
public record PlaceBlueprintPacket(UUID colonyId, ResourceLocation blueprint, String siteName, BlockPos anchor,
        boolean airIsEmpty, Rotation rotation, Mirror mirror, boolean raiseInCreative)
        implements CustomPacketPayload {

    public static final Type<PlaceBlueprintPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "place_blueprint"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceBlueprintPacket> STREAM_CODEC =
        StreamCodec.ofMember(PlaceBlueprintPacket::encode, PlaceBlueprintPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(colonyId);
        buffer.writeResourceLocation(blueprint);
        buffer.writeUtf(SiteNames.typed(siteName), SiteNames.WIRE);
        buffer.writeBlockPos(anchor);
        buffer.writeBoolean(airIsEmpty);
        buffer.writeEnum(rotation);
        buffer.writeEnum(mirror);
        buffer.writeBoolean(raiseInCreative);
    }

    private static PlaceBlueprintPacket decode(RegistryFriendlyByteBuf buffer) {
        return new PlaceBlueprintPacket(
            buffer.readUUID(),
            buffer.readResourceLocation(),
            buffer.readUtf(SiteNames.WIRE),
            buffer.readBlockPos(),
            buffer.readBoolean(),
            buffer.readEnum(Rotation.class),
            buffer.readEnum(Mirror.class),
            buffer.readBoolean());
    }
}
