package io.github.izakyl.folkways.core.front.api.ui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;

/** Client-only presentation weights. Mode selection grants no authority over the things being drawn. */
public final class ModeOverlays {
    public static final long TRANSITION_MS = 220;
    public static final float DIM_ALPHA = 0.22F;
    private static final Map<ResourceLocation, Float> origins = new HashMap<>();
    private static ResourceLocation selected = OverheadTools.BUILD;
    private static long changedAt;

    private ModeOverlays() {
    }

    public static void reset() {
        origins.clear();
        selected = OverheadTools.BUILD;
        changedAt = 0;
    }

    public static float alpha(ResourceLocation mode) {
        return alpha(mode, Util.getMillis());
    }

    /** Explicit clock for deterministic transition checks. */
    public static float alpha(ResourceLocation mode, long now) {
        if (!selected.equals(OverheadTools.mode())) {
            for (OverheadTools.Mode entry : OverheadTools.modes()) {
                origins.put(entry.id(), weight(entry.id(), now));
            }
            selected = OverheadTools.mode();
            changedAt = now;
        }
        return weight(mode, now);
    }

    private static float weight(ResourceLocation mode, long now) {
        float target = mode.equals(selected) ? 1.0F : DIM_ALPHA;
        float initial = origins.getOrDefault(mode, mode.equals(OverheadTools.BUILD) ? 1.0F : DIM_ALPHA);
        return interpolate(initial, target, now - changedAt);
    }

    public static float interpolate(float from, float to, long elapsed) {
        float progress = Math.clamp((float) elapsed / TRANSITION_MS, 0.0F, 1.0F);
        float eased = progress * progress * (3.0F - 2.0F * progress);
        return from + (to - from) * eased;
    }

    public static int color(ResourceLocation mode, int argb) {
        return tint(argb, alpha(mode));
    }

    private static int tint(int argb, float alpha) {
        int red = (argb >> 16) & 255;
        int green = (argb >> 8) & 255;
        int blue = argb & 255;
        float grey = red * 0.2126F + green * 0.7152F + blue * 0.0722F;
        float saturation = 0.25F + 0.75F * (alpha - DIM_ALPHA) / (1.0F - DIM_ALPHA);
        return Math.round((argb >>> 24) * alpha) << 24
            | Math.round(grey + (red - grey) * saturation) << 16
            | Math.round(grey + (green - grey) * saturation) << 8
            | Math.round(grey + (blue - grey) * saturation);
    }

    public static MultiBufferSource buffers(MultiBufferSource source, ResourceLocation mode) {
        float alpha = alpha(mode);
        return type -> new Fading(source.getBuffer(type), alpha);
    }

    public static VertexConsumer vertices(VertexConsumer source, ResourceLocation mode) {
        return new Fading(source, alpha(mode));
    }

    private record Fading(VertexConsumer delegate, float alpha) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int opacity) {
            int color = tint(opacity << 24 | red << 16 | green << 8 | blue, alpha);
            delegate.setColor((color >> 16) & 255, (color >> 8) & 255, color & 255, color >>> 24);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
