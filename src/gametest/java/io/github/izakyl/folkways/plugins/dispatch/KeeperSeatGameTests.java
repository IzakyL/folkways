package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KeeperSeatGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(KeeperSeatGameTests.class);
    }

    // A seat sunk a level into the floor, as beside a stock ticker one block up: whoever gets up from it is set on
    // top of it, a jump short of the floor round it, where no way starts. The keeper steps off onto the floor.
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aKeeperGettingUpFromASunkSeatStepsOntoTheFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos seat = helper.absolutePos(new BlockPos(2, 1, 2));
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                level.setBlockAndUpdate(seat.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(seat.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(seat, BuiltInRegistries.BLOCK.get(
            ResourceLocation.fromNamespaceAndPath("create", "white_seat")).defaultBlockState());
        Body body = PersonBody.RESIDENT.get().create(level);
        body.mob().moveTo(seat.getX() + 0.5D, seat.getY() + 1.0D, seat.getZ() + 0.5D);
        level.addFreshEntity(body.mob());
        helper.assertTrue(new CreatePackageNetwork().sit(level, seat, body.mob()), "the keeper sits");
        WorkGesture.SIT.release(body.mob());
        helper.assertFalse(body.mob().isPassenger(), "the keeper stands up");
        BlockPos feet = body.mob().blockPosition();
        helper.assertTrue(Footing.withFeetAt(level, feet) && feet.getY() == seat.getY() + 1
                && feet.distManhattan(seat.above()) == 1,
            "a keeper up from a sunk seat stands on the floor beside it, not at " + feet.toShortString()
                + " by the seat at " + seat.toShortString());
        body.mob().discard();
        helper.succeed();
    }
}
