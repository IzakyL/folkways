package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record CommissionPacket(UUID colonyId, ResourceLocation pattern, CompoundTag hint, CompoundTag settings,
        Direction facing, String siteName, boolean raiseInCreative) implements CustomPacketPayload {

    private static final long MAX_TAG_BYTES = 256 * 1024L;

    public static final Type<CommissionPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "commission"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CommissionPacket> STREAM_CODEC =
        StreamCodec.ofMember(CommissionPacket::encode, CommissionPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(colonyId);
        buffer.writeResourceLocation(pattern);
        buffer.writeNbt(hint);
        buffer.writeNbt(settings);
        buffer.writeEnum(facing);
        buffer.writeUtf(SiteNames.typed(siteName), SiteNames.WIRE);
        buffer.writeBoolean(raiseInCreative);
    }

    private static CommissionPacket decode(RegistryFriendlyByteBuf buffer) {
        return new CommissionPacket(
            buffer.readUUID(),
            buffer.readResourceLocation(),
            tagOf(buffer),
            tagOf(buffer),
            buffer.readEnum(Direction.class),
            buffer.readUtf(SiteNames.WIRE),
            buffer.readBoolean());
    }

    private static CompoundTag tagOf(RegistryFriendlyByteBuf buffer) {
        CompoundTag read = (CompoundTag) buffer.readNbt(NbtAccounter.create(MAX_TAG_BYTES));
        return read == null ? new CompoundTag() : read;
    }
}
