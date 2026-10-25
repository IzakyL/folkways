package io.github.izakyl.folkways.plugins.combat.command;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID)
public final class CommandContent {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "resident_command");

    private CommandContent() {
    }

    public static void declare(Declaring declaring) {
        Participations.register(PersonBody.ID, Stake.urge(ID), Participation.ALWAYS);
        KitRules.supplyPolicy(ID, (colony, body) -> colony.service(ID, ResidentCommands.class)
            .map(commands -> commands.current(body.id()).filter(commands::active).isEmpty()).orElse(true));
        declaring.colony(ID, scope -> {
            ResidentCommands commands = new ResidentCommands(scope.colony(), scope.server());
            scope.share(commands);
            scope.urges((who, view) -> commands.urges(who));
            scope.tick(commands::tick);
            scope.closed(why -> commands.clear());
        });
    }

    static boolean defeated(Mob target) {
        // isAlive also becomes false for a healthy discarded, unloaded or transferred entity.
        return target.isDeadOrDying() || target.getRemovalReason() == Entity.RemovalReason.KILLED;
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            if (event.getEntity() instanceof Mob target) {
                Colonies.all(level.getServer()).forEach(colony -> colony.service(ID, ResidentCommands.class)
                    .ifPresent(commands -> commands.targetLeft(target.getUUID(), defeated(target))));
            }
            Bodies.of(event.getEntity()).ifPresent(body -> body.colonyId()
                .flatMap(id -> Colonies.of(level.getServer(), id))
                .flatMap(colony -> colony.service(ID, ResidentCommands.class))
                .ifPresent(commands -> commands.release(body.id())));
        }
    }
}
