package io.github.izakyl.folkways.vanilla.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import com.jusipat.astikorcartsredux.entity.PlowEntity;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class CartFacilityGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CartFacilityGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void claimedCartAcceptsDestinationAndCargo(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos goal = start.east(4);
        for (BlockPos floor : BlockPos.betweenClosed(start.offset(-2, -1, -2), goal.offset(1, -1, 2))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }

        var colony = Colonies.mint(level.getServer());
        var horse = EntityType.HORSE.create(level);
        horse.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5, -90, 0);
        horse.setTamed(true);
        level.addFreshEntity(horse);
        var cart = (AbstractCargoCart) BuiltInRegistries.ENTITY_TYPE
            .get(ResourceLocation.parse("astikorcartsredux:supply_cart")).create(level);
        cart.moveTo(start.west(2).getBottomCenter());
        cart.setYRot(-90);
        level.addFreshEntity(cart);
        colony.hold(CartsPlugin.OWNER, Claimed.Entity.of(cart)).orElseThrow();
        cart.setPulling(horse);

        helper.runAfterDelay(5, () -> {
            var facilities = DefinedFacilities.of(colony).orElseThrow();
            String name = "cart_" + cart.getUUID();
            helper.assertTrue(facilities.list().contains(name), "the claimed cart is listed");
            var target = new Value.Vector(List.of((double) goal.getX(), (double) goal.getY(), (double) goal.getZ()));
            cart.setPulling(horse);
            horse.setOnGround(true);
            var movement = facilities.run(name, "move_to", Map.of("target", target));
            helper.assertTrue(movement.isEmpty(), "the destination is accepted: " + movement);
            var path = horse.getNavigation().getPath();
            helper.assertTrue(path != null && path.getTarget().equals(goal), "the native puller received the goal");

            var item = new ItemEntity(level, cart.getX(), cart.getY(), cart.getZ(), new ItemStack(Items.APPLE, 3));
            level.addFreshEntity(item);
            var deposit = facilities.run(name, "deposit", Map.of("target", new Value.Text(item.getUUID().toString())));
            helper.assertTrue(deposit.isEmpty(), "the dropped stack is accepted: " + deposit);
            helper.assertTrue(item.isRemoved(), "the transferred drop is gone");
            int apples = cart.getItemStacks().stream().filter(stack -> stack.is(Items.APPLE))
                .mapToInt(ItemStack::getCount).sum();
            helper.assertTrue(apples == 3, "the cart holds the whole stack");
            cart.discard();
            horse.discard();
            colony.raze();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 160)
    public static void residentSwitchesPlowingWhilePullingAcrossGrass(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos goal = start.east(5);
        var field = BlockPos.betweenClosedStream(start.offset(-2, -1, -2), goal.offset(1, -1, 2))
            .map(BlockPos::immutable).toList();
        field.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.GRASS_BLOCK.defaultBlockState()));

        var colony = Colonies.mint(level.getServer());
        var plow = spawnPlow(helper, start.west(2));
        colony.hold(CartsPlugin.OWNER, Claimed.Entity.of(plow)).orElseThrow();
        var horse = EntityType.HORSE.create(level);
        horse.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5, -90, 0);
        horse.setTamed(true);
        level.addFreshEntity(horse);
        plow.setPulling(horse);
        plow.setItem(1, new ItemStack(Items.IRON_HOE));
        var resident = PersonBody.RESIDENT.get().create(level);
        resident.setPos(start.west().south().getBottomCenter());
        resident.setNoAi(true);
        resident.joinColony(colony.id());
        level.addFreshEntity(resident);
        var facilities = DefinedFacilities.of(colony).orElseThrow();
        String name = "cart_" + plow.getUUID();

        Vec3[] switchedOffAt = {Vec3.ZERO};
        helper.startSequence()
            .thenIdle(5)
            .thenExecute(() -> {
                var signal = (Signal) facilities.facility(plow.getUUID()).orElseThrow().resolve(level).get("plowing");
                helper.assertTrue(signal.part().equals("plowing/" + plow.getUUID()), "the plowing part carries the UUID");
                helper.assertTrue(signal.write(level, resident, new Value.Bool(true)) == Signal.Write.WRITTEN,
                    "the resident enabled plowing");
                helper.assertTrue(signal.feedback().orElseThrow().read(level).orElseThrow().equals(new Value.Bool(true)),
                    "feedback shows the native state is on");
                horse.setOnGround(true);
                helper.assertTrue(facilities.run(name, "move_to", Map.of("target", target(goal))).isEmpty(),
                    "the destination was accepted");
            })
            .thenWaitUntil(() -> helper.assertTrue(
                field.stream().anyMatch(pos -> level.getBlockState(pos).is(Blocks.FARMLAND)),
                "a pulled, enabled plow tills grass"))
            .thenExecute(() -> {
                helper.assertTrue(plow.getItem(1).getDamageValue() > 0, "the native hoe takes damage");
                var signal = (Signal) facilities.facility(plow.getUUID()).orElseThrow().resolve(level).get("plowing");
                helper.assertTrue(signal.write(level, resident, new Value.Bool(false)) == Signal.Write.WRITTEN,
                    "the resident disabled plowing");
                helper.assertTrue(signal.feedback().orElseThrow().read(level).orElseThrow().equals(new Value.Bool(false)),
                    "feedback shows the native state is off");
                field.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.GRASS_BLOCK.defaultBlockState()));
                switchedOffAt[0] = plow.position();
            })
            .thenWaitUntil(() -> helper.assertTrue(
                plow.position().subtract(switchedOffAt[0]).horizontalDistanceSqr() > 1,
                "the plow kept moving after plowing was disabled"))
            .thenExecute(() -> {
                helper.assertTrue(field.stream().noneMatch(pos -> level.getBlockState(pos).is(Blocks.FARMLAND)),
                    "an inactive plow leaves grass intact");
                plow.discard();
                horse.discard();
                resident.discard();
                colony.raze();
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void plowToolPortTransfersNativeSlots(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var plow = spawnPlow(helper, helper.absolutePos(new BlockPos(2, 1, 2)));
        colony.hold(CartsPlugin.OWNER, Claimed.Entity.of(plow)).orElseThrow();
        var resident = PersonBody.RESIDENT.get().create(level);
        resident.setPos(plow.position().add(1, 0, 0));
        resident.setNoAi(true);
        resident.joinColony(colony.id());
        level.addFreshEntity(resident);

        helper.runAfterDelay(5, () -> {
            var facility = DefinedFacilities.of(colony).orElseThrow().facility(plow.getUUID()).orElseThrow();
            var control = facility.resolve(level).get("tools");
            helper.assertTrue(control instanceof Port<?> port && port.resource() == ResourceKind.ITEM,
                "the plow exposes an item port");
            var tools = Ports.at(level, control.anchor(), ResourceKind.ITEM, control.part()).orElseThrow();
            helper.assertTrue(tools.part().equals("tools/" + plow.getUUID()), "the tools part carries the UUID");
            var hoe = new ItemStack(Items.IRON_HOE);
            helper.assertTrue(tools.deposit(level, resident, hoe, true).is(Items.IRON_HOE) && plow.getItem(0).isEmpty(),
                "simulating a deposit leaves the slot alone");
            helper.assertTrue(tools.deposit(level, resident, hoe, false).is(Items.IRON_HOE),
                "the item port accepted the hoe");
            helper.assertTrue(plow.getItem(0).is(Items.IRON_HOE) && plow.getStackInSlot(0).is(Items.IRON_HOE),
                "the hoe landed in the native tool slot");
            helper.assertTrue(tools.draw(level, resident, hoe, false).is(Items.IRON_HOE)
                    && plow.getItem(0).isEmpty() && plow.getStackInSlot(0).isEmpty(),
                "drawing the hoe empties the native tool slot");
            plow.discard();
            resident.discard();
            colony.raze();
            helper.succeed();
        });
    }

    private static PlowEntity spawnPlow(GameTestHelper helper, BlockPos at) {
        var level = helper.getLevel();
        var plow = (PlowEntity) BuiltInRegistries.ENTITY_TYPE
            .get(ResourceLocation.parse("astikorcartsredux:plow")).create(level);
        plow.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, -90, 0);
        level.addFreshEntity(plow);
        return plow;
    }

    private static Value.Vector target(BlockPos goal) {
        return new Value.Vector(List.of((double) goal.getX(), (double) goal.getY(), (double) goal.getZ()));
    }
}
