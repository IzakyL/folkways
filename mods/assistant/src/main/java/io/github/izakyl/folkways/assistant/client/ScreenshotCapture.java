package io.github.izakyl.folkways.assistant.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.izakyl.folkways.assistant.tool.api.CaptureRequest;
import io.github.izakyl.jpi.agent.AgentToolResult;
import io.github.izakyl.jpi.ai.CancellationToken;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

final class ScreenshotCapture {

    private static Pending pending;

    private static Float captureFov;

    private record Pending(CaptureRequest camera, CompletableFuture<AgentToolResult> result) {
    }

    private ScreenshotCapture() {
    }

    static CompletableFuture<AgentToolResult> capture(CaptureRequest camera, CancellationToken cancellation) {
        CompletableFuture<AgentToolResult> result = new CompletableFuture<>();
        var subscription = cancellation.onCancel(() -> result.complete(ScreenshotImage.error("cancelled")));
        result.orTimeout(10, TimeUnit.SECONDS).whenComplete((value, error) -> subscription.close());
        Minecraft.getInstance().execute(() -> {
            if (result.isDone()) {
                return;
            }
            if (pending != null) {
                result.complete(ScreenshotImage.error("screenshot_busy"));
            } else if (Minecraft.getInstance().level == null || Minecraft.getInstance().player == null) {
                result.complete(ScreenshotImage.error("no_world"));
            } else {
                pending = new Pending(camera, result);
            }
        });
        return result.exceptionally(error -> ScreenshotImage.error("screenshot_failed"));
    }

    /**
     * Places a framed shot's camera for this window, then pulls it in front of anything standing between the
     * subject and the camera. A side view that ends up crowded against its subject tries higher before settling
     * for the clearest of its tries.
     */
    private static CaptureRequest frame(Minecraft minecraft, CaptureRequest shot) {
        var window = minecraft.getWindow();
        double aspect = window.getWidth() / (double) Math.max(1, window.getHeight());
        AABB margin = shot.subject().inflate(1);
        CaptureRequest best = null;
        double clearest = -1;
        for (double elevation : shot.view().equals("top") ? new double[] {90}
                : new double[] {CaptureRequest.SIDE_ELEVATION, 55, 75}) {
            CaptureRequest placed = shot.framed(aspect, elevation);
            Vec3 target = shot.subject().getCenter();
            Vec3 ideal = placed.position();
            Vec3 edge = margin.clip(ideal, target).orElse(target);
            Vec3 outward = ideal.subtract(edge);
            double wanted = outward.length();
            BlockHitResult hit = minecraft.level.clip(new ClipContext(edge, ideal, ClipContext.Block.VISUAL,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.MISS || wanted < 1.0e-6) {
                return placed;
            }
            Vec3 stop = hit.getLocation().subtract(outward.normalize().scale(0.6));
            double clear = stop.subtract(edge).dot(outward.normalize());
            if (clear > clearest) {
                clearest = clear;
                Vec3 position = clear > 0 ? stop : edge;
                best = new CaptureRequest(position, placed.lookAt(), placed.yaw(), placed.pitch(), placed.fov(),
                        placed.subject(), placed.view(), placed.size());
            }
            if (clear >= Math.min(wanted, 8)) {
                break;
            }
        }
        return best;
    }

    static boolean capturing() {
        return captureFov != null;
    }

    static void fov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        if (captureFov != null) {
            event.setFOV(captureFov);
        }
    }

