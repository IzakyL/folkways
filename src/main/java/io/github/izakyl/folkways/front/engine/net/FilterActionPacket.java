package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;

public record FilterActionPacket(FilterAction action, int slotIndex, ItemStack stack) implements CustomPacketPayload {
    public static final Type<FilterActionPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "ui_filter_action")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, FilterActionPacket> STREAM_CODEC = StreamCodec.composite(
        NeoForgeStreamCodecs.enumCodec(FilterAction.class), FilterActionPacket::action,
        ByteBufCodecs.VAR_INT, FilterActionPacket::slotIndex,
        ItemStack.OPTIONAL_STREAM_CODEC, FilterActionPacket::stack,
        FilterActionPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
