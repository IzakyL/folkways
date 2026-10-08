package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.front.api.Ghost;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * Draws the blocks a colony still owes the way Litematica and Create draw a schematic. The owed blocks are meshed
 * once, against a view that puts them in the world, so faces between two of them are culled; the mesh is drawn
 * twice, first to depth alone and then in colour at that depth, so only the nearest face of each shows and the
 * ghosts fade as one solid shape. Where a wrong block already stands, the ghost would sink into it, so the cell is
 * boxed instead, red for the wrong block and orange for the right block set the wrong way.
 */
final class GhostRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int GHOST_ALPHA = 128;
    private static final float[] MISSING_RGB = {0.45F, 0.85F, 1.00F};
    private static final float[] WRONG_BLOCK_RGB = {1.00F, 0.30F, 0.30F};
    private static final float[] WRONG_STATE_RGB = {1.00F, 0.62F, 0.15F};
    private static final float MARK_FILL = 0.18F;

    private static final List<Mesh> MESHES = new ArrayList<>();
    private static final List<Mark> MARKS = new ArrayList<>();
    private static List<Ghost> shown = List.of();
    private static List<BlockState> standing = List.of();
    private static boolean stale;

    private GhostRenderer() {
    }

    /** Takes the owed blocks the server sent, remeshing only when they or the blocks standing in them changed. */
    static void show(Level level, List<Ghost> ghosts) {
        List<BlockState> now = ghosts.stream().map(ghost -> level.getBlockState(ghost.pos())).toList();
        if (ghosts.equals(shown) && now.equals(standing)) {
            return;
        }
        shown = List.copyOf(ghosts);
        standing = now;
        stale = true;
    }

    static void clear() {
        MESHES.forEach(mesh -> mesh.buffer().close());
        MESHES.clear();
        MARKS.clear();
        shown = List.of();
        standing = List.of();
        stale = false;
    }

    static boolean isEmpty() {
        return shown.isEmpty();
    }

    static void drawModels(Level level, Vec3 camera, float partialTick) {
        if (stale) {
            remesh(level);
        }
        if (MESHES.isEmpty()) {
            return;
        }
        List<Matrix4f> views = MESHES.stream().map(mesh -> mesh.view(level, camera, partialTick)).toList();
        pass(Layers.DEPTH, views);
        pass(Layers.COLOR, views);
    }

    static void drawFills(Level level, PoseStack poseStack, VertexConsumer fills, float partialTick) {
        for (Mark mark : MARKS) {
            Placed.at(level, mark.pos(), partialTick).enter(poseStack);
            AABB box = mark.box();
            LevelRenderer.addChainedFilledBoxVertices(poseStack, fills,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                mark.rgb()[0], mark.rgb()[1], mark.rgb()[2], MARK_FILL);
            poseStack.popPose();
        }
    }

    static void drawOutlines(Level level, PoseStack poseStack, VertexConsumer lines, float partialTick) {
        for (Mark mark : MARKS) {
            Placed.at(level, mark.pos(), partialTick).enter(poseStack);
            LevelRenderer.renderLineBox(poseStack, lines, mark.box(),
                mark.rgb()[0], mark.rgb()[1], mark.rgb()[2], 1.0F);
            poseStack.popPose();
        }
    }

    private static void pass(RenderType layer, List<Matrix4f> views) {
        layer.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            for (int at = 0; at < MESHES.size(); at++) {
                VertexBuffer buffer = MESHES.get(at).buffer();
                buffer.bind();
                buffer.drawWithShader(views.get(at), RenderSystem.getProjectionMatrix(), shader);
            }
            VertexBuffer.unbind();
        }
        layer.clearRenderState();
    }

    private static void remesh(Level level) {
        List<Ghost> ghosts = shown;
        List<BlockState> stood = standing;
        clear();
        shown = ghosts;
        standing = stood;

        Map<BlockPos, BlockState> modelled = new HashMap<>();
        Map<Optional<UUID>, List<BlockPos>> frames = new LinkedHashMap<>();
        for (int at = 0; at < ghosts.size(); at++) {
            Ghost ghost = ghosts.get(at);
            BlockState wanted = ghost.state();
            BlockState there = stood.get(at);
            if (wanted.getRenderShape() == RenderShape.INVISIBLE) {
                continue;
            }
            if (there.is(wanted.getBlock())) {
                MARKS.add(Mark.whole(ghost.pos(), WRONG_STATE_RGB));
            } else if (!there.isAir() && !there.canBeReplaced()) {
                MARKS.add(Mark.whole(ghost.pos(), WRONG_BLOCK_RGB));
            } else if (wanted.getRenderShape() == RenderShape.MODEL) {
                modelled.put(ghost.pos(), wanted);
                frames.computeIfAbsent(WorldSpaces.containing(level, ghost.pos()).map(WorldSpaces.Frame::id),
                    frame -> new ArrayList<>()).add(ghost.pos());
            } else {
                MARKS.add(Mark.shaped(level, ghost.pos(), wanted, MISSING_RGB));
            }
        }

        GhostView view = new GhostView(level, modelled);
        for (List<BlockPos> cells : frames.values()) {
            mesh(view, cells);
        }
    }

    /** Meshes one frame's ghosts around the first of them, so the mesh can follow the frame as it moves. */
    private static void mesh(GhostView view, List<BlockPos> cells) {
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        BlockPos origin = cells.getFirst();
        RandomSource random = RandomSource.create();
        PoseStack poseStack = new PoseStack();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(RenderType.translucent().bufferSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            VertexConsumer faded = new Fading(builder, GHOST_ALPHA);
            for (BlockPos pos : cells) {
                BlockState state = view.getBlockState(pos);
                BakedModel model = blocks.getBlockModel(state);
                ModelData data = model.getModelData(view, pos, state, ModelData.EMPTY);
                random.setSeed(state.getSeed(pos));
                poseStack.pushPose();
                poseStack.translate(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ());
                // A modded model may expect a real level behind it; one that cannot mesh here is left out, as
                // Create's schematic renderer leaves it out.
                try {
                    for (RenderType type : model.getRenderTypes(state, random, data)) {
                        blocks.renderBatched(state, pos, view, poseStack, faded, true, random, data, type);
                    }
                } catch (RuntimeException failure) {
                    LOGGER.debug("Could not mesh the ghost of {} at {}", state, pos, failure);
                }
                poseStack.popPose();
            }
            MeshData mesh = builder.build();
            if (mesh != null) {
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                buffer.upload(mesh);
                VertexBuffer.unbind();
                MESHES.add(new Mesh(origin, buffer));
            }
        }
    }

    private record Mesh(BlockPos origin, VertexBuffer buffer) {

        Matrix4f view(Level level, Vec3 camera, float partialTick) {
            Placed placed = Placed.at(level, origin, partialTick);
            Vec3 at = placed.origin().subtract(camera);
            return new Matrix4f(RenderSystem.getModelViewMatrix())
                .translate((float) at.x, (float) at.y, (float) at.z)
                .mul(new Matrix4f(placed.turn()));
        }
    }

    private record Mark(BlockPos pos, AABB box, float[] rgb) {

        static Mark whole(BlockPos pos, float[] rgb) {
            return new Mark(pos, new AABB(BlockPos.ZERO).inflate(0.01D), rgb);
        }

        static Mark shaped(Level level, BlockPos pos, BlockState state, float[] rgb) {
            VoxelShape shape = state.getShape(level, pos);
            return new Mark(pos, shape.isEmpty() ? new AABB(BlockPos.ZERO) : shape.bounds(), rgb);
        }
    }

    /**
     * The world with the ghosts standing in it, so a ghost's faces cull against its neighbours and connect to
     * them. Light is full everywhere, as in Litematica's schematic world, so a ghost reads the same by day or
     * night; ambient occlusion still shades it where it meets solid blocks.
     */
    private record GhostView(Level level, Map<BlockPos, BlockState> ghosts) implements BlockAndTintGetter {

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState ghost = ghosts.get(pos);
            return ghost != null ? ghost : level.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return ghosts.containsKey(pos) ? null : level.getBlockEntity(pos);
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return level.getShade(direction, shade);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return level.getLightEngine();
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return getMaxLightLevel();
        }

        @Override
        public int getRawBrightness(BlockPos pos, int darkening) {
            return getMaxLightLevel();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return level.getBlockTint(pos, resolver);
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }
    }

    private record Fading(VertexConsumer delegate, int alpha) implements VertexConsumer {

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int opacity) {
            delegate.setColor(red, green, blue, opacity * alpha / 255);
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

    /**
     * The two passes: depth alone first, through the cutout shader so a leaf's empty pixels write no depth, then
     * colour, faded, only where a face meets the depth already written.
     */
    private static final class Layers extends RenderType {
        static final RenderType DEPTH = create("folkways_ghost_depth", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, TRANSIENT_BUFFER_SIZE, false, false,
            CompositeState.builder()
                .setLightmapState(LIGHTMAP)
                .setShaderState(RENDERTYPE_CUTOUT_SHADER)
                .setTextureState(BLOCK_SHEET_MIPPED)
                .setWriteMaskState(DEPTH_WRITE)
                .setOutputState(TRANSLUCENT_TARGET)
                .createCompositeState(false));
        static final RenderType COLOR = create("folkways_ghost_color", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, TRANSIENT_BUFFER_SIZE, false, false,
            CompositeState.builder()
                .setLightmapState(LIGHTMAP)
                .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
                .setTextureState(BLOCK_SHEET_MIPPED)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setWriteMaskState(COLOR_WRITE)
                .setOutputState(TRANSLUCENT_TARGET)
                .createCompositeState(false));

        private Layers(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling,
                boolean sorted, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sorted, setup, clear);
        }
    }
}
