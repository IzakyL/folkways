package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestRosterPacket(UUID colonyId) implements CustomPacketPayload {

    public static final Type<RequestRosterPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "request_roster"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRosterPacket> STREAM_CODEC =
        StreamCodec.ofMember(RequestRosterPacket::encode, RequestRosterPacket::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(colonyId);
    }

    private static RequestRosterPacket decode(RegistryFriendlyByteBuf buffer) {
        return new RequestRosterPacket(buffer.readUUID());
    }
}
