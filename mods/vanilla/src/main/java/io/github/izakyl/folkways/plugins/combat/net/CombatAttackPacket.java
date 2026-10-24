package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Only identifiers cross the network; the server resolves and authenticates the live actors. */
public record CombatAttackPacket(UUID colonyId, List<UUID> residents, UUID target) implements CustomPacketPayload {
    public static final Type<CombatAttackPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "combat_attack"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CombatAttackPacket> STREAM_CODEC = StreamCodec.ofMember(
        CombatAttackPacket::encode, buffer -> new CombatAttackPacket(buffer.readUUID(),
            CombatSelectionPacket.readIds(buffer, CombatPackets.MAX_MEMBERS), buffer.readUUID()));

    public CombatAttackPacket {
        residents = List.copyOf(residents);
        if (residents.size() > CombatPackets.MAX_MEMBERS) { throw new IllegalArgumentException("too many residents"); }
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(colonyId);
        CombatSelectionPacket.writeIds(buffer, residents);
        buffer.writeUUID(target);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
