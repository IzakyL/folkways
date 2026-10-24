package io.github.izakyl.folkways.plugins.combat.control;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID)
public final class SelectionControlContent {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "combat_selection_control");
    private SelectionControlContent() { }

    public static void declare(Declaring declaring) {
        Participations.register(PersonBody.ID, Stake.urge(ID), Participation.ALWAYS);
        KitRules.supplyPolicy(ID, (colony, body) -> colony.service(ID, CombatSelectionOwnership.class)
            .map(service -> !service.reserved(body)).orElse(true));
        declaring.colony(ID, scope -> {
            CombatSelectionOwnership ownership = new CombatSelectionOwnership(scope.colony(), scope.server());
            scope.share(ownership);
            scope.urges((who, view) -> ownership.urges(who));
            scope.tick(ownership::tick);
            scope.closed(why -> ownership.close());
        });
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) { return; }
        for (var colony : Colonies.all(level.getServer())) {
            colony.service(ID, CombatSelectionOwnership.class).ifPresent(service -> {
                if (event.getEntity() instanceof ServerPlayer player) { service.clearContext(player); }
                service.removed(event.getEntity().getUUID());
            });
        }
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Colonies.all(player.getServer()).forEach(colony -> colony.service(ID, CombatSelectionOwnership.class)
                .ifPresent(service -> service.clearContext(player)));
        }
    }
}
