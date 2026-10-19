package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

// Bound books a player died holding: they come back on respawn, once.
@EventBusSubscriber(modid = FolkwaysMod.CORE_ID)
public final class ColonyBookKeeping {

    private ColonyBookKeeping() {
    }

    @SubscribeEvent
    public static void returnKeptBooks(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        List<ItemStack> kept = player.getData(FolkwaysPlayerAttachments.KEPT_BOOKS);
        if (kept.isEmpty()) {
            return;
        }
        player.setData(FolkwaysPlayerAttachments.KEPT_BOOKS, List.<ItemStack>of());
        for (ItemStack stack : kept) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }
}
