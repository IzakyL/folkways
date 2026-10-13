package io.github.izakyl.folkways.front.client;

import com.github.exopandora.shouldersurfing.api.client.IShoulderSurfing;
import com.github.exopandora.shouldersurfing.api.client.world.phys.OffsetPickContext;
import com.github.exopandora.shouldersurfing.api.client.world.phys.PickContext;
import com.github.exopandora.shouldersurfing.api.util.Couple;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Shoulder Surfing Reloaded's crosshair ray. Loaded only when that mod is installed. */
final class ShoulderAim {
    private ShoulderAim() {
    }

    static Aim of(Player player) {
        if (!IShoulderSurfing.getInstance().isShoulderSurfing()) {
            return null;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        PickContext context = new PickContext.Builder(camera).withEntity(player).build();
        // A fixed crosshair sits on the camera's own line of sight; a dynamic one follows the ray the mod traces.
        if (context instanceof OffsetPickContext) {
            return new Aim(camera.getPosition(), new Vec3(camera.getLookVector()));
        }
        Couple<Vec3> ray = context.blockTrace(1.0D, 1.0F);
        Vec3 along = ray.right().subtract(ray.left());
        return along.lengthSqr() < 1.0E-8D ? null : new Aim(ray.left(), along.normalize());
    }
}
