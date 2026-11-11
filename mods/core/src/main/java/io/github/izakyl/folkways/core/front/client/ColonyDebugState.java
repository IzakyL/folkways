package io.github.izakyl.folkways.core.front.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.front.engine.net.DebugFocusPacket;
import io.github.izakyl.folkways.core.front.engine.net.DebugFocusPacket.Kind;
import io.github.izakyl.folkways.core.front.engine.net.DebugStatePacket;
import io.github.izakyl.folkways.core.front.engine.net.DebugWaypointsPacket;
import io.github.izakyl.folkways.core.front.engine.net.RequestDebugWaypointsPacket;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.Snapshot;
import io.github.izakyl.folkways.core.front.engine.net.DebugTarget;
import io.github.izakyl.folkways.core.front.engine.net.RequestDebugFocusPacket;
import java.util.Optional;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, value = Dist.CLIENT)
public final class ColonyDebugState {
    private static UUID colony;
    private static DebugTarget aimed = DebugTarget.NONE;
    private static DebugTarget target = DebugTarget.NONE;
    private static DebugFocusPacket snapshot = empty();
    private static int scroll;
    private static int contentHeight;
    private static int panelLeft;
    private static int panelBottom;
    private static int panelTop;
    private static int panelRight;
    private static long lastSeen;
    private static long lastRequest;
    private static long lastReply;
    private static long lastWaypointsRequest;
    private static long lastWaypointsReply;
    private static Snapshot waypoints = Snapshot.EMPTY;
    private static ClientLevel world;

    private ColonyDebugState() {
    }

    private static DebugFocusPacket empty() {
        return new DebugFocusPacket(DebugTarget.NONE, List.of(), List.of(), List.of());
    }

    public static void state(DebugStatePacket packet) {
        colony = packet.colony().orElse(null);
        clear();
        world = Minecraft.getInstance().level;
    }

    public static void focus(DebugFocusPacket packet) {
        if (colony != null) {
            if (packet.target() instanceof DebugTarget.None && !(snapshot.target() instanceof DebugTarget.None)
                    && Util.getMillis() - lastSeen <= 1000L) {
                snapshot = new DebugFocusPacket(snapshot.target(), snapshot.card(), snapshot.links(), packet.stores());
            } else {
                if (!snapshot.target().equals(packet.target())) {
                    scroll = 0;
                }
                snapshot = packet;
            }
            if (packet.target().equals(aimed) && !(aimed instanceof DebugTarget.None)) {
                lastSeen = Util.getMillis();
            }
            lastReply = Util.getMillis();
        }
    }

    public static void waypoints(DebugWaypointsPacket packet) {
        var level = Minecraft.getInstance().level;
        if (packet.colony().equals(colony) && level != null && packet.dimension().equals(level.dimension())) {
            waypoints = packet.graph();
            lastWaypointsReply = Util.getMillis();
        }
    }

    private static boolean freshWaypoints() {
        return colony != null && Util.getMillis() - lastWaypointsReply <= 3000L;
    }

    private static void clear() {
        scroll = 0;
        contentHeight = 0;
        aimed = DebugTarget.NONE;
        target = DebugTarget.NONE;
        snapshot = empty();
        lastSeen = 0;
        lastRequest = 0;
        lastReply = 0;
        lastWaypointsRequest = 0;
        lastWaypointsReply = 0;
        waypoints = Snapshot.EMPTY;
    }

    private static boolean fresh() {
        return colony != null && Util.getMillis() - lastReply <= 2000L;
    }

    static boolean focused(int entityId) {
        return fresh() && snapshot.target() instanceof DebugTarget.Resident resident
            && resident.entityId() == entityId && !snapshot.card().isEmpty();
    }

