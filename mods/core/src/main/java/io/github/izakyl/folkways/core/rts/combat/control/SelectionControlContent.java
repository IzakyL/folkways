package io.github.izakyl.folkways.core.rts.combat.control;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.KitRules;
import io.github.izakyl.folkways.core.combat.api.Commands;
import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.rts.combat.command.ResidentCommands;
import io.github.izakyl.folkways.core.rts.combat.group.UnitGroups;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID)
public final class SelectionControlContent {
    static final ResourceLocation ID = Commands.SELECTION;

    private SelectionControlContent() {
    }

    public static void declare(Declaring declaring) {
        WeaponRules.lookup((who, weapon) -> Bodies.of(who.body())
            .flatMap(body -> body.colonyId())
            .flatMap(id -> Colonies.of(((ServerLevel) who.body().level()).getServer(), id))
            .map(colony -> UnitGroups.of(colony).weaponRules(who.resident().id())
                .getOrDefault(WeaponRules.category(weapon), WeaponRules.DEFAULT))
            .orElse(WeaponRules.DEFAULT));
        WeaponRules.policy((who, target, weapon, distance) -> Bodies.of(who.body())
            .flatMap(body -> body.colonyId())
            .flatMap(id -> Colonies.of(((ServerLevel) who.body().level()).getServer(), id)).map(colony -> {
                WeaponRules.Rule rule = UnitGroups.of(colony).weaponRules(who.resident().id())
                    .getOrDefault(WeaponRules.category(weapon), WeaponRules.DEFAULT);
                boolean ordered = ResidentCommands.of(colony).current(who.resident().id())
                    .filter(order -> order.mode() == ResidentCommands.Mode.ATTACK)
                    .filter(order -> target != null && order.targetId().filter(target.getUUID()::equals).isPresent())
                    .isPresent();
                boolean indirect = Loadouts.using(weapon).map(provider -> provider.indirect(weapon)).orElse(false);
                return rule.permits(ordered, distance, indirect);
            }).orElse(true));
        KitRules.supplyPolicy(
            ID,
            (colony, body) ->
                colony.service(ID, CombatSelectionOwnership.class)
                    .map(service -> !service.reserved(body))
                    .orElse(true));
        declaring.colony(
            ID,
            scope -> {
                CombatSelectionOwnership ownership = new CombatSelectionOwnership(scope.colony(), scope.server());
                scope.share(ownership);
                scope.urges((who, view) -> ownership.urges(who));
                scope.tick(ownership::tick);
                scope.closed(why -> ownership.close());
            });
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        for (var colony : Colonies.all(level.getServer())) {
            colony.service(ID, CombatSelectionOwnership.class)
                .ifPresent(
                service -> {
                    if (event.getEntity() instanceof ServerPlayer player) {
                        service.clearContext(player);
                    }
                    service.removed(event.getEntity().getUUID());
                });
        }
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Colonies.all(player.getServer())
                .forEach(
                colony ->
                    colony.service(ID, CombatSelectionOwnership.class)
                        .ifPresent(service -> service.clearContext(player)));
        }
    }
}
