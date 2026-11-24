package io.github.izakyl.folkways.core.command.combat.net;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Combatant;
import io.github.izakyl.folkways.core.combat.api.FireControl;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.network.PacketDistributor;

/** Confirmation of an accepted hostile attack, for its commander's RTS overlay. */
record FocusMark(UUID colony, UUID target) implements CustomPacketPayload {
    static final Type<FocusMark> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("folkways_core", "focus_mark"));
    static final StreamCodec<RegistryFriendlyByteBuf, FocusMark> STREAM_CODEC = StreamCodec.of(
        (buffer, mark) -> {
            buffer.writeUUID(mark.colony());
            buffer.writeUUID(mark.target());
        },
        buffer -> new FocusMark(buffer.readUUID(), buffer.readUUID()));

    static void attack(ServerPlayer player, Colony colony, Entity target, LivingEntity observer) {
        if (target instanceof Mob mob && Combatant.get().hostile(mob, observer)) {
            long now = player.serverLevel().getServer().overworld().getGameTime();
            FireControl.of(colony).mark(FireControl.contact(mob, now), now + FireControl.FOCUS_TICKS);
            if (player.serverLevel().getServer().getPlayerList().getPlayer(player.getUUID()) == player) {
                PacketDistributor.sendToPlayer(player, new FocusMark(colony.id(), mob.getUUID()));
            }
        }
    }

    @Override
    public Type<FocusMark> type() {
        return TYPE;
    }
}
