package io.github.izakyl.folkways.front.client.ponder;

import io.github.izakyl.folkways.front.api.ui.Cue;
import io.github.izakyl.folkways.front.api.ui.Cues;
import io.github.izakyl.folkways.front.client.Lessons;
import java.util.List;
import java.util.Optional;
import net.createmod.catnip.gui.ScreenOpener;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.enums.PonderKeybinds;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class PonderHint {

    private static final String PONDER_KEYBIND = "key.ponder.ponder";

    private static final float FILL_FLOOR = 0.25F;
    private static final float FILL_RATE = 0.25F;
    private static final float DECAY = 0.05F;

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

    public static KeyMapping mapping() {
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
        if (hoveredTitle == null) {
            return;
        }
        Optional<TooltipComponent> cues = hovered == null ? Optional.empty() : Optional.of(new Cues(
            List.of(Cue.of("folkways.keys.ponder", Cue.Key.PONDER).held(progress)), true));
        graphics.renderTooltip(Minecraft.getInstance().font, List.of(hoveredTitle), cues, ItemStack.EMPTY,
            mouseX, mouseY);
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
