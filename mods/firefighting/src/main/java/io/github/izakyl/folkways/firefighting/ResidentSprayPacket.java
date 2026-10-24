package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record ResidentSprayPacket(int actor, UUID resident, BlockPos cabinet, UUID hydrant,
        HandheldNozzleType nozzle, Vec3 origin, Vec3 direction, boolean active) implements CustomPacketPayload {
    public static final Type<ResidentSprayPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("folkways", "resident_spray"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResidentSprayPacket> STREAM_CODEC =
        StreamCodec.ofMember(ResidentSprayPacket::write, ResidentSprayPacket::read);

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC,
            (packet, context) -> context.enqueueWork(() -> ResidentSprayVisuals.accept(packet)));
    }

    static void send(Mob actor, FireHydrantCabinetBlockEntity cabinet, Vec3 origin, Vec3 direction, boolean active) {
        PacketDistributor.sendToPlayersTrackingEntity(actor, new ResidentSprayPacket(actor.getId(), actor.getUUID(),
            cabinet.getBlockPos(), cabinet.getHydrantId(), cabinet.getNozzleType(), origin, direction, active));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(actor);
        buffer.writeUUID(resident);
        buffer.writeBlockPos(cabinet);
        buffer.writeUUID(hydrant);
        buffer.writeEnum(nozzle);
        buffer.writeDouble(origin.x);
        buffer.writeDouble(origin.y);
        buffer.writeDouble(origin.z);
        buffer.writeDouble(direction.x);
        buffer.writeDouble(direction.y);
        buffer.writeDouble(direction.z);
        buffer.writeBoolean(active);
    }

    private static ResidentSprayPacket read(RegistryFriendlyByteBuf buffer) {
        return new ResidentSprayPacket(buffer.readVarInt(), buffer.readUUID(), buffer.readBlockPos(), buffer.readUUID(),
            buffer.readEnum(HandheldNozzleType.class), new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
            new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readBoolean());
    }
}
