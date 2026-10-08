package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.item.BookGestures;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import io.github.izakyl.folkways.front.engine.net.SetBookGesturePacket;
import io.github.izakyl.folkways.front.engine.net.ToggleColonyMemberPacket;
import io.github.izakyl.folkways.front.engine.net.ToggleColonyResidentPacket;
import io.github.izakyl.folkways.front.engine.net.ZoneSnapshot;
import io.github.izakyl.folkways.front.ui.screen.Desk;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class FolkwaysClientGameEvents {
    private static boolean modifierDown;

    private FolkwaysClientGameEvents() {
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        boolean modifier = event.getKey() == InputConstants.KEY_LCONTROL
            || event.getKey() == InputConstants.KEY_RCONTROL
            || (Minecraft.ON_OSX && (event.getKey() == GLFW.GLFW_KEY_LEFT_SUPER
                || event.getKey() == GLFW.GLFW_KEY_RIGHT_SUPER));
        if (modifier) {
            modifierDown = event.getAction() != InputConstants.RELEASE;
        }
    }

    static boolean modifierDown() {
        return modifierDown;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        modifierDown = false;
        Desk.forget();
        SiteEntryInjector.forget();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ColonyHighlightState.tick();
        ZoneSelection.tick();
        PathSelection.tick();
        ColonyLookState.tick();
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null
            || event.getAction() != InputConstants.PRESS
            || event.getButton() != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        Optional<HeldBook> book = HeldBook.bound(minecraft.player);
        if (book.isEmpty()) {
            return;
        }
        boolean consumed = switch (book.get().gesture()) {
            case POINT -> toggleMember(minecraft, book.get());
            case BOX -> ZoneSelection.handleLeftClick();
            case LINE -> PathSelection.handleLeftClick();
        };
        if (consumed) {
            event.setCanceled(true);
        }
    }

    private static boolean toggleMember(Minecraft minecraft, HeldBook book) {
        if (minecraft.hitResult instanceof EntityHitResult aimed
            && aimed.getType() == HitResult.Type.ENTITY) {
            PacketDistributor.sendToServer(
                new ToggleColonyResidentPacket(book.colonyId(), aimed.getEntity().getId()));
            return true;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        PacketDistributor.sendToServer(
            new ToggleColonyMemberPacket(book.colonyId(), hit.getBlockPos().immutable()));
        return true;
    }

    @SubscribeEvent
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!event.isUseItem() || minecraft.screen != null || minecraft.player == null
            || HeldBook.bound(minecraft.player).isEmpty()
            || ZoneSelection.drafting() || PathSelection.drafting()) {
            return;
        }
        ZoneSelection.aimedZone(minecraft.player).map(ZoneSnapshot::id)
            .or(() -> PathSelection.aimedPath(minecraft))
            .ifPresent(ZoneSelection::openOn);
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null || event.getScrollDeltaY() == 0.0D) {
            return;
        }
        Optional<HeldBook> book = HeldBook.any(minecraft.player);
        if (book.isEmpty()) {
            return;
        }
        if (minecraft.player.isShiftKeyDown()) {
            PacketDistributor.sendToServer(
                new SetBookGesturePacket(book.get().hand(), BookGestures.next(book.get().gesture())));
            event.setCanceled(true);
            return;
        }
        if (modifierDown && book.get().gesture() == Shape.Gesture.BOX && book.get().colonyId() != null
            && ZoneSelection.handleScroll(event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        ZoneSelection.render(event);
        PathSelection.render(event);
        ColonyHighlightState.render(event);
        LookLineCards.render(event);
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            WorldCards.flush(Minecraft.getInstance(), event.getPoseStack(), event.getCamera().getPosition());
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (Minecraft.getInstance().screen == null) {
            ColonyLookCard.render(event.getGuiGraphics());
        }
    }
}
