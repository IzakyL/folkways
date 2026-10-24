package io.github.izakyl.folkways.war.client;

import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The inverse of Core's overhead pointer ray, in GUI pixels rather than world X/Z. */
final class CombatProjection {
    record Pixel(double x, double y, double depth) { }
    record Rect(double left, double top, double right, double bottom) {
        static Rect between(double x1, double y1, double x2, double y2) {
            return new Rect(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
        }
        boolean contains(double x, double y) { return x >= left && x <= right && y >= top && y <= bottom; }
        boolean intersects(Rect other) {
            return right >= other.left && left <= other.right && bottom >= other.top && top <= other.bottom;
        }
        double centerX() { return (left + right) / 2; }
        double centerY() { return (top + bottom) / 2; }
    }

    private final Vec3 origin;
    private final Vec3 look;
    private final Vec3 left;
    private final Vec3 up;
    private final double halfWidth;
    private final double halfHeight;
    private final double scaleX;
    private final double scaleY;

    CombatProjection(Minecraft minecraft) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        origin = camera.getPosition();
        look = new Vec3(camera.getLookVector());
        left = new Vec3(camera.getLeftVector());
        up = new Vec3(camera.getUpVector());
        halfWidth = minecraft.getWindow().getGuiScaledWidth() / 2.0;
        halfHeight = minecraft.getWindow().getGuiScaledHeight() / 2.0;
        double tangent = Math.tan(Math.toRadians(minecraft.options.fov().get()) / 2.0);
        double aspect = minecraft.getWindow().getScreenWidth() / (double) minecraft.getWindow().getScreenHeight();
        scaleX = halfWidth / (tangent * aspect);
        scaleY = halfHeight / tangent;
    }

    Optional<Pixel> point(Vec3 world) {
        Vec3 relative = world.subtract(origin);
        double depth = relative.dot(look);
        if (depth <= 0.05) { return Optional.empty(); }
        return Optional.of(new Pixel(halfWidth - relative.dot(left) * scaleX / depth,
            halfHeight - relative.dot(up) * scaleY / depth, depth));
    }

    Optional<Rect> bounds(AABB box) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            Optional<Pixel> pixel = point(new Vec3((corner & 1) == 0 ? box.minX : box.maxX,
                (corner & 2) == 0 ? box.minY : box.maxY, (corner & 4) == 0 ? box.minZ : box.maxZ));
            // A box crossing the camera plane must not become a screen-filling selection target.
            if (pixel.isEmpty()) { return Optional.empty(); }
            minX = Math.min(minX, pixel.get().x()); minY = Math.min(minY, pixel.get().y());
            maxX = Math.max(maxX, pixel.get().x()); maxY = Math.max(maxY, pixel.get().y());
        }
        Rect bounds = new Rect(minX, minY, maxX, maxY);
        return bounds.intersects(new Rect(0, 0, halfWidth * 2, halfHeight * 2)) ? Optional.of(bounds) : Optional.empty();
    }
}
