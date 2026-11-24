package io.github.izakyl.folkways.core.front.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.api.ui.ModeOverlays;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.github.izakyl.folkways.core.front.engine.item.HeldBook;
import io.github.izakyl.folkways.core.front.engine.net.ToggleColonyMemberPacket;
import io.github.izakyl.folkways.core.front.engine.net.ToggleColonyResidentPacket;
import io.github.izakyl.folkways.core.front.engine.net.UseBookPacket;
import io.github.izakyl.folkways.core.front.engine.net.ZoneSnapshot;
import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class FolkwaysClientGameEvents {
    private static boolean modifierDown;
    private static boolean alternateDown;

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
        if (event.getAction() == InputConstants.PRESS && Overhead.KEY.matches(event.getKey(), event.getScanCode())) {
            Overhead.toggle(Minecraft.getInstance());
        }
        if (event.getKey() == InputConstants.KEY_LALT || event.getKey() == InputConstants.KEY_RALT) {
            alternateDown = event.getAction() != InputConstants.RELEASE;
        }
    }

    static boolean modifierDown() {
        return modifierDown;
    }

    static boolean alternateDown() {
        return alternateDown;
    }

    /** The sneak key as a modifier for the book; from above it only takes the camera down, and modifies nothing. */
    static boolean sneakDown(Player player) {
        return !Overhead.on() && player.isShiftKeyDown();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        modifierDown = false;
        alternateDown = false;
        Overhead.forget();
        OverheadTools.reset();
        BookTools.reset();
        ModeOverlays.reset();
        ShapePreview.clear();
        ShapePreview.showConfirmation(List.of());
        HeldPlacement.clear();
        CurrentColony.forgetOnClient();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Overhead.tick(Minecraft.getInstance());
        Minecraft minecraft = Minecraft.getInstance();
        Optional<UUID> colony = minecraft.player == null ? Optional.empty()
            : HeldBook.bound(minecraft.player).map(HeldBook::colonyId);
        OverheadTools.marks().forEach(marks -> marks.tick(colony));
        ColonyHighlightState.tick();
        ZoneSelection.tick();
        PathSelection.tick();
        ShapePreview.tick();
        HeldPlacement.tick();
        ColonyLookState.tick();
        ColonyDebugState.tick();
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null
            || event.getAction() != InputConstants.PRESS
            || event.getButton() != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        if (click(minecraft)) {
            event.setCanceled(true);
        }
    }

    /** The book's left click on whatever is aimed at, by the gesture the book is turned to. */
    static boolean click(Minecraft minecraft) {
        Optional<HeldBook> book = HeldBook.bound(minecraft.player);
        if (book.isEmpty()) {
            return false;
        }
        if (Overhead.on() && OverheadTools.selected().isPresent()) {
            return true;
        }
        // A shape held to be set down takes the click, whatever the book has picked.
        if (HeldPlacement.holding()) {
            return HeldPlacement.handleLeftClick();
        }
        return switch (BookTools.gesture()) {
            case POINT -> toggleMember(minecraft, book.get());
            case BOX -> ZoneSelection.handleLeftClick();
            case LINE -> PathSelection.handleLeftClick();
        };
    }

    /** The book used from the view from above, where the game's own use key does not reach it. */
    static boolean use(Minecraft minecraft) {
        Optional<HeldBook> book = HeldBook.bound(minecraft.player);
        if (book.isEmpty()) {
            return false;
        }
        if (Overhead.on() && OverheadTools.selected().isPresent()) {
            OverheadTools.selected().orElseThrow().open().run();
            return true;
        }
        useBook(minecraft, book.get());
        return true;
    }

    /**
     * A whole shape drawn is handed over, asking what it becomes; otherwise what the colony holds where the book is
     * aimed opens on its card, and anything else opens the colony's panel. A shape half drawn keeps the use key.
     */
    private static void useBook(Minecraft minecraft, HeldBook book) {
        if (BookTools.hand() || BookTools.justHanded()) {
            return;
        }
        if (BookTools.drafting()) {
            return;
        }
        Aim aim = Aim.of(minecraft.player);
        Optional<BlockPos> block = aim.block(minecraft.player).map(hit -> hit.getBlockPos().immutable());
        Optional<UUID> holding = ZoneSelection.aimedZone(minecraft.player).map(ZoneSnapshot::id)
            .or(() -> PathSelection.aimedPath(minecraft));
        PacketDistributor.sendToServer(new UseBookPacket(book.colonyId(), block, holding));
    }

    private static boolean toggleMember(Minecraft minecraft, HeldBook book) {
        Aim aim = Aim.of(minecraft.player);
        Optional<EntityHitResult> entity = aim.entity(minecraft.player);
        if (entity.isPresent()) {
            PacketDistributor.sendToServer(
                new ToggleColonyResidentPacket(book.colonyId(), entity.get().getEntity().getId()));
            return true;
        }
        Optional<BlockHitResult> hit = aim.block(minecraft.player);
        if (hit.isEmpty()) {
            return false;
        }
        PacketDistributor.sendToServer(
            new ToggleColonyMemberPacket(book.colonyId(), hit.get().getBlockPos().immutable()));
        return true;
    }

    // The bound book's use key is the book's own: the server is told what it was used on, not the block.
    @SubscribeEvent
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!event.isUseItem() || minecraft.screen != null || minecraft.player == null) {
            return;
        }
        Optional<HeldBook> book = HeldBook.bound(minecraft.player);
        if (book.isEmpty() || book.get().hand() != event.getHand()) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        useBook(minecraft, book.get());
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null || event.getScrollDeltaY() == 0.0D) {
            return;
        }
        if (ColonyDebugState.scroll(event.getScrollDeltaY()) || scroll(minecraft, event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    /** The wheel turned with the book in hand: with sneak it turns the gesture, with the other keys it adjusts. */
    static boolean scroll(Minecraft minecraft, double delta) {
        Optional<HeldBook> book = HeldBook.any(minecraft.player);
        if (book.isEmpty()) {
            return false;
        }
        if (Overhead.on() && OverheadTools.selected().isPresent()) {
            return false;
        }
        if (book.get().colonyId() == null) {
            return false;
        }
        if (sneakDown(minecraft.player)) {
            // The wheel turned down turns the book on to its next gesture, as it walks on along the hotbar.
            BookTools.cycleGesture(delta < 0.0D ? 1 : -1);
            return true;
        }
        if (!(modifierDown || alternateDown)) {
            return false;
        }
        if (HeldPlacement.holding()) {
            return HeldPlacement.handleScroll(delta, modifierDown, alternateDown);
        }
        return switch (BookTools.gesture()) {
            case POINT -> false;
            case BOX -> ZoneSelection.handleScroll(delta);
            case LINE -> PathSelection.handleScroll(delta);
        };
    }

    // The cards go down first: the translucent fills drawn after them write depth, and would hide a card behind them.
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (ShadowPass.active()) {
            return;
        }
        ToolOverlay.render(event);
        ColonyHighlightState.queueCards(event);
        ColonyDebugState.render(event);
        LookLineCards.render(event);
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            WorldCards.flush(Minecraft.getInstance(), event.getPoseStack(), event.getCamera().getPosition());
        }
        ZoneSelection.render(event);
        PathSelection.render(event);
        ShapePreview.render(event);
        HeldPlacement.render(event);
        ColonyHighlightState.render(event);
        AimCursor.render(event);
    }
}
