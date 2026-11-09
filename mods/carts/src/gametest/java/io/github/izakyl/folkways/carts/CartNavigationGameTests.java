package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.resident.api.Locomotions;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Trip;
import io.github.izakyl.folkways.core.travel.internal.graph.Roamer;
import io.github.izakyl.folkways.core.travel.internal.graph.Roaming;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCartsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_carts")
@PrefixGameTestTemplate(false)
public final class CartNavigationGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CartNavigationGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "cart_navigation", timeoutTicks = 500)
    public static void doorSeparatesSameKindAnswersAndUnhitchingRestoresWalking(GameTestHelper helper) {
        Route rig = new Route(helper, 1, false, "hand_cart");
        var bare = rig.resident(rig.start.south());
        BlockPos goal = rig.start.east(10);
        var goals = Set.of(WorldPos.of(helper.getLevel(), goal));
        Roaming graph = new Roaming(helper.getLevel());
        List<Roamer> both = List.of(rig.roamer(), new Roamer(bare.routeKind(), bare.locomotion(), bare));
        helper.assertTrue(graph.answered(ways -> ways.journey(rig.body.resident(),
                WorldPos.of(helper.getLevel(), rig.start), goals), both, List.of(), 0) instanceof Faring.No,
            "carting refuses the one-wide door");
        helper.assertTrue(graph.answered(ways -> ways.journey(bare.resident(),
                WorldPos.of(helper.getLevel(), rig.start), goals), both, List.of(), 1) instanceof Faring.Yes,
            "a bare resident of the same kind has an independent accepted answer");
        var path = bare.getNavigation().createPath(goal, 0);
        helper.assertTrue(path != null && path.canReach(), "bare local navigation accepts the door");
        bare.getNavigation().moveTo(path, 1);
        helper.runAfterDelay(150, () -> {
            helper.assertTrue(bare.getX() > rig.start.getX() + 8, "the bare resident really walked through the door");
            bare.discard();
            var old = Trip.afoot(rig.body.locomotion(), goals, 1, () -> "folkways.test.lost").orElseThrow();
            old.setOut(helper.getLevel(), rig.body, goals);
            rig.colony.claimOf(rig.cart.getUUID()).ifPresent(held -> rig.colony.release(held.id(), ClaimRelease.LET_GO));
            helper.assertTrue(rig.cart.getPulling() == null && rig.body.locomotion().id().equals(Locomotions.WALKING),
                "withdrawing detaches immediately and fits ordinary walking");
            helper.assertTrue(old.step(helper.getLevel(), rig.body, goals) instanceof Going.Failed,
                "switching ends the old trip cleanly");
            graph.advance(List.of(rig.roamer()), List.of(), 2);
            helper.assertTrue(graph.answered(ways -> ways.journey(rig.body.resident(),
                    WorldPos.of(helper.getLevel(), rig.start), goals), List.of(rig.roamer()), List.of(), 3)
                    instanceof Faring.Yes, "the carting graph and its requests can disappear");
            var unhitched = rig.body.getNavigation().createPath(goal, 0);
            helper.assertTrue(unhitched != null && unhitched.canReach(), "the former puller can now use the door");
            rig.body.getNavigation().moveTo(unhitched, 1);
        });
        helper.runAfterDelay(320, () -> {
            helper.assertTrue(rig.body.getX() > rig.start.getX() + 8, "the former puller really walks through");
            rig.close();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "cart_gateway", timeoutTicks = 500)
    public static void widerGatewayKeepsHandCartHitchedUnderRealNavigation(GameTestHelper helper) {
        follow(helper, false, "hand_cart");
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "cart_corner", timeoutTicks = 500)
    public static void cornerKeepsHandCartHitchedUnderRealNavigation(GameTestHelper helper) {
        follow(helper, true, "hand_cart");
    }

    private static void follow(GameTestHelper helper, boolean corner, String kind) {
        Route rig = new Route(helper, 3, corner, kind);
        BlockPos goal = corner ? rig.start.offset(10, 0, 6) : rig.start.east(10);
        var goals = Set.of(WorldPos.of(helper.getLevel(), goal));
        Roaming graph = new Roaming(helper.getLevel());
        helper.assertTrue(graph.answered(ways -> ways.journey(rig.body.resident(),
                WorldPos.of(helper.getLevel(), rig.start), goals), List.of(rig.roamer()), List.of(), 0)
                instanceof Faring.Yes, "the carting graph accepts this route");
        var path = rig.body.getNavigation().createPath(goal, 0);
        helper.assertTrue(path != null && path.canReach(), "carting local navigation accepts this route: " + path + " end="
                + (path == null ? "null" : path.getEndNode()) + " body=" + rig.body.getBoundingBox()
                + " cart=" + rig.cart.getBoundingBox() + " ground=" + rig.body.onGround());
        rig.body.getNavigation().moveTo(path, 1);
        helper.onEachTick(() -> {
            helper.assertTrue(rig.cart.getPulling() == rig.body,
                "cart stays hitched throughout the accepted route at " + rig.body.position());
            helper.assertFalse(rig.cart.shouldRemovePulling(), "native collision removal never fires");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(rig.body.blockPosition().distSqr(goal) < 2, "real AI walking reaches the goal");
            helper.assertTrue(rig.body.walkDist > 4, "the puller travelled by walking");
            rig.close();
        });
    }

    private static final class Route implements AutoCloseable {
        private final GameTestHelper helper;
        private final BlockPos start;
        private final Colony colony;
        private final ResidentEntity body;
        private final AbstractCargoCart cart;
        private AbstractHorse horse;

        private Route(GameTestHelper helper, int gateway, boolean corner, String kind) {
            this.helper = helper;
            // Separate from other templates: these courses deliberately exceed the empty template's footprint.
            start = helper.absolutePos(new BlockPos(2, 1, 2)).above(40);
            for (BlockPos at : BlockPos.betweenClosed(start.offset(-4, -1, -4), start.offset(14, 3, 10))) {
                helper.getLevel().setBlockAndUpdate(at, at.getY() == start.getY() - 1
                    ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            colony = Colonies.mint(helper.getLevel().getServer());
            for (BlockPos at : BlockPos.betweenClosed(start.offset(5, 0, -4), start.offset(5, 3, 10))) {
                boolean opening = Math.abs(at.getZ() - start.getZ()) <= gateway / 2;
                helper.getLevel().setBlockAndUpdate(at, opening ? Blocks.AIR.defaultBlockState()
                    : Blocks.STONE.defaultBlockState());
            }
            // Prevent walking around the partition's ends.
            for (BlockPos at : BlockPos.betweenClosed(start.offset(-4, 0, -4), start.offset(14, 3, 10))) {
                if (at.getZ() == start.getZ() - 4 || at.getZ() == start.getZ() + 10
                        || at.getX() == start.getX() - 4 || at.getX() == start.getX() + 14) {
                    helper.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
                }
            }
            if (gateway == 1) {
                var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
                helper.getLevel().setBlockAndUpdate(start.east(5), door);
                helper.getLevel().setBlockAndUpdate(start.east(5).above(),
                    door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            }
            if (corner) {
                // A solid inside corner forces a turn after the gateway instead of a straight diagonal.
                for (BlockPos at : BlockPos.betweenClosed(start.offset(6, 0, 2), start.offset(8, 3, 10))) {
                    helper.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
                }
            }
            body = resident(start);
            body.setYRot(-90);
            cart = (AbstractCargoCart) BuiltInRegistries.ENTITY_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("astikorcartsredux", kind)).create(helper.getLevel());
            cart.moveTo(start.west(2).getBottomCenter());
            cart.setYRot(-90);
            helper.getLevel().addFreshEntity(cart);
            colony.hold(FolkwaysCartsMod.OWNER, Claimed.Entity.of(cart)).orElseThrow();
            if (kind.equals("supply_cart")) {
                horse = net.minecraft.world.entity.EntityType.HORSE.create(helper.getLevel());
                horse.moveTo(start.west(2).getBottomCenter());
                horse.setTamed(true);
                helper.getLevel().addFreshEntity(horse);
                cart.moveTo(start.west(4).getBottomCenter());
                cart.setPulling(horse);
            } else {
                cart.setPulling(body);
            }
            helper.assertTrue(body.locomotion() == Carting.INSTANCE, "hitch fits the carting locomotion");
        }

        private ResidentEntity resident(BlockPos at) {
            var resident = PersonBody.RESIDENT.get().create(helper.getLevel());
            resident.moveTo(at.getBottomCenter());
            resident.setOnGround(true);
            helper.getLevel().addFreshEntity(resident);
            colony.hold(PersonContent.ID, Claimed.Entity.of(resident)).orElseThrow();
            return resident;
        }

        private Roamer roamer() {
            return new Roamer(body.routeKind(), body.locomotion(), body);
        }

        @Override
        public void close() {
            cart.setPulling(null);
            cart.discard();
            if (horse != null) {
                horse.discard();
            }
            body.discard();
            colony.raze();
            for (BlockPos at : BlockPos.betweenClosed(start.offset(-4, -1, -4), start.offset(14, 3, 10))) {
                helper.getLevel().setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            }
        }
    }
}
