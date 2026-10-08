package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.event.ContainerMenuEvent;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import io.github.izakyl.folkways.FolkwaysMod;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class SiteContainers {
    private static final Map<Player, Click> CLICKS = new WeakHashMap<>();

    private SiteContainers() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) {
            CLICKS.put(event.getEntity(), new Click(event.getPos().immutable(), event.getLevel().getGameTime()));
        }
    }

    @SubscribeEvent
    public static void onCreate(ContainerMenuEvent.Create event) {
        if (event.menu instanceof InventoryMenu || event.menu instanceof ModularUIContainerMenu
                || !(event.menu instanceof IModularUIHolderMenu holder) || holder.hasModularUI()) {
            return;
        }
        Click clicked = event.isRemote() ? null : CLICKS.remove(event.player);
        Optional<BlockPos> at = clicked != null && clicked.tick() == event.player.level().getGameTime()
            ? Optional.of(clicked.pos()) : Optional.empty();
        holder.setModularUI(ColonyShell.besideContainer(event.player, at));
    }

    private record Click(BlockPos pos, long tick) {
    }
}
