package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KitchenDeliveryGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(KitchenDeliveryGameTests.class);
    }

    @GameTest(template = "empty")
    public static void rotatedStationDropsOutsideTheWorkersAreaAreDeliveredOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos storage = helper.absolutePos(new BlockPos(2, 1, 2));
        UUID id = UUID.randomUUID();
        Vec3 origin = Vec3.atLowerCornerOf(storage.offset(-24, 0, -8));
        Body person = PersonBody.RESIDENT.get().create(level);
        WorldSpaces.Frame frame = new WorldSpaces.Frame() {
            public UUID id() { return id; }
            public Level level() { return level; }
            public BlockPos storageOrigin() { return storage; }
            public Vec3 toWorld(Vec3 local) { return origin.add(-local.z, local.y, local.x); }
            public Vec3 toLocal(Vec3 world) {
                Vec3 delta = world.subtract(origin);
                return new Vec3(delta.z, delta.y, -delta.x);
            }
        };
        Runnable remove = WorldSpaces.install(new WorldSpaces.Source() {
            public Optional<WorldSpaces.Frame> frame(Level host, UUID key) {
                return host == level && key.equals(id) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> containing(Level host, BlockPos cell) {
                return host == level && cell.equals(storage) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> aboard(Entity entity) {
                return entity == person.mob() ? Optional.of(frame) : Optional.empty();
            }
        });
        Vec3 world = WorldSpaces.world(level, WorldPos.of(level, storage)).orElseThrow().add(0, 0.25, 0);
        person.mob().setPos(world.add(4, -0.25, 0));
        Vec3 stored = Vec3.atBottomCenterOf(storage).add(0, 0.25, 0);
        Vec3 held = person.mob().position().add(0, 0.25, 0);
        var projected = new ItemEntity(level, world.x, world.y, world.z, new ItemStack(Items.CHICKEN, 3));
        var local = new ItemEntity(level, stored.x, stored.y, stored.z, new ItemStack(Items.BONE));
        var carried = new ItemEntity(level, held.x, held.y, held.z, new ItemStack(Items.BOWL));
        ItemStack previous = new ItemStack(Items.APPLE, 7);
        ItemStack hand = new ItemStack(Items.STICK);
        person.mob().setItemInHand(InteractionHand.MAIN_HAND, previous);
        try {
            helper.assertTrue(!person.mob().getBoundingBox().inflate(1).contains(world),
                "the transformed station drop is outside the worker's capture area");
            helper.assertTrue(!new AABB(storage).inflate(1).minmax(person.mob().getBoundingBox().inflate(1)).contains(world),
                "the old storage-to-worker bounding box misses this transformed station drop");
            KitchenDelivery.Result result = KitchenDelivery.run(level, storage, new Cook(person), hand, () -> {
                helper.assertTrue(person.mob().getMainHandItem() == hand, "the real API receives its supplied hand");
                level.addFreshEntity(projected);
                level.addFreshEntity(local);
                level.addFreshEntity(carried);
                return true;
            });
            helper.assertTrue(result.success(), "the interaction result is preserved");
            helper.assertValueEqual(result.outputs().size(), 3, "the three actual drops are each delivered once");
            helper.assertValueEqual(result.outputs().stream().filter(stack -> stack.is(Items.CHICKEN))
                .mapToInt(ItemStack::getCount).sum(), 3, "the rotated world-space station output is retained");
            helper.assertTrue(result.outputs().stream().anyMatch(stack -> stack.is(Items.BONE))
                    && result.outputs().stream().anyMatch(stack -> stack.is(Items.BOWL)),
                "station storage drops and worker container drops are also retained");
            helper.assertTrue(List.of(projected, local, carried).stream()
                    .allMatch(drop -> level.getEntity(drop.getUUID()) == null),
                "captured products do not also remain as loose items");
            helper.assertTrue(person.mob().getMainHandItem() == previous, "the worker's previous hand is restored");
            helper.succeed();
        } finally {
            remove.run();
            List.of(projected, local, carried).forEach(ItemEntity::discard);
            person.mob().discard();
        }
    }

    private record Cook(Body person) implements Worker {
        @Override
        public Resident resident() { return person.resident(); }
        @Override
        public Mob body() { return person.mob(); }
        @Override
        public Container pack() { return person.pack(); }
        @Override
        public List<Store> within() { return List.of(); }
        @Override
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        @Override
        public void spill(ItemStack stack) { throw new IllegalStateException("unexpected spill"); }
        @Override
        public io.github.izakyl.folkways.effect.Effects effects() { return person.effects(); }
        @Override
        public List<Node> route() { return List.of(); }
        @Override
        public Placement placement() { return Placement.NOWHERE; }
    }
}
