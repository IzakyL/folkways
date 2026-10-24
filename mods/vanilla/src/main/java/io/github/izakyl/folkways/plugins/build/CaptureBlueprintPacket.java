package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record CaptureBlueprintPacket(UUID colony, BlockPos from, BlockPos to, String name) implements CustomPacketPayload {
    public static final Type<CaptureBlueprintPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "capture_blueprint"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CaptureBlueprintPacket> STREAM_CODEC = StreamCodec.of(
        (buffer, packet) -> {
            buffer.writeUUID(packet.colony());
            buffer.writeBlockPos(packet.from());
            buffer.writeBlockPos(packet.to());
            buffer.writeUtf(packet.name(), 128);
        }, buffer -> new CaptureBlueprintPacket(buffer.readUUID(), buffer.readBlockPos(), buffer.readBlockPos(),
            buffer.readUtf(128)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