    static void beforeFrame(DeltaTracker delta) {
        Pending request = pending;
        pending = null;
        if (request == null || request.result().isDone()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            request.result().complete(ScreenshotImage.error("no_world"));
            return;
        }
        CaptureRequest camera = request.camera().subject() == null ? request.camera() : frame(minecraft,
                request.camera());
        Vec3 position = camera.position() == null ? minecraft.player.getEyePosition() : camera.position();
        double limit = Math.max(0, minecraft.options.getEffectiveRenderDistance() - 1) * 16.0;
        Vec3 player = minecraft.player.position();
        double dx = position.x - player.x;
        double dy = position.y - player.y;
        double dz = position.z - player.z;
        if (dx * dx + dy * dy + dz * dz > limit * limit || !minecraft.level.hasChunk(
                Mth.floor(position.x) >> 4, Mth.floor(position.z) >> 4)) {
            request.result().complete(ScreenshotImage.error("camera_out_of_range"));
            return;
        }
        BlockPos block = BlockPos.containing(position);
        var state = minecraft.level.getBlockState(block);
        if (state.canOcclude() || state.isSolidRender(minecraft.level, block)
                || Block.isShapeFullBlock(
                        state.getCollisionShape(minecraft.level, block))) {
            request.result().complete(ScreenshotImage.error("camera_inside_solid"));
            return;
        }
        float yaw = camera.yaw() == null ? minecraft.player.getYRot() : camera.yaw();
        float pitch = camera.pitch() == null ? minecraft.player.getXRot() : camera.pitch();
        if (camera.lookAt() != null) {
            Vec3 direction = camera.lookAt().subtract(position);
            if (direction.lengthSqr() < 1.0e-12) {
                request.result().complete(ScreenshotImage.error("invalid_arguments"));
                return;
            }
            yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
            pitch = (float) -Math.toDegrees(Math.atan2(direction.y,
                    Math.sqrt(direction.x * direction.x + direction.z * direction.z)));
        }
        Entity original = minecraft.getCameraEntity();
        CameraType cameraType = minecraft.options.getCameraType();
        var previousHit = minecraft.hitResult;
        var previousEntity = minecraft.crosshairPickEntity;
        BufferedImage image = null;
        String pose = "Camera: dimension=" + minecraft.level.dimension().location() + " position=("
                + position.x + "," + position.y + "," + position.z + ") yaw=" + yaw + " pitch=" + pitch
                + " fov=" + camera.fov() + (camera.view() == null ? "" : " view=" + camera.view());
        try {
            float partial = delta.getGameTimeDeltaPartialTick(true);
            // Set first, so anything that steers the camera can tell this setup belongs to the shot.
            captureFov = camera.fov();
            var view = minecraft.gameRenderer.getMainCamera();
            Entity old = original == null ? minecraft.player : original;
            view.setup(minecraft.level, old, false, false, partial);
            double eyeOffset = view.getPosition().y - Mth.lerp((double) partial, old.yo, old.getY());
            Marker eye = new Marker(EntityType.MARKER, minecraft.level);
            eye.moveTo(position.x, position.y - eyeOffset, position.z, yaw, pitch);
            eye.setOldPosAndRot();
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            minecraft.setCameraEntity(eye);
            minecraft.levelRenderer.needsUpdate();
            minecraft.gameRenderer.renderLevel(delta);
            minecraft.gameRenderer.renderLevel(delta);
            try (NativeImage source = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                double scale = Math.min(1.0,
                        (double) camera.size() / Math.max(source.getWidth(), source.getHeight()));
                int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
                try (NativeImage resized = new NativeImage(width, height, false)) {
                    source.resizeSubRectTo(0, 0, source.getWidth(), source.getHeight(), resized);
                    image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int color = resized.getPixelRGBA(x, y);
                            image.setRGB(x, y, (color & 255) << 16 | color & 0xff00 | color >>> 16 & 255);
                        }
                    }
                }
            }
        } catch (RuntimeException failure) {
            request.result().complete(ScreenshotImage.error("screenshot_failed"));
        } finally {
            captureFov = null;
            minecraft.setCameraEntity(original);
            minecraft.options.setCameraType(cameraType);
            minecraft.hitResult = previousHit;
            minecraft.crosshairPickEntity = previousEntity;
            minecraft.levelRenderer.needsUpdate();
        }
        if (image != null) {
            BufferedImage captured = image;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return ScreenshotImage.encode(captured, pose, camera.size());
                } catch (Exception failure) {
                    return ScreenshotImage.error("screenshot_failed");
                }
            }).thenAccept(request.result()::complete);
        }
    }
}
