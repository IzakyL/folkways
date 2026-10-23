package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.base.persist.Reader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LivingEffectsGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(LivingEffectsGameTests.class); }

    @GameTest(template = "empty")
    public static void moodSmoothsExperiencesAndLeisureSurvivesReload(GameTestHelper helper) {
        Mood mood = new Mood();
        mood.step(0, 20, 1200, -12, false, false);
        helper.assertValueEqual(mood.reasons.get("housing"), -12, "a new resident without a bed has no phantom housing experience");
        mood = new Mood();
        double previous = Mood.speed(0);
        for (int value = 1; value <= 100; value++) {
            helper.assertTrue(Mood.speed(value) >= previous, "happier is never slower"); previous = Mood.speed(value);
        }
        helper.assertTrue(Math.abs(Mood.speed(60) - 1) < 1e-9 && Math.abs(Mood.speed(100) - 1.15) < 1e-9, "neutral and maximum speed");
        mood.step(100, 20, 1200, 10, true, false);
        mood.step(110, 20, 1200, 10, true, false);
        helper.assertTrue(mood.value > 60 && mood.value <= 60.04 + 1e-9, "small gradual change");
        mood.enjoyed("petting", 400, 400, 20, 110);
        Mood saved = new Mood(); saved.load(Reader.of(mood.save()));
        helper.assertTrue(Math.abs(saved.leisure - mood.leisure) < 1e-4, "entertainment survives reload");
        saved.repetitions.put("petting", 2); double before = saved.leisure;
        saved.enjoyed("petting", 100, 400, 20, 120);
        helper.assertTrue(Math.abs(saved.leisure - before - 1.5) < 1e-6, "repeated activity has diminishing returns");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void moodEventsAreGainsAndFallsNotExpiries(GameTestHelper helper) {
        Mood mood = new Mood();
        mood.chatted = 10L * Mood.DAY; // company throughout, so loneliness does not arrive mid-test
        helper.assertValueEqual(mood.step(0, 20, 1200, 0, false, false), 0, "the first step has nothing to compare");
        helper.assertValueEqual(mood.step(100, 20, 1200, 0, false, false), 0, "nothing happened");
        mood.dined = 200;
        helper.assertTrue(mood.step(200, 20, 1200, 0, false, false) > 0, "a table meal is a good event");
        helper.assertValueEqual(mood.step(200 + Mood.DAY, 20, 1200, 0, false, false), 0, "the meal wearing off is no event");
        mood.attacked = 300 + Mood.DAY;
        helper.assertTrue(mood.step(300 + Mood.DAY, 20, 1200, 0, false, false) < 0, "an attack is a bad event");
        helper.assertValueEqual(mood.step(300 + Mood.DAY + 2000, 20, 1200, 0, false, false), 0,
            "the fright fading is no event");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bedroomIsInspectedAndOpeningItsRoofInvalidatesIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        for (int x = 0; x <= 5; x++) {
            for (int z = 0; z <= 5; z++) {
                level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(origin.offset(x, 3, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 3; y++) {
                    level.setBlockAndUpdate(origin.offset(x, y, z), (x == 0 || x == 5 || z == 0 || z == 5)
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
        BlockPos bed = origin.offset(1, 0, 1);
        level.setBlockAndUpdate(bed, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT));
        level.setBlockAndUpdate(bed.south(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD));
        BlockPos door = origin.offset(2, 0, 0);
        level.setBlockAndUpdate(door.north().below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(door, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        level.setBlockAndUpdate(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        level.setBlockAndUpdate(origin.offset(3, 0, 3), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(2, 0, 3), Blocks.RED_CARPET.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(4, 0, 3), Blocks.POTTED_DANDELION.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(2, 2, 2), Blocks.GLOWSTONE.defaultBlockState());
        HousingRooms rooms = new HousingRooms();
        rooms.get(level, bed);
        helper.runAfterDelay(4, () -> {
            for (int i = 0; i < 40; i++) { rooms.tick(); }
            HousingRooms.Room room = rooms.get(level, bed);
            helper.assertTrue(room.grade() == HousingRooms.Grade.PRIVATE, "single furnished enclosed bedroom: " + room);
            // Opening the door does not merge this room with the outside.
            level.setBlockAndUpdate(door, level.getBlockState(door).setValue(DoorBlock.OPEN, true));
            rooms.changed(level, door); rooms.get(level, bed);
            for (int i = 0; i < 40; i++) { rooms.tick(); }
            helper.assertTrue(rooms.get(level, bed).grade() == HousingRooms.Grade.PRIVATE, "open doorway remains a boundary");
            BlockPos farEnd = origin.offset(4, 0, 2);
            helper.assertTrue(rooms.comfortableAt(level, farEnd), "room comfort is cached");
            rooms.changed(level, bed.west(34));
            helper.assertFalse(rooms.comfortableAt(level, farEnd), "invalidating a room removes all its cells, including the far end");
            rooms.get(level, bed);
            for (int i = 0; i < 40; i++) { rooms.tick(); }
            BlockPos roof = origin.offset(1, 3, 1);
            level.setBlockAndUpdate(roof, Blocks.AIR.defaultBlockState());
            rooms.changed(level, roof); rooms.get(level, bed);
            for (int i = 0; i < 40; i++) { rooms.tick(); }
            helper.assertTrue(rooms.get(level, bed).grade() == HousingRooms.Grade.EXPOSED, "roof gap removes bedroom benefit");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void housingCapacityAndProductionMixAreExplicit(GameTestHelper helper) {
        helper.assertTrue(HousingRooms.grade(16, 4, 0, true, true, false) == HousingRooms.Grade.DORMITORY, "four per bed");
        helper.assertTrue(HousingRooms.grade(24, 4, 2, true, true, false) == HousingRooms.Grade.COMFORTABLE, "six per bed");
        helper.assertTrue(HousingRooms.grade(18, 2, 3, true, true, false) == HousingRooms.Grade.PRIVATE, "nine per bed");
        helper.assertTrue(HousingRooms.grade(18, 2, 3, true, true, true) != HousingRooms.Grade.PRIVATE, "production prevents highest grade");
        helper.assertTrue(HousingRooms.grade(12, 4, 3, true, true, false) == HousingRooms.Grade.EXPOSED, "too crowded");
        helper.succeed();
    }
}
