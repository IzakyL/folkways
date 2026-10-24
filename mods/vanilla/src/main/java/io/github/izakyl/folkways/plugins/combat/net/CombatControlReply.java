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

/** Only the requesting player's authoritative selection and nine shortcuts are returned. */
public record CombatControlReply(long request, UUID colonyId, WorldPos at, long revision,
        List<UUID> selection, List<List<UUID>> groups, List<WorldPos> points, int cycleStart, boolean open,
        boolean accepted) implements CustomPacketPayload {
    public static final Type<CombatControlReply> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "combat_control_reply"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CombatControlReply> STREAM_CODEC =
        StreamCodec.ofMember(CombatControlReply::encode, CombatControlReply::decode);

    public CombatControlReply {
        selection = List.copyOf(selection);
        groups = groups.stream().map(List::copyOf).toList();
        points = List.copyOf(points);
        if (selection.size() > CombatPackets.MAX_MEMBERS || groups.size() != 9
                || groups.stream().anyMatch(group -> group.size() > CombatPackets.MAX_MEMBERS)
                || points.size() > CombatPackets.MAX_POINTS) {
            throw new IllegalArgumentException("invalid combat control snapshot");
        }
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarLong(request);
        buffer.writeUUID(colonyId);
        at.write(buffer);
        buffer.writeVarLong(revision);
        CombatSelectionPacket.writeIds(buffer, selection);
        groups.forEach(group -> CombatSelectionPacket.writeIds(buffer, group));
        buffer.writeVarInt(points.size());
        points.forEach(point -> point.write(buffer));
        buffer.writeVarInt(cycleStart);
        buffer.writeBoolean(open);
        buffer.writeBoolean(accepted);
    }
    private static CombatControlReply decode(RegistryFriendlyByteBuf buffer) {
        long request = buffer.readVarLong();
        UUID colony = buffer.readUUID();
        WorldPos at = WorldPos.read(buffer);
        long revision = buffer.readVarLong();
        List<UUID> selection = CombatSelectionPacket.readIds(buffer, CombatPackets.MAX_MEMBERS);
        List<List<UUID>> groups = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            groups.add(CombatSelectionPacket.readIds(buffer, CombatPackets.MAX_MEMBERS));
        }
        int count = buffer.readVarInt();
        if (count < 0 || count > CombatPackets.MAX_POINTS) { throw new IllegalArgumentException("invalid route points"); }
        List<WorldPos> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) { points.add(WorldPos.read(buffer)); }
        return new CombatControlReply(request, colony, at, revision, selection, groups, points,
            buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
    }
}
