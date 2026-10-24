package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Route markers represent committed server orders, never an unsubmitted client draft. */
public record CombatWaypointReply(long request, UUID colonyId, WorldPos at, List<UUID> residents,
        List<WorldPos> points, int cycleStart, boolean open, boolean accepted, Intent intent) implements CustomPacketPayload {
    public enum Intent { MOVE, ATTACK, WAYPOINT }
    public static final Type<CombatWaypointReply> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "combat_waypoint_reply"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CombatWaypointReply> STREAM_CODEC =
        StreamCodec.ofMember(CombatWaypointReply::encode, CombatWaypointReply::decode);
    public CombatWaypointReply {
        residents = List.copyOf(residents);
        points = List.copyOf(points);
        if (residents.size() > CombatPackets.MAX_MEMBERS || points.size() > CombatPackets.MAX_POINTS) {
            throw new IllegalArgumentException("invalid committed route snapshot");
        }
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarLong(request);
        buffer.writeUUID(colonyId);
        at.write(buffer);
        CombatSelectionPacket.writeIds(buffer, residents);
        buffer.writeVarInt(points.size());
        points.forEach(point -> point.write(buffer));
        buffer.writeVarInt(cycleStart);
        buffer.writeBoolean(open);
        buffer.writeBoolean(accepted);
        buffer.writeEnum(intent);
    }
    private static CombatWaypointReply decode(RegistryFriendlyByteBuf buffer) {
        long request = buffer.readVarLong();
        UUID colony = buffer.readUUID();
        WorldPos at = WorldPos.read(buffer);
        List<UUID> residents = CombatSelectionPacket.readIds(buffer, CombatPackets.MAX_MEMBERS);
        int count = buffer.readVarInt();
        if (count < 0 || count > CombatPackets.MAX_POINTS) { throw new IllegalArgumentException("invalid route points"); }
        List<WorldPos> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) { points.add(WorldPos.read(buffer)); }
        return new CombatWaypointReply(request, colony, at, residents, points, buffer.readVarInt(),
            buffer.readBoolean(), buffer.readBoolean(), buffer.readEnum(Intent.class));
    }
}
