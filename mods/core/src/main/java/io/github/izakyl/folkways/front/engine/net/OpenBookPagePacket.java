package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OpenBookPagePacket(UUID colonyId, ResourceLocation page) implements CustomPacketPayload {
    public static final Type<OpenBookPagePacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "open_book_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenBookPagePacket> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, OpenBookPagePacket::colonyId,
        ResourceLocation.STREAM_CODEC, OpenBookPagePacket::page,
        OpenBookPagePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
