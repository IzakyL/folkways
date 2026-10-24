package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.plugins.combat.group.UnitGroup;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Small server observation; localized labels are composed by the receiving client. */
public record CombatOrderState(UUID groupId, String command, String phase, String result,
        int members, int missing, int stop, boolean attackMove) {
    public static final CombatOrderState NONE = new CombatOrderState(null, "none", "idle", "none", 0, 0, -1, false);

    public CombatOrderState {
        Objects.requireNonNull(command); Objects.requireNonNull(phase); Objects.requireNonNull(result);
        if (!command.matches("[a-z_]{1,64}") || !phase.matches("[a-z_]{1,64}") || !result.matches("[a-z_]{1,64}")
                || members < 0 || members > CombatPackets.MAX_MEMBERS || missing < 0 || missing > members
                || stop < -1 || stop >= CombatPackets.MAX_POINTS) {
            throw new IllegalArgumentException("invalid combat observation");
        }
    }
    static CombatOrderState of(UnitGroup group) {
        return new CombatOrderState(group.id(), group.commandKind().name().toLowerCase(Locale.ROOT),
            group.phase().name().toLowerCase(Locale.ROOT), group.endReason().name().toLowerCase(Locale.ROOT),
            group.members().size(), group.missing().size(), group.patrolStop(), group.attackMove());
    }
    void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(groupId != null);
        if (groupId != null) { buffer.writeUUID(groupId); }
        buffer.writeUtf(command, 64); buffer.writeUtf(phase, 64); buffer.writeUtf(result, 64);
        buffer.writeVarInt(members); buffer.writeVarInt(missing); buffer.writeVarInt(stop);
        buffer.writeBoolean(attackMove);
    }
    static CombatOrderState read(RegistryFriendlyByteBuf buffer) {
        UUID id = buffer.readBoolean() ? buffer.readUUID() : null;
        return new CombatOrderState(id, buffer.readUtf(64), buffer.readUtf(64), buffer.readUtf(64),
            buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }
}
