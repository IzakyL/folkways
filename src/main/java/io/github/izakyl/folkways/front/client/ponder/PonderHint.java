package io.github.izakyl.folkways.front.client.ponder;

import io.github.izakyl.folkways.front.client.Lessons;
import java.util.List;
import net.createmod.catnip.gui.ScreenOpener;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.enums.PonderKeybinds;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.PonderTooltipHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class PonderHint {

    private static final String PROMPT_KEY = "ponder." + PonderTooltipHandler.HOLD_TO_PONDER;
    private static final String PONDER_KEYBIND = "key.ponder.ponder";

    private static final float FILL_FLOOR = 0.25F;
    private static final float FILL_RATE = 0.25F;
    private static final float DECAY = 0.05F;
    private static final int BAR_COLOR = 0xFF6BA88A;
    private static final int BAR_BACKDROP = 0x60000000;

    private static ResourceLocation hovered;
    private static Component hoveredTitle;
    private static ResourceLocation tracking;
    private static float progress;
    private static boolean keyHeld;

    private PonderHint() {
    }

    public static boolean key(int keyCode, int scanCode, boolean pressed) {
        KeyMapping mapping = mapping();
        if (mapping == null || !mapping.matches(keyCode, scanCode)) {
            return false;
        }
        keyHeld = pressed;
        return true;
    }

    private static KeyMapping mapping() {
        for (KeyMapping candidate : Minecraft.getInstance().options.keyMappings) {
            if (PONDER_KEYBIND.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    public static void hover(ResourceLocation lesson, Component title) {
        hovered = lesson;
        hoveredTitle = title;
    }

    public static void clearHover() {
        hovered = null;
        hoveredTitle = null;
    }

    public static void clear() {
        hovered = null;
        hoveredTitle = null;
        tracking = null;
        progress = 0.0F;
        keyHeld = false;
    }

    public static void tick() {
        if (hovered == null || hovered != tracking) {
            tracking = hovered;
            progress = 0.0F;
            return;
        }
        if (!keyHeld && !PonderKeybinds.PONDER.isDown()) {
            progress = Math.max(0.0F, progress - DECAY);
            return;
        }
        progress = Math.min(1.0F, progress + Math.max(FILL_FLOOR, progress) * FILL_RATE);
        if (progress >= 1.0F) {
            ResourceLocation target = tracking;
            clear();
            play(target);
        }
    }

    public static void renderPrompt(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hovered == null && hoveredTitle == null) {
            return;
        }
        if (hovered == null) {
            graphics.renderComponentTooltip(Minecraft.getInstance().font, List.of(hoveredTitle),
                mouseX, mouseY);
            return;
        }
        Component prompt = Component.translatable(PROMPT_KEY, PonderKeybinds.PONDER.message());
        List<Component> lines = hoveredTitle == null ? List.of(prompt) : List.of(hoveredTitle, prompt);
        graphics.renderComponentTooltip(Minecraft.getInstance().font, lines, mouseX, mouseY);
        if (progress <= 0.0F) {
            return;
        }
        int width = Minecraft.getInstance().font.width(prompt);
        int x = mouseX + 12;
        int y = mouseY - 12 + 10 * lines.size();
        graphics.fill(x, y, x + width, y + 1, BAR_BACKDROP);
        graphics.fill(x, y, x + Math.round(width * progress), y + 1, BAR_COLOR);
    }

    public static void play(ResourceLocation lesson) {
        List<StoryBoardEntry> entries = Lessons.scenesFor(lesson);
        if (entries.isEmpty()) {
            return;
        }
        List<PonderScene> scenes = PonderIndex.getSceneAccess().compile(entries);
        if (scenes.isEmpty()) {
            return;
        }
        ScreenOpener.transitionTo(new LessonScreen(scenes));
    }
}
