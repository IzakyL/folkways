package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID)
public final class ColonyBookKeeping {

    private ColonyBookKeeping() {
    }

    @SubscribeEvent
    public static void keepBoundBooks(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        List<ItemStack> kept = null;
        for (Iterator<ItemEntity> drops = event.getDrops().iterator(); drops.hasNext();) {
            ItemStack stack = drops.next().getItem();
            if (ColonyBookItem.boundColonyId(stack).isEmpty()) {
                continue;
            }
            if (kept == null) {
                kept = new ArrayList<>(player.getData(FolkwaysPlayerAttachments.KEPT_BOOKS));
            }
            kept.add(stack);
            drops.remove();
        }
        if (kept != null) {
            player.setData(FolkwaysPlayerAttachments.KEPT_BOOKS, kept);
        }
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
