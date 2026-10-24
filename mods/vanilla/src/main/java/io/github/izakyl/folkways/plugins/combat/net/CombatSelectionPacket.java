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

public record CombatSelectionPacket(UUID colonyId, List<UUID> residents, List<WorldPos> points,
        boolean patrol) implements CustomPacketPayload {
    public static final Type<CombatSelectionPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "combat_selection"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CombatSelectionPacket> STREAM_CODEC =
        StreamCodec.ofMember(CombatSelectionPacket::encode, CombatSelectionPacket::decode);

    public CombatSelectionPacket {
        residents = List.copyOf(residents);
        points = List.copyOf(points);
        if (residents.size() > CombatPackets.MAX_MEMBERS || points.size() > CombatPackets.MAX_POINTS) {
            throw new IllegalArgumentException("selection exceeds the packet safety limit");
        }
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(colonyId);
        writeIds(buffer, residents);
        buffer.writeVarInt(points.size());
        points.forEach(point -> point.write(buffer));
        buffer.writeBoolean(patrol);
    }

    private static CombatSelectionPacket decode(RegistryFriendlyByteBuf buffer) {
        UUID colony = buffer.readUUID();
        List<UUID> residents = readIds(buffer);
        int count = buffer.readVarInt();
        if (count < 0 || count > CombatPackets.MAX_POINTS) { throw new IllegalArgumentException("invalid patrol points"); }
        List<WorldPos> points = new ArrayList<>(count);
        for (int n = 0; n < count; n++) { points.add(WorldPos.read(buffer)); }
        return new CombatSelectionPacket(colony, residents, points, buffer.readBoolean());
    }

    static void writeIds(RegistryFriendlyByteBuf buffer, java.util.Collection<UUID> ids) {
        buffer.writeVarInt(ids.size());
        ids.forEach(buffer::writeUUID);
    }

    static List<UUID> readIds(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        // Bound allocations by the packet's actual bytes, never by a gameplay member cap.
        if (count < 0 || count > buffer.readableBytes() / 16) {
            throw new IllegalArgumentException("invalid resident identifiers");
        }
        List<UUID> ids = new ArrayList<>(count);
        for (int n = 0; n < count; n++) { ids.add(buffer.readUUID()); }
        return List.copyOf(ids);
    }
}
