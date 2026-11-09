package io.github.izakyl.folkways.vanilla.damagecontrol;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DamageControlCommand(UUID colony, Action action, UUID area, BlockPos min, BlockPos max)
    implements CustomPacketPayload {
    public enum Action { POLL, CREATE, RECORD }
    public static final UUID NONE = new UUID(0, 0);
    public static final Type<DamageControlCommand> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "damage_control_command"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DamageControlCommand> CODEC = StreamCodec.of(
        (buffer, packet) -> {
            buffer.writeUUID(packet.colony());
            buffer.writeEnum(packet.action());
            buffer.writeUUID(packet.area());
            buffer.writeBlockPos(packet.min());
            buffer.writeBlockPos(packet.max());
        }, buffer -> new DamageControlCommand(buffer.readUUID(), buffer.readEnum(Action.class), buffer.readUUID(),
            buffer.readBlockPos(), buffer.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
