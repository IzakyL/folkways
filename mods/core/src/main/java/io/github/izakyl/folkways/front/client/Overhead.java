package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.platform.Window;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * The view from above: the camera let go of the player's eyes to fly where it is steered, and the book worked
 * with the mouse from there. The player stands where they were, and still has to hold the book.
 */
@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class Overhead {
    public static final KeyMapping KEY = new KeyMapping("key.folkways.overhead", GLFW.GLFW_KEY_V,
        "key.categories.folkways");

    private static final double RISE = 12.0D;
    private static final float TILT = 45.0F;
    private static final double BLOCKS_A_SECOND = 24.0D;
    private static final double CLIMB_A_SECOND = 8.0D;
    private static final double BLOCKS_A_NOTCH = 4.0D;
    private static final float DEGREES_A_PIXEL = 0.3F;
    private static final double STAND_OFF = 20.0D;

    /** The game's own HUD of a body that is not being played from here. */
    private static final Set<ResourceLocation> BODY_HUD = Set.of(VanillaGuiLayers.CROSSHAIR, VanillaGuiLayers.HOTBAR,
        VanillaGuiLayers.EXPERIENCE_BAR, VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.PLAYER_HEALTH,
        VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.AIR_LEVEL,
        VanillaGuiLayers.VEHICLE_HEALTH, VanillaGuiLayers.JUMP_METER, VanillaGuiLayers.SELECTED_ITEM_NAME);

    /** What the camera rides: an entity of this client alone, in no level's lists. */
    private static Entity eye;
    private static Vec3 focus;

    public record CameraState(Vec3 position, Optional<Vec3> focus, float yaw, float pitch,
                              boolean tactical, Optional<ResourceLocation> tool) { }

    /** A read-only observation of the local camera; it owns no resident or server command. */
    public static Optional<CameraState> camera() {
        return eye == null ? Optional.empty() : Optional.of(new CameraState(eye.position(), Optional.ofNullable(focus),
            eye.getYRot(), eye.getXRot(), tacticalControls(), OverheadTools.selected().map(OverheadTools.Tool::id)));
    }

    /** Whether tools may currently interact with this view, rather than a book page or another screen. */
    public static boolean activeScreen() {
        return on() && Minecraft.getInstance().screen instanceof OverheadScreen;
    }

    public record Ray(Vec3 origin, Vec3 direction) { }

    /** A read-only world ray through a GUI pixel, using the same view as the book's pointer. */
    public static Optional<Ray> screenRay(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!activeScreen()) { return Optional.empty(); }
        Window window = minecraft.getWindow();
        Aim aim = aim(minecraft, 2.0D * mouseX / window.getGuiScaledWidth() - 1.0D,
            1.0D - 2.0D * mouseY / window.getGuiScaledHeight());
        return Optional.of(new Ray(aim.from(), aim.look()));
    }

    static boolean tacticalControls() {
        if (Minecraft.getInstance().screen instanceof OverheadScreen screen) {
            Optional<Boolean> dragging = screen.dragTacticalControls();
            if (dragging.isPresent()) { return dragging.get(); }
        }
        return PersonalCameraControls.globalTacticalControls() || OverheadTools.selected()
            .filter(tool -> tool.camera() == OverheadTools.Camera.TACTICAL).isPresent();
    }

    private Overhead() {
    }

    static boolean on() {
        return eye != null;
    }

    /** Whether a screen stands between the player and the world: any but the one this view is worked through. */
    static boolean screened(Minecraft minecraft) {
        return minecraft.screen != null && !(minecraft.screen instanceof OverheadScreen);
    }

    static void toggle(Minecraft minecraft) {
        if (on()) {
            leave(minecraft);
            return;
        }
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.screen != null
            || HeldBook.bound(player).isEmpty()) {
            return;
        }
        // It starts up behind the player, looking down at where they stand.
        Vec3 back = Vec3.directionFromRotation(0.0F, player.getYRot()).scale(-RISE);
        eye = new Marker(EntityType.MARKER, minecraft.level);
        place(player.getEyePosition().add(back.x, RISE, back.z), player.getYRot(), TILT);
        minecraft.setCameraEntity(eye);
        minecraft.setScreen(new OverheadScreen());
    }

    static void leave(Minecraft minecraft) {
        Entity left = eye;
        forget();
        if (left != null && minecraft.getCameraEntity() == left && minecraft.player != null) {
            minecraft.setCameraEntity(minecraft.player);
        }
        if (minecraft.screen instanceof OverheadScreen) {
            minecraft.setScreen(null);
        }
    }

    static void forget() {
        eye = null;
        focus = null;
    }

    static void tick(Minecraft minecraft) {
        if (!on()) {
            return;
        }
        if (minecraft.player == null || minecraft.level != eye.level() || minecraft.getCameraEntity() != eye
            || HeldBook.bound(minecraft.player).isEmpty()) {
            leave(minecraft);
            return;
        }
        // A panel opened from here takes the screen's place, and gives it back when it closes.
        if (minecraft.screen == null) {
            minecraft.setScreen(new OverheadScreen());
        }
        if (tacticalControls()) {
            if (focus == null) { acquireFocus(minecraft); }
        } else { focus = null; }
    }

    /** The ray under the mouse, out of the camera through the pixel the pointer is on. */
    static Aim aim(Minecraft minecraft) {
        Window window = minecraft.getWindow();
        double across = 2.0D * minecraft.mouseHandler.xpos() / window.getScreenWidth() - 1.0D;
        double up = 1.0D - 2.0D * minecraft.mouseHandler.ypos() / window.getScreenHeight();
        return aim(minecraft, across, up);
    }

    private static Aim aim(Minecraft minecraft, double across, double up) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Window window = minecraft.getWindow();
        double half = Math.tan(Math.toRadians(minecraft.options.fov().get()) / 2.0D);
        double wide = half * window.getScreenWidth() / window.getScreenHeight();
        Vector3f look = new Vector3f(camera.getLookVector())
            .add(new Vector3f(camera.getLeftVector()).mul((float) (-across * wide)))
            .add(new Vector3f(camera.getUpVector()).mul((float) (up * half)));
        return new Aim(camera.getPosition(), new Vec3(look).normalize());
    }

    /**
     * Flies the camera over the ground with the walking keys, and up and down with jump and sneak, for the time
     * one frame took.
     */
    static void fly(Minecraft minecraft, double seconds) {
        if (!on()) {
            return;
        }
        // Told from the keys' own events as well as asked of the window: a screen has the keyboard here.
        double ahead = (Pressed.held(minecraft.options.keyUp) ? 1.0D : 0.0D)
            - (Pressed.held(minecraft.options.keyDown) ? 1.0D : 0.0D);
        double aside = (Pressed.held(minecraft.options.keyRight) ? 1.0D : 0.0D)
            - (Pressed.held(minecraft.options.keyLeft) ? 1.0D : 0.0D);
        double climb = (Pressed.held(minecraft.options.keyJump) ? 1.0D : 0.0D)
            - (Pressed.held(minecraft.options.keyShift) ? 1.0D : 0.0D);
        if (ahead == 0.0D && aside == 0.0D && climb == 0.0D) {
            return;
        }
        Vec3 moved = new Vec3(0.0D, climb * CLIMB_A_SECOND * seconds, 0.0D);
        if (ahead != 0.0D || aside != 0.0D) {
            Vec3 forward = Vec3.directionFromRotation(0.0F, eye.getYRot());
            Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
            moved = moved.add(forward.scale(ahead).add(right.scale(aside)).normalize()
                .scale(BLOCKS_A_SECOND * seconds));
        }
        place(eye.position().add(moved), eye.getYRot(), eye.getXRot());
        if (focus != null) { focus = focus.add(moved); }
    }

    /** Moves the camera along its own line of sight, nearer what it looks at or farther off. */
    static void dolly(double notches) {
        if (on()) {
            if (tacticalControls()) {
                if (focus == null) { acquireFocus(Minecraft.getInstance()); }
                double distance = Mth.clamp(eye.position().distanceTo(focus) - notches * BLOCKS_A_NOTCH, 2, 256);
                place(focus.subtract(eye.getLookAngle().scale(distance)), eye.getYRot(), eye.getXRot());
                return;
            }
            place(eye.position().add(eye.getLookAngle().scale(notches * BLOCKS_A_NOTCH)), eye.getYRot(), eye.getXRot());
        }
    }

    /** Brings the camera, turned as it is, to where the spot is in the middle of its view. */
    static void lookAt(Vec3 spot) {
        if (on()) {
            place(spot.subtract(eye.getLookAngle().scale(STAND_OFF)), eye.getYRot(), eye.getXRot());
            if (tacticalControls()) { focus = spot; }
        }
    }

    static void turn(double dragX, double dragY) {
        if (on()) {
            place(eye.position(), eye.getYRot() + (float) dragX * DEGREES_A_PIXEL,
                Mth.clamp(eye.getXRot() + (float) dragY * DEGREES_A_PIXEL, -90.0F, 90.0F));
        }
    }

    /** Refresh once at the start of a gesture, then keep this actual pivot for the entire drag. */
    static void beginTacticalDrag(Minecraft minecraft) {
        if (on()) { acquireFocus(minecraft); }
    }

    private static void acquireFocus(Minecraft minecraft) {
        Vec3 from = eye.position();
        Vec3 look = eye.getLookAngle();
        if (minecraft.level == null) {
            focus = from.add(look.scale(STAND_OFF));
            return;
        }
        var hit = minecraft.level.clip(new ClipContext(from, from.add(look.scale(128)),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceToSqr(from) >= .25) {
            focus = hit.getLocation();
            return;
        }
        // Empty sky or unloaded terrain has no surface hit. Use the player's ground plane when it
        // lies in front of this view, otherwise a finite point along the current centre ray.
        double ground = minecraft.player == null ? from.y - RISE : minecraft.player.getY();
        double distance = Math.abs(look.y) < .01 ? STAND_OFF : (ground - from.y) / look.y;
        if (!Double.isFinite(distance) || distance < 2 || distance > 128) { distance = STAND_OFF; }
        focus = from.add(look.scale(distance));
    }

    static void pan(Minecraft minecraft, double dragX, double dragY) {
        if (!on()) { return; }
        if (focus == null) { acquireFocus(minecraft); }
        double perPixel = 2 * eye.position().distanceTo(focus)
            * Math.tan(Math.toRadians(minecraft.options.fov().get()) / 2)
            / Math.max(1, minecraft.getWindow().getGuiScaledHeight());
        Vec3 forward = Vec3.directionFromRotation(0, eye.getYRot());
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        double slope = Math.max(.25, Math.abs(Math.sin(Math.toRadians(eye.getXRot()))));
        Vec3 moved = right.scale(-dragX * perPixel).add(forward.scale(dragY * perPixel / slope));
        place(eye.position().add(moved), eye.getYRot(), eye.getXRot());
        focus = focus.add(moved);
    }

    static void orbit(double dragX, double dragY) {
        if (!on() || focus == null) { return; }
        double distance = eye.position().distanceTo(focus);
        float yaw = eye.getYRot() + (float) dragX * DEGREES_A_PIXEL;
        float pitch = Mth.clamp(eye.getXRot() + (float) dragY * DEGREES_A_PIXEL, 10, 85);
        place(focus.subtract(Vec3.directionFromRotation(pitch, yaw).scale(distance)), yaw, pitch);
    }

    // Set down whole, with nothing left to ease in from: it is moved by the frame, not by the tick.
    private static void place(Vec3 at, float yaw, float pitch) {
        eye.moveTo(at.x, at.y, at.z, yaw, pitch);
        eye.setOldPosAndRot();
    }

    // The game still draws for a player looking out of their own eyes: the hand, their HUD, the block in reach.

    @SubscribeEvent
    public static void onHand(RenderHandEvent event) {
        if (on()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLayer(RenderGuiLayerEvent.Pre event) {
        if (on() && BODY_HUD.contains(event.getName())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onOutline(RenderHighlightEvent.Block event) {
        if (on()) {
            event.setCanceled(true);
        }
    }
}
