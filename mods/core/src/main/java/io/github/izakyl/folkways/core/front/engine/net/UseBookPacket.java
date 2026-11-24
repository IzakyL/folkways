package io.github.izakyl.folkways.core.front.engine.net;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The bound book used on the world: on the block it was aimed at, and through the zone or path the look passed
 * through. What the colony holds there opens on its card, a block before the ground around it; anything else opens the
 * colony's panel.
 */
public record UseBookPacket(UUID colonyId, Optional<BlockPos> block, Optional<UUID> holding)
    implements CustomPacketPayload {

    public static final Type<UseBookPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, "use_book"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UseBookPacket> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, UseBookPacket::colonyId,
        ByteBufCodecs.optional(BlockPos.STREAM_CODEC), UseBookPacket::block,
        ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), UseBookPacket::holding,
        UseBookPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
