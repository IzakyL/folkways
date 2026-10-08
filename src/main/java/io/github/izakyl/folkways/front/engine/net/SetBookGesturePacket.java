package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.item.BookGestures;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;

public record SetBookGesturePacket(InteractionHand hand, Shape.Gesture gesture)
    implements CustomPacketPayload {

    public static final Type<SetBookGesturePacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "set_book_gesture")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SetBookGesturePacket> STREAM_CODEC = StreamCodec.composite(
        NeoForgeStreamCodecs.enumCodec(InteractionHand.class), SetBookGesturePacket::hand,
        BookGestures.STREAM_CODEC, SetBookGesturePacket::gesture,
        SetBookGesturePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
