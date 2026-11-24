package io.github.izakyl.folkways.vanilla.damagecontrol;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.engine.authority.ColonyAuthority;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Answers a bound book's poll with damage control's marks; the areas are drawn and set up like any other zone. */
final class DamageControlCommands {
    private DamageControlCommands() {
    }

    static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(FolkwaysCoreMod.NETWORK_VERSION);
        registrar.playToServer(DamageControlCommand.TYPE, DamageControlCommand.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                context.enqueueWork(() -> handle(player, packet));
            }
        });
        registrar.playToClient(DamageControlState.TYPE, DamageControlState.CODEC, (packet, context) -> {
            if (FMLEnvironment.dist.isClient()) {
                context.enqueueWork(() -> DamageControlMarks.heard(packet));
            }
        });
    }

    private static void handle(ServerPlayer player, DamageControlCommand packet) {
        ColonyAuthority.forColony(player, packet.colony()).ifPresent(authority -> {
            Colony colony = authority.colony();
            colony.service(DamageControlContent.ID, DamageControlPresence.class).ifPresent(presence ->
                PacketDistributor.sendToPlayer(player, presence.state(colony.view(player.serverLevel()))));
        });
    }
}
