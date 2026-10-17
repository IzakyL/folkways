package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.ui.Cue;
import io.github.izakyl.folkways.front.client.ponder.PonderHint;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Which of a key hint's inputs are being pressed, so its icon can show it. What the game is told of is kept
 * from the event, for the mouse and for a screen that has the keyboard; a tap or a turn of the wheel is over
 * within a frame, and stays lit long enough to be seen.
 */
@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
final class Pressed {
    private static final long LINGER_MS = 140L;
    /** The wheel has no code of its own among the keys and the buttons. */
    private static final int WHEEL = -1;

    private static final Set<Integer> KEYS = new HashSet<>();
    private static final Set<Integer> BUTTONS = new HashSet<>();
    private static final Map<Integer, Long> KEY_AT = new HashMap<>();
    private static final Map<Integer, Long> BUTTON_AT = new HashMap<>();

    private Pressed() {
    }

    static boolean down(Cue.Key key) {
        Minecraft minecraft = Minecraft.getInstance();
        return switch (key) {
            case CLICK -> button(InputConstants.MOUSE_BUTTON_LEFT);
            case ALT_CLICK -> button(InputConstants.MOUSE_BUTTON_RIGHT);
            case DRAG -> button(InputConstants.MOUSE_BUTTON_MIDDLE);
            case WHEEL -> lit(BUTTON_AT, WHEEL);
            case USE -> bound(minecraft.options.keyUse);
            case SNEAK -> bound(minecraft.options.keyShift);
            case JUMP -> bound(minecraft.options.keyJump);
            case MODIFIER -> Screen.hasControlDown() || FolkwaysClientGameEvents.modifierDown();
            case ALTERNATE -> Screen.hasAltDown() || FolkwaysClientGameEvents.alternateDown();
            case OVERHEAD -> bound(Overhead.KEY);
            case WALK -> bound(minecraft.options.keyUp) || bound(minecraft.options.keyLeft)
                || bound(minecraft.options.keyDown) || bound(minecraft.options.keyRight);
            case ESCAPE -> key(GLFW.GLFW_KEY_ESCAPE);
            case TAB -> key(GLFW.GLFW_KEY_TAB);
            case BACKSPACE -> key(GLFW.GLFW_KEY_BACKSPACE) || key(GLFW.GLFW_KEY_DELETE);
            case DIGITS -> digit();
            case PONDER -> bound(PonderHint.mapping());
        };
    }

    /** Whether a key is held down now, with no tap left lit: what is steered by holding it. */
    static boolean held(KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        return switch (key.getType()) {
            case MOUSE -> BUTTONS.contains(key.getValue());
            case KEYSYM -> KEYS.contains(key.getValue())
                || InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), key.getValue());
            case SCANCODE -> false;
        };
    }

    private static boolean bound(KeyMapping mapping) {
        if (mapping == null) {
            return false;
        }
        InputConstants.Key key = mapping.getKey();
        return switch (key.getType()) {
            case MOUSE -> button(key.getValue());
            case KEYSYM -> key(key.getValue());
            case SCANCODE -> false;
        };
    }

    private static boolean digit() {
        for (int code = GLFW.GLFW_KEY_0; code <= GLFW.GLFW_KEY_9; code++) {
            if (key(code)) {
                return true;
            }
        }
        return false;
    }

    private static boolean key(int code) {
        return KEYS.contains(code) || lit(KEY_AT, code)
            || InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), code);
    }

    private static boolean button(int button) {
        return BUTTONS.contains(button) || lit(BUTTON_AT, button);
    }

    private static boolean lit(Map<Integer, Long> at, int code) {
        Long pressed = at.get(code);
        return pressed != null && Util.getMillis() - pressed < LINGER_MS;
    }

    private static void told(Set<Integer> held, Map<Integer, Long> at, int code, int action) {
        if (action == InputConstants.RELEASE) {
            held.remove(code);
            return;
        }
        held.add(code);
        at.put(code, Util.getMillis());
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        told(KEYS, KEY_AT, event.getKey(), event.getAction());
    }

    @SubscribeEvent
    public static void onButton(InputEvent.MouseButton.Pre event) {
        told(BUTTONS, BUTTON_AT, event.getButton(), event.getAction());
    }

    @SubscribeEvent
    public static void onWheel(InputEvent.MouseScrollingEvent event) {
        BUTTON_AT.put(WHEEL, Util.getMillis());
    }

    @SubscribeEvent
    public static void onScreenWheel(ScreenEvent.MouseScrolled.Pre event) {
        BUTTON_AT.put(WHEEL, Util.getMillis());
    }

    // A key let go while the window was away is never heard of again: nothing is held over a new screen or game.
    @SubscribeEvent
    public static void onScreen(ScreenEvent.Opening event) {
        KEYS.clear();
        BUTTONS.clear();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        KEYS.clear();
        BUTTONS.clear();
    }
}