    static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            colony = null;
            clear();
            world = null;
            return;
        }
        if (world != minecraft.level) {
            clear();
            world = minecraft.level;
        }
        if (colony == null) {
            return;
        }
        long now = Util.getMillis();
        if (now - lastWaypointsRequest >= 1000L) {
            lastWaypointsRequest = now;
            PacketDistributor.sendToServer(new RequestDebugWaypointsPacket(colony));
        }
        DebugTarget picked = pick(minecraft);
        aimed = picked;
        if (!(picked instanceof DebugTarget.None)) {
            target = picked;
            if (picked.equals(snapshot.target())) {
                lastSeen = now;
            }
        } else if (now - lastSeen > 1000L) {
            target = DebugTarget.NONE;
        }
        if (now - lastRequest >= 250L) {
            lastRequest = now;
            PacketDistributor.sendToServer(new RequestDebugFocusPacket(colony, target));
        }
    }

    private static DebugTarget pick(Minecraft minecraft) {
        if (minecraft.screen != null && !Overhead.activeScreen()) {
            return DebugTarget.NONE;
        }
        Aim aim = Aim.of(minecraft.player);
        double range = Overhead.on() ? Aim.range() : 32.0D;
        var block = aim.block(minecraft.player);
        if (block.isPresent()) {
            var hit = block.get();
            double distance = hit.getLocation().distanceTo(aim.in(minecraft.level, hit.getBlockPos()).from());
            if (distance <= range) {
                range = distance;
            } else {
                block = Optional.empty();
            }
        }
        Vec3 end = aim.at(range);
        var entity = ProjectileUtil.getEntityHitResult(minecraft.player, aim.from(), end,
            new AABB(aim.from(), end).inflate(1.0D), candidate -> candidate.isAlive() && candidate.isPickable()
                && !candidate.isSpectator(), range * range);
        if (entity != null) {
            return new DebugTarget.Resident(entity.getEntity().getId());
        }
        return block.<DebugTarget>map(hit -> new DebugTarget.Block(hit.getBlockPos())).orElse(DebugTarget.NONE);
    }

    static void render(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ShadowPass.active() || colony == null || minecraft.level == null
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        var stack = event.getPoseStack();
        var buffers = minecraft.renderBuffers().bufferSource();
        stack.pushPose();
        stack.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer lines = buffers.getBuffer(Layers.THIN);
        if (freshWaypoints()) {
            ColonyDebugWaypoints.render(minecraft.level, stack, lines, waypoints, partial);
        }
        for (var mark : fresh() ? snapshot.stores() : List.<DebugFocusPacket.StoreMark>of()) {
            float[] rgb = switch (mark.state()) {
                case OBSERVED -> new float[] {0.2F, 0.9F, 0.3F};
                case DIRTY, QUEUED -> new float[] {1.0F, 0.85F, 0.2F};
                case UNOBSERVED -> new float[] {0.6F, 0.6F, 0.6F};
                case UNLOADED -> new float[] {0.3F, 0.5F, 1.0F};
                case UNSUPPORTED -> new float[] {1.0F, 0.2F, 0.2F};
            };
            Placed.at(minecraft.level, mark.pos(), partial).enter(stack);
            LevelRenderer.renderLineBox(stack, lines, new AABB(BlockPos.ZERO).inflate(0.01D),
                rgb[0], rgb[1], rgb[2], 0.8F);
            if (mark.differs()) {
                LevelRenderer.renderLineBox(stack, lines, new AABB(-0.04D, 0.85D, -0.04D, 0.15D, 1.04D, 0.15D),
                    1.0F, 0.1F, 0.1F, 1.0F);
            }
            stack.popPose();
        }
        buffers.endBatch(Layers.THIN);
        for (var link : fresh() ? snapshot.links() : List.<DebugFocusPacket.Link>of()) {
            Vec3 from = position(minecraft, link.from(), partial);
            Vec3 to = position(minecraft, link.to(), partial);
            if (from == null || to == null) {
                continue;
            }
            float[] rgb = switch (link.kind()) {
                case ROUTE -> new float[] {0.4F, 0.9F, 1.0F};
                case DRAW -> new float[] {1.0F, 0.8F, 0.3F};
                case INTO -> new float[] {0.3F, 1.0F, 0.5F};
                case ASSIGNEE -> new float[] {0.9F, 0.6F, 1.0F};
            };
            RenderType type = link.kind() == Kind.ROUTE || link.kind() == Kind.ASSIGNEE ? Layers.THICK : Layers.THIN;
            PathSelection.segment(stack, buffers.getBuffer(type), from, to, rgb, 0.9F);
            buffers.endBatch(type);
            WorldCards.queue(from.add(to).scale(0.5D).add(0, 0.25D, 0),
                ColonyLookCard.rows(List.of(Line.text(link.label()))), 0.018F, WorldCards.Look.PLATE, camera);
        }
        stack.popPose();
    }

    private static final class Layers extends RenderType {
        static final RenderType THIN = lines("folkways_debug_thin", 1.0D);
        static final RenderType THICK = lines("folkways_debug_thick", 2.5D);

        private static RenderType lines(String name, double width) {
            return create(name, DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1536,
                CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new LineStateShard(OptionalDouble.of(width)))
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .createCompositeState(false));
        }

        private Layers(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling,
                boolean sorted, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sorted, setup, clear);
        }
    }

    private static Vec3 position(Minecraft minecraft, DebugTarget endpoint, float partial) {
        return switch (endpoint) {
            case DebugTarget.Resident resident -> {
                var entity = minecraft.level.getEntity(resident.entityId());
                yield entity == null ? null : entity.getPosition(partial).add(0, entity.getBbHeight() * 0.5D, 0);
            }
            case DebugTarget.Block block -> Placed.at(minecraft.level, block.pos(), partial).centre();
            case DebugTarget.None ignored -> null;
        };
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!Overhead.on()) {
            if (freshWaypoints() && waypoints.truncated() && !minecraft.options.hideGui && !ShadowPass.active()) {
                var graphics = event.getGuiGraphics();
                graphics.drawString(minecraft.font, Component.translatable("folkways.debug.waypoints.truncated"),
                    8, graphics.guiHeight() - 40, 0xFFFFF5DC);
            }
            panel(event.getGuiGraphics(), minecraft);
        }
    }

    static void panel(GuiGraphics graphics, Minecraft minecraft) {
        if (ShadowPass.active() || !fresh() || snapshot.card().isEmpty() || minecraft.options.hideGui) {
            return;
        }
        int width = Math.min(320, graphics.guiWidth() * 2 / 5);
        panelRight = graphics.guiWidth() - 8;
        panelLeft = panelRight - width;
        // Vanilla tutorial toasts can occupy any of the five 32px slots in the top-right corner.
        panelTop = minecraft.getToasts().getToast(TutorialToast.class, Toast.NO_TOKEN) == null ? 8 : 168;
        int innerWidth = Math.max(1, width - 8);
        var laid = DebugCardLayout.of(minecraft.font, snapshot.card(), innerWidth);
        var footer = minecraft.font.split(Component.translatable("folkways.debug.overlay.scroll"), innerWidth);
        int footerHeight = footer.size() * (minecraft.font.lineHeight + 1) + 4;
        int height = Math.max(1, graphics.guiHeight() - panelTop - 16);
        contentHeight = laid.height();
        if (contentHeight > height) {
            height = Math.max(1, height - footerHeight);
        }
        scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - height));
        panelBottom = panelTop + 4 + Math.min(height, contentHeight);
        graphics.fill(panelLeft, panelTop, panelRight,
            panelBottom + 4 + (contentHeight > height ? footerHeight : 0), 0xDD101820);
        graphics.enableScissor(panelLeft + 4, panelTop + 4, panelRight - 4, panelBottom);
        graphics.pose().pushPose();
        graphics.pose().translate(panelLeft + 4, panelTop + 4 - scroll, 0);
        laid.draw(graphics);
        graphics.pose().popPose();
        graphics.disableScissor();
        if (contentHeight > height) {
            int y = panelBottom + 4;
            for (var part : footer) {
                graphics.drawString(minecraft.font, part, panelLeft + 4, y, 0xFFAAAAAA);
                y += minecraft.font.lineHeight + 1;
            }
        }
    }

    static boolean scroll(double delta) {
        Minecraft minecraft = Minecraft.getInstance();
        double x = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()
            / minecraft.getWindow().getScreenWidth();
        double y = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()
            / minecraft.getWindow().getScreenHeight();
        return scroll(x, y, delta);
    }

    static boolean scroll(double x, double y, double delta) {
        if (!fresh() || snapshot.card().isEmpty() || contentHeight <= panelBottom - panelTop - 4
                || !(Screen.hasAltDown()
                    || Overhead.activeScreen() && x >= panelLeft && x <= panelRight
                        && y >= panelTop && y <= panelBottom)) {
            return false;
        }
        scroll = Math.clamp(scroll - (int) (delta * 30), 0, Math.max(0, contentHeight - (panelBottom - panelTop - 4)));
        return true;
    }
}
