package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.izakyl.folkways.front.client.ColonyLookCard.Row;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class WorldCards {

    record Look(float backgroundOpacity, int bodyColor) {
        static final Look PLATE = new Look(0.4F, ColonyLookCard.TEXT_COLOR);
        static final Look PLACARD = new Look(0.62F, 0xFFDDDDDD);
    }

    private static final float PAD_X = 3.0F;
    private static final float PAD_Y = 2.0F;
    private static final float ITEM_DEPTH = 1.0F;
    private static final float ITEM_LIFT = 0.5F;
    // Share of the way a card's ink moves toward its backdrop's each frame, so a passing cloud fades rather than flickers.
    private static final float INK_EASE = 0.15F;

    private static final List<Queued> QUEUE = new ArrayList<>();

    private static final Map<String, CardBackdrop.Ink> INKS = new HashMap<>();

    private WorldCards() {
    }

    /** Holds a card for this frame's {@link #flush}, which decides whether it is drawn at all. */
    static void queue(Vec3 anchor, List<Row> rows, float scale, Look look, Vec3 camera) {
        QUEUE.add(new Queued(anchor, rows, scale, look, anchor.distanceToSqr(camera)));
    }

    /**
     * Draws the queued cards nearest first and drops every card that any drawn card would cover even in part,
     * so a card is either whole on screen or not there. Each card is flushed on its own.
     */
    static void flush(Minecraft minecraft, PoseStack poseStack, Vec3 camera) {
        if (QUEUE.isEmpty()) {
            return;
        }
        List<Queued> queued = new ArrayList<>(QUEUE);
        QUEUE.clear();
        if (minecraft.level == null) {
            return;
        }
        queued.sort(Comparator.comparingDouble(Queued::distanceSqr));

        Camera view = minecraft.gameRenderer.getMainCamera();
        Quaternionf toView = new Quaternionf(view.rotation()).conjugate();
        Map<String, CardBackdrop.Ink> inks = new HashMap<>();
        List<float[]> shown = new ArrayList<>();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        buffers.endBatch();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        for (Queued card : queued) {
            LookCardLayout.Laid laid = LookCardLayout.of(minecraft.font, card.rows());
            float[] rect = screenRect(card, laid, camera, toView);
            if (rect != null && shown.stream().anyMatch(other -> overlaps(rect, other))) {
                continue;
            }
            if (rect != null) {
                shown.add(rect);
            }
            CardBackdrop.Ink ink = ink(minecraft, view, card, laid);
            inks.put(key(card), ink);
            draw(minecraft, poseStack, buffers, card.anchor(), laid, card.scale(), ink);
            buffers.endBatch();
        }
        poseStack.popPose();
        INKS.clear();
        INKS.putAll(inks);
    }

    /** Reads the world behind the card's middle and corners, and eases the card's ink toward what it needs there. */
    private static CardBackdrop.Ink ink(Minecraft minecraft, Camera view, Queued card, LookCardLayout.Laid laid) {
        Vec3 up = new Vec3(view.getUpVector()).scale(card.scale());
        Vec3 left = new Vec3(view.getLeftVector()).scale(card.scale());
        Vec3 bottom = card.anchor().add(up.scale(-PAD_Y));
        Vec3 top = card.anchor().add(up.scale(laid.height() + PAD_Y));
        Vec3 side = left.scale(laid.width() / 2.0F + PAD_X);
        double backdrop = CardBackdrop.luminance(minecraft, view.getPosition(),
            bottom.add(top).scale(0.5D), top.add(side), top.subtract(side), bottom.add(side), bottom.subtract(side));
        float opacity = minecraft.options.getBackgroundOpacity(card.look().backgroundOpacity());
        CardBackdrop.Ink wanted = CardBackdrop.ink(backdrop, opacity, card.look().bodyColor());
        CardBackdrop.Ink was = INKS.get(key(card));
        if (was == null) {
            return wanted;
        }
        return new CardBackdrop.Ink(Mth.lerp(INK_EASE, was.backgroundOpacity(), wanted.backgroundOpacity()),
            CardBackdrop.grey(Math.round(Mth.lerp(INK_EASE, was.bodyColor() & 0xFF, wanted.bodyColor() & 0xFF))));
    }

    // A card is known across frames by its title, which for a resident is the name. A placard stays where it is
    // and shares its title with others, a chest's with every chest's, so it is known by where it stands.
    private static String key(Queued card) {
        return card.look() != Look.PLACARD && !card.rows().isEmpty() && card.rows().getFirst() instanceof Row.Text title
            ? title.text() : card.anchor().toString();
    }

    /**
     * The card's extent on the view plane, divided through by its depth: the card always faces the camera, so
     * every point of it sits at the anchor's depth. Null when the anchor is behind the camera.
     */
    private static float[] screenRect(Queued card, LookCardLayout.Laid laid, Vec3 camera, Quaternionf toView) {
        Vector3f view = toView.transform(card.anchor().subtract(camera).toVector3f());
        float depth = -view.z();
        if (depth <= 0.0F) {
            return null;
        }
        float halfWidth = (laid.width() / 2.0F + PAD_X) * card.scale();
        float top = (laid.height() + PAD_Y) * card.scale();
        float bottom = -PAD_Y * card.scale();
        return new float[] {(view.x() - halfWidth) / depth, (view.y() + bottom) / depth,
            (view.x() + halfWidth) / depth, (view.y() + top) / depth};
    }

    private static boolean overlaps(float[] a, float[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    private record Queued(Vec3 anchor, List<Row> rows, float scale, Look look, double distanceSqr) {
    }

    /** Draws the card facing the camera, its bottom edge centred on the anchor. */
    private static void draw(Minecraft minecraft, PoseStack poseStack, MultiBufferSource.BufferSource buffers,
            Vec3 anchor, LookCardLayout.Laid laid, float scale, CardBackdrop.Ink ink) {
        Font font = minecraft.font;

        poseStack.pushPose();
        poseStack.translate(anchor.x, anchor.y, anchor.z);
        poseStack.mulPose(minecraft.gameRenderer.getMainCamera().rotation());
        poseStack.scale(scale, -scale, scale);
        poseStack.translate(-laid.width() / 2.0F, -laid.height(), 0.0F);
        Matrix4f matrix = poseStack.last().pose();

        int backgroundColor = Math.round(ink.backgroundOpacity() * 255.0F) << 24;
        quad(buffers.getBuffer(RenderType.textBackground()), matrix, -PAD_X, -PAD_Y, laid.width() + PAD_X,
            laid.height() + PAD_Y, backgroundColor);

        for (LookCardLayout.Piece piece : laid.pieces()) {
            switch (piece) {
                case LookCardLayout.Piece.Fill fill ->
                    sprite(minecraft, buffers, matrix, GaugeIcons.FILL, fill.x0(), fill.y0(), fill.x1(), fill.y1(),
                        fill.color());
                case LookCardLayout.Piece.Icon icon ->
                    sprite(minecraft, buffers, matrix, icon.sprite(), icon.x(), icon.y(), icon.x() + icon.width(),
                        icon.y() + icon.height(), -1);
                case LookCardLayout.Piece.Item item -> drawItem(minecraft, poseStack, buffers, item);
                case LookCardLayout.Piece.Text text ->
                    font.drawInBatch(text.text(), text.x(), text.y(),
                        text.color() == ColonyLookCard.TEXT_COLOR ? ink.bodyColor() : text.color(), false, matrix,
                        buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            }
        }
        poseStack.popPose();
    }

    private static void drawItem(Minecraft minecraft, PoseStack poseStack, MultiBufferSource buffers,
            LookCardLayout.Piece.Item item) {
        ItemStack stack = item.stack();
        poseStack.pushPose();
        poseStack.translate(item.x() + item.size() / 2.0F, item.y() + item.size() / 2.0F, ITEM_LIFT);
        poseStack.scale(item.size(), -item.size(), ITEM_DEPTH);
        minecraft.getItemRenderer().renderStatic(stack, ItemDisplayContext.GUI, LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY, poseStack, buffers, minecraft.level, 0);
        poseStack.popPose();
    }

    private static void sprite(Minecraft minecraft, MultiBufferSource buffers, Matrix4f matrix,
            ResourceLocation id, float x0, float y0, float x1, float y1, int color) {
        TextureAtlasSprite sprite = minecraft.getGuiSprites().getSprite(id);
        VertexConsumer icons = buffers.getBuffer(RenderType.textPolygonOffset(sprite.atlasLocation()));
        icons.addVertex(matrix, x0, y0, 0.0F).setColor(color).setUv(sprite.getU0(), sprite.getV0())
            .setLight(LightTexture.FULL_BRIGHT);
        icons.addVertex(matrix, x0, y1, 0.0F).setColor(color).setUv(sprite.getU0(), sprite.getV1())
            .setLight(LightTexture.FULL_BRIGHT);
        icons.addVertex(matrix, x1, y1, 0.0F).setColor(color).setUv(sprite.getU1(), sprite.getV1())
            .setLight(LightTexture.FULL_BRIGHT);
        icons.addVertex(matrix, x1, y0, 0.0F).setColor(color).setUv(sprite.getU1(), sprite.getV0())
            .setLight(LightTexture.FULL_BRIGHT);
    }

    private static void quad(VertexConsumer quads, Matrix4f matrix, float x0, float y0, float x1, float y1,
            int color) {
        if (x1 <= x0) {
            return;
        }
        quads.addVertex(matrix, x0, y0, 0.0F).setColor(color).setLight(LightTexture.FULL_BRIGHT);
        quads.addVertex(matrix, x0, y1, 0.0F).setColor(color).setLight(LightTexture.FULL_BRIGHT);
        quads.addVertex(matrix, x1, y1, 0.0F).setColor(color).setLight(LightTexture.FULL_BRIGHT);
        quads.addVertex(matrix, x1, y0, 0.0F).setColor(color).setLight(LightTexture.FULL_BRIGHT);
    }
}
