package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One immediately executable waypoint; subsequent clicks extend the same actual squad's queue. */
public record CombatWaypointPacket(long request, UUID colonyId, List<UUID> residents,
        WorldPos point, boolean attackMove) implements CustomPacketPayload {
    public static final Type<CombatWaypointPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "combat_waypoint"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CombatWaypointPacket> STREAM_CODEC =
        StreamCodec.ofMember(CombatWaypointPacket::encode, CombatWaypointPacket::decode);
    public CombatWaypointPacket {
        residents = List.copyOf(residents);
        if (request <= 0 || residents.isEmpty() || residents.size() > CombatPackets.MAX_MEMBERS) {
            throw new IllegalArgumentException("invalid waypoint selection");
        }
    }
    public CombatWaypointPacket(long request, UUID colonyId, List<UUID> residents, WorldPos point) {
        this(request, colonyId, residents, point, false);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarLong(request);
        buffer.writeUUID(colonyId);
        CombatSelectionPacket.writeIds(buffer, residents);
        point.write(buffer);
        buffer.writeBoolean(attackMove);
    }
    private static CombatWaypointPacket decode(RegistryFriendlyByteBuf buffer) {
        return new CombatWaypointPacket(buffer.readVarLong(), buffer.readUUID(),
            CombatSelectionPacket.readIds(buffer, CombatPackets.MAX_MEMBERS), WorldPos.read(buffer), buffer.readBoolean());
    }
}
