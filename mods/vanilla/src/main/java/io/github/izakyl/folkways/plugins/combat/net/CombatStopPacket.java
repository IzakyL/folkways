package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Stop is a replacement squad order, not a release of the player's selection or shortcuts. */
public record CombatStopPacket(long request, UUID colonyId, List<UUID> residents) implements CustomPacketPayload {
    public static final Type<CombatStopPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "combat_stop"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CombatStopPacket> STREAM_CODEC =
        StreamCodec.ofMember(CombatStopPacket::encode, buffer -> new CombatStopPacket(buffer.readVarLong(),
            buffer.readUUID(), CombatSelectionPacket.readIds(buffer, CombatPackets.MAX_MEMBERS)));
    public CombatStopPacket {
        residents = List.copyOf(residents);
        if (request <= 0 || residents.isEmpty() || residents.size() > CombatPackets.MAX_MEMBERS) {
            throw new IllegalArgumentException("invalid stop selection");
        }
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarLong(request); buffer.writeUUID(colonyId);
        CombatSelectionPacket.writeIds(buffer, residents);
    }
}
