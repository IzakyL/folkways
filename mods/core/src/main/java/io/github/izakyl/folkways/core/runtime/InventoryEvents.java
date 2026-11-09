package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.internal.ColonyRegistry;
import io.github.izakyl.folkways.core.inventory.api.InventoryChanged;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID)
public final class InventoryEvents {

    private InventoryEvents() {
    }

    @SubscribeEvent
    public static void equipped(LivingEquipmentChangeEvent event) {
        InventoryFeedback.resident(event.getEntity());
    }

    @SubscribeEvent
    public static void store(InventoryChanged.Store event) {
        for (var colony : Colonies.all(event.level().getServer())) {
            colony.inventoryFeedback().changed(Stash.at(event.at()));
        }
    }

    @SubscribeEvent
    public static void resident(InventoryChanged.Resident event) {
        var entity = event.entity();
        if (entity.level() instanceof ServerLevel level) {
            Bodies.of(entity).flatMap(body -> body.colonyId())
                .flatMap(id -> Colonies.of(level.getServer(), id))
                .ifPresent(colony -> colony.inventoryFeedback().changedResident(entity.getUUID()));
        }
    }

    @SubscribeEvent
    public static void loaded(ChunkEvent.Load event) {
        chunk(event, true);
    }

    @SubscribeEvent
    public static void unloaded(ChunkEvent.Unload event) {
        chunk(event, false);
    }

    private static void chunk(ChunkEvent event, boolean loaded) {
        if (event.getLevel() instanceof ServerLevel level) {
            var pos = event.getChunk().getPos();
            for (var colony : ColonyRegistry.get(level.getServer()).colonies()) {
                colony.inventory().chunk(level, pos.x, pos.z, loaded);
            }
        }
    }
}
