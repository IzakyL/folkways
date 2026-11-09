package io.github.izakyl.folkways.vanilla.damagecontrol;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.notice.Attempt;
import io.github.izakyl.folkways.core.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.front.engine.colony.ZoneDrafts;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

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
                context.enqueueWork(() -> DamageControlTool.heard(packet));
            }
        });
    }

    private static void handle(ServerPlayer player, DamageControlCommand packet) {
        ColonyAuthority.forColony(player, packet.colony()).ifPresent(authority -> {
            Colony colony = authority.colony();
            Attempt result = apply(player.serverLevel(), colony, packet);
            result.refusal().ifPresent(why -> player.displayClientMessage(why.component(), true));
            colony.service(DamageControlContent.ID, DamageControlPresence.class).ifPresent(presence ->
                PacketDistributor.sendToPlayer(player, presence.state(colony.view(player.serverLevel()))));
        });
    }

    static Attempt apply(ServerLevel level, Colony colony, DamageControlCommand packet) {
        if (!colony.id().equals(packet.colony())) {
            return Attempt.refused(DamageControlRefusal.UNSUPPORTED);
        }
        if (packet.action() == DamageControlCommand.Action.POLL) {
            return Attempt.went();
        }
        if (packet.action() == DamageControlCommand.Action.CREATE) {
            return ZoneDrafts.zone(level, colony, DamageControlContent.ID, packet.min(), packet.max(),
                ColonySettings.empty())
                .isPresent() ? Attempt.went() : Attempt.refused(DamageControlRefusal.UNSUPPORTED);
        }
        return record(level, colony, packet.area());
    }

    static Attempt record(ServerLevel level, Colony colony, UUID area) {
        var found = ColonyFront.of(colony).zonesIn(level).stream().filter(zone -> zone.id().equals(area)
            && zone.delegation().equals(DamageControlContent.ID)).findFirst();
        if (found.isEmpty()) {
            return Attempt.refused(DamageControlRefusal.UNSUPPORTED);
        }
        return colony.service(DamageControlContent.ID, DamageControlPresence.class)
            .map(presence -> presence.act(DamageControlContent.ID, "record:" + area, Optional.empty(),
                colony.view(level)))
            .orElseGet(() -> Attempt.refused(DamageControlRefusal.UNSUPPORTED));
    }
}
