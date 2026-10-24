package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Ordered selection and shortcut operations, resolved against the server's current selection. */
public record CombatControlPacket(long request, UUID colonyId, Operation operation, int slot,
        List<UUID> residents) implements CustomPacketPayload {
    public enum Operation { SELECT, BIND, RECALL, HEARTBEAT, CLEAR, RELEASE }
    public static final Type<CombatControlPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "combat_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CombatControlPacket> STREAM_CODEC =
        StreamCodec.ofMember(CombatControlPacket::encode, CombatControlPacket::decode);

    public CombatControlPacket {
        residents = List.copyOf(residents);
        if (request <= 0 || residents.size() > CombatPackets.MAX_MEMBERS || slot < 0 || slot > 9
                || (operation == Operation.BIND || operation == Operation.RECALL) && slot == 0
                || operation != Operation.SELECT && operation != Operation.RELEASE && !residents.isEmpty()
                || operation == Operation.RELEASE && residents.isEmpty()) {
            throw new IllegalArgumentException("invalid combat control operation");
        }
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarLong(request);
        buffer.writeUUID(colonyId);
        buffer.writeEnum(operation);
        buffer.writeByte(slot);
        CombatSelectionPacket.writeIds(buffer, residents);
    }
    private static CombatControlPacket decode(RegistryFriendlyByteBuf buffer) {
        return new CombatControlPacket(buffer.readVarLong(), buffer.readUUID(), buffer.readEnum(Operation.class),
            buffer.readUnsignedByte(), CombatSelectionPacket.readIds(buffer, CombatPackets.MAX_MEMBERS));
    }
}
