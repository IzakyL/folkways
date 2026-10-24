package io.github.izakyl.folkways.front.client;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.ui.screen.Desk;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PersonalCameraKeyEvents {
    public static final KeyMapping KEY = new KeyMapping("key.folkways.global_tactical_controls",
        InputConstants.UNKNOWN.getValue(), "key.categories.folkways");
    private static InputConstants.Key heldShortcut;

    private PersonalCameraKeyEvents() { }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() == InputConstants.RELEASE) {
            releaseShortcut(event.getKey(), event.getScanCode());
            return;
        }
        if (Minecraft.getInstance().screen == null && event.getAction() == InputConstants.PRESS
                && KEY.matches(event.getKey(), event.getScanCode())
                && KEY.isConflictContextAndModifierActive() && available()) {
            pressShortcut(event.getKey(), event.getScanCode());
        }
    }

    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (KEY.matches(event.getKeyCode(), event.getScanCode())
                && KEY.isConflictContextAndModifierActive() && available()) {
            pressShortcut(event.getKeyCode(), event.getScanCode());
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScreenKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        releaseShortcut(event.getKeyCode(), event.getScanCode());
    }

    private static void pressShortcut(int key, int scanCode) {
        InputConstants.Key pressed = InputConstants.getKey(key, scanCode);
        if (!pressed.equals(heldShortcut)) {
            heldShortcut = pressed;
            PersonalCameraControls.toggle();
        }
    }

    private static void releaseShortcut(int key, int scanCode) {
        if (InputConstants.getKey(key, scanCode).equals(heldShortcut)) heldShortcut = null;
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() == InputConstants.PRESS && KEY.matchesMouse(event.getButton())
                && KEY.isConflictContextAndModifierActive() && available()) {
            PersonalCameraControls.toggle();
            event.setCanceled(true);
        }
    }

    private static boolean available() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return false;
        if (minecraft.screen == null || minecraft.screen instanceof OverheadScreen) return true;
        ModularUI ui = ModularUI.of(minecraft.screen);
        return Desk.on(ui) && !(ui.getFocusedElement() instanceof TextField);
    }
}
