package io.github.izakyl.folkways.front.client;

import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolder;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.mixin.ContainerScreenAccessor;
import io.github.izakyl.folkways.front.engine.net.RequestSitePacket;
import io.github.izakyl.folkways.front.engine.net.SiteSyncPacket;
import io.github.izakyl.folkways.front.engine.net.ToggleColonyMemberPacket;
import io.github.izakyl.folkways.front.ui.panel.PanelButton;
import io.github.izakyl.folkways.front.ui.screen.ColonyShell;
import io.github.izakyl.folkways.front.ui.screen.FolkwaysUI;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SiteEntryInjector {
    private static final int BUTTON_W = 96;
    private static final int BUTTON_H = 16;
    private static final int GAP = 6;

    private static BlockPos pendingBlock;

    private static Screen boundScreen;
    private static BlockPos asked;
    private static boolean answered;
    private static boolean member;
    private static boolean offered;

    private SiteEntryInjector() {
    }

    static void forget() {
        pendingBlock = null;
        boundScreen = null;
        asked = null;
        answered = false;
    }

    public static void accept(SiteSyncPacket packet) {
        if (boundScreen == null || !Objects.equals(asked, packet.pos())) {
            return;
        }
        answered = true;
        member = packet.member();
        offered = packet.offered();
        rebuild();
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() == Minecraft.getInstance().player) {
            pendingBlock = event.getPos();
        }
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?> container
                && container.getMenu() instanceof IModularUIHolder holder
                && holder.getModularUI() instanceof ColonyShell.SiteUI ui) {
            ui.setScreenAndInit(container);
            var panel = ui.getElementById("folkways.site.panel");
            int room = Math.max(120, Math.min(300, container.width - container.getXSize() - 3 * GAP));
            if (panel.isDisplayed() || (container == boundScreen && answered && offered)) {
                int left = Math.max(GAP, (container.width - container.getXSize() - GAP - room) / 2);
                ((ContainerScreenAccessor) container).folkways$setLeftPos(left);
            }
            int right = container.getGuiLeft() + container.getXSize() + GAP;
            panel.layout(layout -> layout.left(right).top(container.getGuiTop())
                .width(room).height(sideHeight(container)));
            event.addListener(ui.getWidget());
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)
                || screen instanceof InventoryScreen
                || (screen.getMenu() instanceof ModularUIContainerMenu menu
                    && menu.uiHolder instanceof FolkwaysUI)
                || pendingBlock == null) {
            boundScreen = null;
            return;
        }
        if (screen != boundScreen || !Objects.equals(pendingBlock, asked)) {
            boundScreen = screen;
            asked = pendingBlock;
            answered = false;
            member = false;
            offered = false;
            PacketDistributor.sendToServer(new RequestSitePacket(asked));
            return;
        }
        if (!answered) {
            return;
        }
        BlockPos block = asked;
        int x = screen.getGuiLeft() + screen.getXSize() + GAP;
        int y = screen.getGuiTop() + 4;
        if (offered) {
            y = screen.getGuiTop() + sideHeight(screen) + GAP;
        }
        UUID heldColony = ColonyHighlightState.heldColonyId(Minecraft.getInstance().player);
        if (heldColony == null) {
            return;
        }
        event.addListener(PanelButton.centred(x, y, BUTTON_W, BUTTON_H, "folkways.member.toggle",
            () -> Component.translatable(member ? "folkways.member.leave" : "folkways.member.join")
                .getString(),
            () -> member,
            () -> {
                PacketDistributor.sendToServer(new ToggleColonyMemberPacket(heldColony, block));
                PacketDistributor.sendToServer(new RequestSitePacket(block));
            }));
    }

    private static int sideHeight(AbstractContainerScreen<?> screen) {
        return Math.min(230, screen.height - screen.getGuiTop() - BUTTON_H - 2 * GAP);
    }

    private static void rebuild() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        if (screen != null && screen == boundScreen) {
            screen.resize(minecraft, screen.width, screen.height);
        }
    }
}
