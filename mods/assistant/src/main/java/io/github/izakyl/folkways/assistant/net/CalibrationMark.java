package io.github.izakyl.folkways.assistant.net;

import io.github.izakyl.folkways.assistant.AssistantMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * What a calibration read found, for the player who asked to see it in the world: the parts of a mechanism the
 * assistant read, or the control a probe tried and what moved in the sandbox. Positions are in world coordinates.
 */
public record CalibrationMark(String json) implements CustomPacketPayload {

    public static final int MAX_BYTES = 32 * 1024;

    public static final Type<CalibrationMark> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AssistantMod.ID, "calibration_mark"));

    public static final StreamCodec<FriendlyByteBuf, CalibrationMark> CODEC = StreamCodec.of(
            (buffer, mark) -> buffer.writeUtf(mark.json(), MAX_BYTES),
            buffer -> new CalibrationMark(buffer.readUtf(MAX_BYTES)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
