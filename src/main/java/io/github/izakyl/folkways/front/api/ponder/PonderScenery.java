package io.github.izakyl.folkways.front.api.ponder;

import io.github.izakyl.folkways.core.api.terms.Gait;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class PonderScenery {

    public static final int WORLD_SIZE = 16;

    private PonderScenery() {
    }

    public static void openWorld(SceneBuilder scene, SceneBuildingUtil util, int plateSize) {
        scene.addInstruction(active -> {
            active.getWorld().setBounds(new BoundingBox(0, 0, 0, WORLD_SIZE - 1, WORLD_SIZE - 1, WORLD_SIZE - 1));
            active.getBaseWorldSection()
                .set(util.select().fromTo(0, 0, 0, WORLD_SIZE - 1, WORLD_SIZE - 1, WORLD_SIZE - 1));
        });
        scene.configureBasePlate(0, 0, plateSize);
    }

    public static void ground(SceneBuilder scene, SceneBuildingUtil util, int size, Block block) {
        scene.world().setBlocks(util.select().fromTo(0, 0, 0, size - 1, 0, size - 1), block.defaultBlockState(), false);
        scene.showBasePlate();
    }

    public static void grassGround(SceneBuilder scene, SceneBuildingUtil util, int size) {
        ground(scene, util, size, Blocks.GRASS_BLOCK);
    }

    public static Set<BlockPos> ring(SceneBuildingUtil util, int x0, int z0, int x1, int z1, int y) {
        Set<BlockPos> posts = new LinkedHashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x == x0 || x == x1 || z == z0 || z == z1) {
                    posts.add(util.grid().at(x, y, z));
                }
            }
        }
        return posts;
    }

    public static void fence(SceneBuilder scene, Block fence, Collection<BlockPos> posts) {
        for (BlockPos post : posts) {
            BlockState state = fence.defaultBlockState()
                .setValue(CrossCollisionBlock.NORTH, posts.contains(post.north()))
                .setValue(CrossCollisionBlock.SOUTH, posts.contains(post.south()))
                .setValue(CrossCollisionBlock.EAST, posts.contains(post.east()))
                .setValue(CrossCollisionBlock.WEST, posts.contains(post.west()));
            scene.world().setBlock(post, state, false);
        }
    }

    // A block of a mod this one does not compile against, by id, with properties given as name, value pairs.
    // A property the block lacks, or a value it does not take, is left at its default.
    public static BlockState foreign(ResourceLocation id, String... properties) {
        BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        for (int at = 0; at + 1 < properties.length; at += 2) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(properties[at]);
            if (property != null) {
                state = set(state, property, properties[at + 1]);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }

    public static void stand(Entity entity, Vec3 pos, float yRot) {
        entity.setPos(pos);
        entity.setYRot(yRot);
        entity.setYHeadRot(yRot);
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = yRot;
            living.yBodyRotO = yRot;
        }
        entity.setOldPosAndRot();
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setNoGravity(true);
    }

    public static Body body(SceneBuilder scene, EntityType<?> type, Vec3 pos, float yRot) {
        ElementLink<EntityElement> link = scene.world().createEntity(level -> {
            Entity body = type.create(level);
            if (body == null) {
                return null;
            }
            stand(body, pos, yRot);
            return body;
        });
        return new Body(link, pos, yRot);
    }

    public static final class Body {

        private final ElementLink<EntityElement> link;
        private Vec3 at;
        private float facing;

        private Body(ElementLink<EntityElement> link, Vec3 at, float facing) {
            this.link = link;
            this.at = at;
            this.facing = facing;
        }

        public Vec3 at() {
            return at;
        }

        public void walkTo(SceneBuilder scene, Vec3 target) {
            Vec3 from = at;
            double distance = target.subtract(from).length();
            int steps = Math.max(1, Gait.ticksToWalk(distance));
            float yaw = distance > 1.0E-4D ? yawTowards(from, target) : facing;
            for (int step = 1; step <= steps; step++) {
                Vec3 before = lerp(from, target, (step - 1) / (double) steps);
                Vec3 after = lerp(from, target, step / (double) steps);
                scene.world().modifyEntity(link, entity -> stride(entity, before, after, yaw));
                scene.idle(1);
            }
            scene.world().modifyEntity(link, entity -> stand(entity, target, yaw));
            at = target;
            facing = yaw;
        }

        public void turnTo(SceneBuilder scene, float yRot) {
            Vec3 here = at;
            scene.world().modifyEntity(link, entity -> stand(entity, here, yRot));
            facing = yRot;
        }

        public void hold(SceneBuilder scene, ItemStack stack) {
            scene.world().modifyEntity(link, entity -> {
                if (entity instanceof LivingEntity living) {
                    living.setItemInHand(InteractionHand.MAIN_HAND, stack);
                }
            });
        }
    }

    private static void stride(Entity entity, Vec3 from, Vec3 to, float yaw) {
        entity.setPos(to);
        entity.xOld = from.x;
        entity.yOld = from.y;
        entity.zOld = from.z;
        entity.xo = from.x;
        entity.yo = from.y;
        entity.zo = from.z;
        entity.yRotO = entity.getYRot();
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.setDeltaMovement(Vec3.ZERO);
    }

    private static float yawTowards(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * (180.0D / Math.PI)) - 90.0F;
    }

    private static Vec3 lerp(Vec3 from, Vec3 to, double t) {
        return new Vec3(Mth.lerp(t, from.x, to.x), Mth.lerp(t, from.y, to.y), Mth.lerp(t, from.z, to.z));
    }
}
