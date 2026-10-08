package io.github.izakyl.folkways.front.client;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * How bright the world behind a card is, and the backing and body ink that keep the card's words readable over it:
 * body text holds a 4.5:1 contrast against the backing laid over the brightest spot sampled behind the card.
 */
final class CardBackdrop {

    static final double CONTRAST = 4.5D;
    private static final double REACH = 96.0D;
    private static final double DARKEST_LIGHT = 0.15D;
    private static final double CLOUD_SHARE = 0.6D;
    private static final int BRIGHTEST_BODY = 0xE8;
    private static final float MOST_OPAQUE = 0.9F;

    private CardBackdrop() {
    }

    record Ink(float backgroundOpacity, int bodyColor) {
    }

    /** The brightest relative luminance among the points of the world seen through these spots, from the camera. */
    static double luminance(Minecraft minecraft, Vec3 camera, Vec3... spots) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return 0.0D;
        }
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        double brightest = 0.0D;
        for (Vec3 spot : spots) {
            Vec3 way = spot.subtract(camera);
            if (way.lengthSqr() < 1.0E-6D) {
                continue;
            }
            Vec3 far = camera.add(way.normalize().scale(REACH));
            BlockHitResult hit = level.clip(new ClipContext(spot, far, ClipContext.Block.VISUAL,
                ClipContext.Fluid.ANY, CollisionContext.empty()));
            brightest = Math.max(brightest, hit.getType() == HitResult.Type.MISS
                ? sky(minecraft, level, camera, way, partialTick)
                : block(level, hit));
        }
        return brightest;
    }

    private static double block(ClientLevel level, BlockHitResult hit) {
        BlockPos at = hit.getBlockPos();
        BlockState state = level.getBlockState(at);
        int color = state.getMapColor(level, at).col;
        int light = level.getMaxLocalRawBrightness(at.relative(hit.getDirection()));
        double lit = DARKEST_LIGHT + (1.0D - DARKEST_LIGHT) * light / 15.0D;
        return luminance(color) * lit;
    }

    private static double sky(Minecraft minecraft, ClientLevel level, Vec3 camera, Vec3 way, float partialTick) {
        Vec3 sky = level.getSkyColor(camera, partialTick);
        double lum = luminance(sky.x, sky.y, sky.z);
        // Clouds drift across any upward view, and they are the brightest thing up there.
        if (way.y > 0.0D && minecraft.options.getCloudsType() != CloudStatus.OFF) {
            Vec3 cloud = level.getCloudColor(partialTick);
            lum += (luminance(cloud.x, cloud.y, cloud.z) - lum) * CLOUD_SHARE;
        }
        return lum;
    }

    /**
     * The least change from the card's own look that holds the contrast: brighten the body toward
     * {@link #BRIGHTEST_BODY} grey first, then darken the backing.
     */
    static Ink ink(double backdrop, float opacity, int bodyColor) {
        double body = luminance(bodyColor);
        double needed = needed((1.0D - opacity) * backdrop);
        if (body >= needed) {
            return new Ink(opacity, bodyColor);
        }
        double brightest = luminance(grey(BRIGHTEST_BODY));
        if (brightest >= needed) {
            return new Ink(opacity, grey(greyFor(needed)));
        }
        double behind = (brightest + 0.05D) / CONTRAST - 0.05D;
        float darker = backdrop <= 0.0D ? opacity : (float) (1.0D - behind / backdrop);
        return new Ink(Math.min(MOST_OPAQUE, Math.max(opacity, darker)), grey(BRIGHTEST_BODY));
    }

    private static double needed(double behind) {
        return CONTRAST * (behind + 0.05D) - 0.05D;
    }

    private static int greyFor(double lum) {
        double srgb = lum <= 0.0031308D ? lum * 12.92D : 1.055D * Math.pow(lum, 1.0D / 2.4D) - 0.055D;
        return Math.min(BRIGHTEST_BODY, (int) Math.ceil(srgb * 255.0D));
    }

    static int grey(int level) {
        return 0xFF000000 | level << 16 | level << 8 | level;
    }

    static double luminance(int rgb) {
        return luminance((rgb >> 16 & 0xFF) / 255.0D, (rgb >> 8 & 0xFF) / 255.0D, (rgb & 0xFF) / 255.0D);
    }

    private static double luminance(double r, double g, double b) {
        return 0.2126D * linear(r) + 0.7152D * linear(g) + 0.0722D * linear(b);
    }

    private static double linear(double channel) {
        return channel <= 0.04045D ? channel / 12.92D : Math.pow((channel + 0.055D) / 1.055D, 2.4D);
    }
}
