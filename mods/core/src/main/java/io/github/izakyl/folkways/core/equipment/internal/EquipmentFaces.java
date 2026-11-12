package io.github.izakyl.folkways.core.equipment.internal;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.equipment.api.Mannequins;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Client drawing for the equipment page: inventory-like slots with a state mark, and the dressed figure. */
@OnlyIn(Dist.CLIENT)
final class EquipmentFaces {
    // Ledger palette: a slot is a pressed hollow in the paper, darker above and left, lit below and right.
    private static final int SHADE = 0xFF8B7352, LIGHT = 0xFFF4EAD0, HOLLOW = 0xFFCDB98F, HOVER = 0x40FFFFFF;
    private static final int OUTLINE = 0xFF3B2A1A;

    /** What one slot shows; shared by every copy a style makes of its texture. */
    static final class Shown {
        ItemStack stack = ItemStack.EMPTY;
        @Nullable
        ResourceLocation empty;
        // The state mark's colour, or 0 for none.
        int mark;
        boolean atlas;
        boolean selected;
    }

    private EquipmentFaces() {
    }

    static Shown slot(com.lowdragmc.lowdraglib2.gui.ui.UIElement element) {
        Shown shown = new Shown();
        element.style(style -> style.background(new Slot(shown)));
        return shown;
    }

    static Map<ResourceLocation, ItemStack> dressed(com.lowdragmc.lowdraglib2.gui.ui.UIElement element) {
        Map<ResourceLocation, ItemStack> worn = new LinkedHashMap<>();
        element.style(style -> style.background(new Figure(worn)));
        return worn;
    }

    private static final class Slot extends TransformTexture {
        private final Shown shown;

        private Slot(Shown shown) {
            this.shown = shown;
        }

        @Override
        protected void drawInternal(GuiGraphics graphics, float mouseX, float mouseY, float x, float y,
                                    float width, float height, float partialTicks) {
            int left = Math.round(x), top = Math.round(y);
            int right = Math.round(x + width), bottom = Math.round(y + height);
            graphics.fill(left, top, right, bottom, shown.selected ? OUTLINE : SHADE);
            graphics.fill(left + 1, top + 1, right, bottom, LIGHT);
            graphics.fill(left + 1, top + 1, right - 1, bottom - 1, HOLLOW);
            int itemX = left + (right - left - 16) / 2, itemY = top + (bottom - top - 16) / 2;
            if (!shown.stack.isEmpty()) {
                // LDLib2's item texture keeps the item's depth inside its element, so windows above cover it.
                new ItemStackTexture(shown.stack).draw(graphics, mouseX, mouseY, itemX, itemY, 16, 16, partialTicks);
            } else if (shown.empty != null) {
                RenderSystem.enableBlend();
                graphics.setColor(1f, 1f, 1f, .45f);
                if (shown.atlas) {
                    var sprite = Minecraft.getInstance()
                        .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                        .apply(shown.empty);
                    graphics.blit(itemX, itemY, 0, 16, 16, sprite);
                } else {
                    graphics.blit(shown.empty, itemX, itemY, 0, 0, 16, 16, 16, 16);
                }
                graphics.setColor(1f, 1f, 1f, 1f);
            }
            if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height) {
                graphics.fill(left + 1, top + 1, right - 1, bottom - 1, HOVER);
            }
            if (shown.mark != 0) {
                // A corner mark over the item: green held, amber on its way or still needed, red short.
                graphics.fill(right - 7, top + 1, right - 1, top + 7, OUTLINE);
                graphics.fill(right - 6, top + 2, right - 2, top + 6, shown.mark);
            }
        }

        @Override
        public IGuiTexture copy() {
            return new Slot(shown);
        }
    }

    // The profile worn by a stand-in resident, turned towards the pointer like the player in their inventory.
    private static final class Figure extends TransformTexture {
        private static final float BLOCKS_TALL = 2.2f;
        @Nullable
        private static LivingEntity body;
        private final Map<ResourceLocation, ItemStack> worn;

        private Figure(Map<ResourceLocation, ItemStack> worn) {
            this.worn = worn;
        }

        @Nullable
        private static LivingEntity body() {
            Level level = Minecraft.getInstance().level;
            if (level == null) {
                body = null;
            } else if (body == null || body.level() != level) {
                body = Mannequins.make(level).orElse(null);
            }
            return body;
        }

        @Override
        protected void drawInternal(GuiGraphics graphics, float mouseX, float mouseY, float x, float y,
                                    float width, float height, float partialTicks) {
            LivingEntity figure = body();
            if (figure == null || width <= 0 || height <= 0) {
                return;
            }
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot != EquipmentSlot.BODY) {
                    figure.setItemSlot(slot, ItemStack.EMPTY);
                }
            }
            worn.forEach((id, stack) -> GearSlots.slot(id).ifPresent(gear -> gear.dress(figure, stack)));
            float scale = Math.min(height / BLOCKS_TALL, width / 1.2f);
            float centreX = x + width / 2f, centreY = y + height / 2f;
            float turn = (float) Math.clamp(Math.atan((centreX - mouseX) / 40f), -.6, .6);
            float tilt = (float) Math.clamp(Math.atan((centreY - mouseY) / 40f), -.4, .4);
            float bodyYaw = figure.yBodyRot, bodyYawO = figure.yBodyRotO, yaw = figure.getYRot(), yawO = figure.yRotO;
            float pitch = figure.getXRot(), pitchO = figure.xRotO, headO = figure.yHeadRotO, head = figure.yHeadRot;
            // Renderers interpolate from the previous rotation by the frame's partial tick: set both.
            figure.yBodyRot = figure.yBodyRotO = 180f + turn * 20f;
            figure.setYRot(180f + turn * 40f);
            figure.yRotO = figure.getYRot();
            figure.setXRot(-tilt * 20f);
            figure.xRotO = figure.getXRot();
            figure.yHeadRot = figure.getYRot();
            figure.yHeadRotO = figure.getYRot();
            graphics.pose().pushPose();
            try {
                InventoryScreen.renderEntityInInventory(graphics, centreX, centreY, scale,
                    new Vector3f(0f, figure.getBbHeight() / 2f, 0f),
                    new Quaternionf().rotateZ((float) Math.PI), null, figure);
            } finally {
                graphics.pose().popPose();
                // The figure stands in front of the page, which it must, or the paper hides it; then its depth is
                // forgotten, so a window drawn later over the page still covers it. Sunk below the page's plane
                // instead, the figure was hidden behind the page itself.
                graphics.flush();
                RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
                figure.yBodyRot = bodyYaw;
                figure.yBodyRotO = bodyYawO;
                figure.setYRot(yaw);
                figure.yRotO = yawO;
                figure.setXRot(pitch);
                figure.xRotO = pitchO;
                figure.yHeadRotO = headO;
                figure.yHeadRot = head;
            }
        }

        // A figure partly scrolled out of the page is cut at the page's edge rather than hidden whole.
        @Override
        protected void drawInternal(GUIContext context, float x, float y, float width, float height) {
            if (context.scissorStack.isEmpty()) {
                super.drawInternal(context, x, y, width, height);
                return;
            }
            var clip = context.scissorStack.top();
            if (x + width <= clip.left || y + height <= clip.up || x >= clip.right || y >= clip.down) {
                return;
            }
            context.graphics.enableScissor((int) Math.floor(Math.max(x, clip.left)),
                (int) Math.floor(Math.max(y, clip.up)), (int) Math.ceil(Math.min(x + width, clip.right)),
                (int) Math.ceil(Math.min(y + height, clip.down)));
            try {
                super.drawInternal(context, x, y, width, height);
            } finally {
                context.graphics.disableScissor();
            }
        }

        @Override
        public IGuiTexture copy() {
            return new Figure(worn);
        }
    }
}
