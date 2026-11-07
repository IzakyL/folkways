package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
        helper.assertTrue(mood.events.isEmpty(), "initial reasons and unchanged steps record no events");
        mood.dined = 200;
        helper.assertTrue(mood.step(200, 20, 1200, 0, false, false) > 0, "a table meal is a good event");
        helper.assertValueEqual(mood.events.getFirst(), new Mood.Event("dining", 6, 200), "meal reason, amount and time");
        helper.assertValueEqual(mood.step(199 + Mood.DAY, 20, 1200, 0, false, false), 0, "a lasting bonus is no new event");
        helper.assertValueEqual(mood.reasons.get("dining"), 6, "dining contributes six mood points for a day");
        helper.assertValueEqual(mood.step(200 + Mood.DAY, 20, 1200, 0, false, false), 0, "the meal wearing off is no event");
        helper.assertValueEqual(mood.reasons.get("dining"), 0, "the dining bonus expires at one day");
        helper.assertValueEqual(mood.events.size(), 1, "bonus expiry does not enter history");
        mood.attacked(300 + Mood.DAY);
        helper.assertTrue(mood.step(300 + Mood.DAY, 20, 1200, 0, false, false) < 0, "an attack is a bad event");
        helper.assertValueEqual(mood.events.getFirst(), new Mood.Event("attacked", -12, 300 + Mood.DAY), "attack is newest");
        helper.assertValueEqual(mood.events.size(), 2, "attack has no duplicate safety entry");
        helper.assertValueEqual(mood.step(300 + Mood.DAY + 2000, 20, 1200, 0, false, false), 0,
            "the fright fading is no event");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void moodHistoryIsBoundedAndSurvivesReload(GameTestHelper helper) {
        Mood mood = new Mood();
        mood.chatted = 10L * Mood.DAY;
        mood.step(0, 20, 1200, 0, false, false);
        mood.step(10, 4, 1200, 0, false, false);
        helper.assertValueEqual(mood.events.getFirst(), new Mood.Event("food", -15, 10), "hunger records a penalty");
        mood.step(20, 20, 1200, 0, false, false);
        helper.assertValueEqual(mood.events.size(), 1, "healing is no event");
        mood.step(30, 20, 1200, 6, false, false);
        helper.assertValueEqual(mood.events.getFirst(), new Mood.Event("housing", 6, 30), "better housing records a bonus");
        for (int hit = 1; hit <= 10; hit++) { mood.attacked(hit * 1000L); }
        helper.assertValueEqual(mood.events.size(), 8, "only eight newest events");
        helper.assertValueEqual(mood.events.getFirst().at(), 10000L, "newest first");
        helper.assertValueEqual(mood.events.getLast().at(), 3000L, "oldest retained event");
        mood.attacked(10010);
        helper.assertValueEqual(mood.events.getFirst().at(), 10000L, "the same hit is not recorded twice");
        Mood restored = new Mood(); restored.load(Reader.of(mood.save()));
        helper.assertValueEqual(restored.events, mood.events, "history round trip retains reason, signed amount, time and order");
        restored.step(10020, 20, 1200, 6, false, false);
        helper.assertValueEqual(restored.events, mood.events, "first step after reload adds no phantom changes");
        Mood fresh = new Mood();
        var emptyHistory = fresh.save();
        helper.assertTrue(emptyHistory.contains("events", net.minecraft.nbt.Tag.TAG_LIST),
            "the current writer includes even an empty history");
        restored.load(Reader.of(emptyHistory));
        helper.assertTrue(restored.events.isEmpty(), "a fresh mood round trip clears previous history");
        var missingHistory = fresh.save();
        missingHistory.remove("events");
        boolean rejected = false;
        try {
            new Mood().load(Reader.of(missingHistory));
        } catch (IllegalArgumentException invalid) {
            rejected = true;
        }
        helper.assertTrue(rejected, "a present mood record must contain the history written by the current writer");
        restored.step(10030, 20, 1200, 6, false, false);
        helper.assertValueEqual(restored.reasons.get("dining"), 0, "a fresh mood has no invented dining experience");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bedroomIsInspectedAndOpeningItsRoofInvalidatesIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        shell(level, origin, 5, Blocks.STONE);
        BlockPos bed = bed(level, origin.offset(1, 0, 1));
        BlockPos door = door(level, origin.offset(2, 0, 0));
        level.setBlockAndUpdate(origin.offset(3, 0, 3), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(2, 0, 3), Blocks.RED_CARPET.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(4, 0, 3), Blocks.POTTED_DANDELION.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(2, 2, 2), Blocks.GLOWSTONE.defaultBlockState());
        HousingRooms rooms = new HousingRooms();
        rooms.get(level, bed);
        helper.runAfterDelay(4, () -> {
            settle(rooms);
            HousingRooms.Room room = rooms.get(level, bed);
            helper.assertTrue(room.grade() == HousingRooms.Grade.PRIVATE, "single furnished enclosed bedroom: " + room);
            helper.assertValueEqual(room.area(), 16, "the chest still counts toward bedroom area");
            helper.assertTrue(rooms.comfortableAt(level, origin.offset(3, 0, 3)), "the chest receives the interior grade");
            // Opening the door does not merge this room with the outside.
            level.setBlockAndUpdate(door, level.getBlockState(door).setValue(DoorBlock.OPEN, true));
            helper.assertTrue(resurvey(rooms, level, door, bed).grade() == HousingRooms.Grade.PRIVATE,
                "open doorway remains a boundary");
            BlockPos farEnd = origin.offset(4, 0, 2);
            helper.assertTrue(rooms.comfortableAt(level, farEnd), "room comfort is cached");
            rooms.changed(level, bed.west(34));
            helper.assertFalse(rooms.comfortableAt(level, farEnd), "invalidating a room removes all its cells, including the far end");
            rooms.get(level, bed);
            settle(rooms);
            BlockPos roof = origin.offset(1, 3, 1);
            level.setBlockAndUpdate(roof, Blocks.AIR.defaultBlockState());
            helper.assertTrue(resurvey(rooms, level, roof, bed).grade() == HousingRooms.Grade.EXPOSED,
                "roof gap removes bedroom benefit");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void stackedBarrelsSeparateBedroomsAndCountAsArea(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        shell(level, origin, 10, Blocks.GLOWSTONE, 5);
        BlockPos bed = origin.offset(1, 0, 1);
        for (int x : new int[] {1, 6}) {
            bed(level, origin.offset(x, 0, 1));
            door(level, origin.offset(x + 1, 0, 0));
            level.setBlockAndUpdate(origin.offset(x + 2, 0, 4), Blocks.RED_CARPET.defaultBlockState());
        }
        BlockPos otherBed = origin.offset(6, 0, 1);
        BlockPos barrel = origin.offset(5, 0, 2);
        level.setBlockAndUpdate(barrel, Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(barrel.above(), Blocks.BARREL.defaultBlockState());
        HousingRooms rooms = new HousingRooms();
        rooms.get(level, bed);
        helper.runAfterDelay(4, () -> {
            settle(rooms);
            HousingRooms.Room room = rooms.get(level, bed);
            helper.assertValueEqual(room.beds(), 1, "barrel partition keeps the neighbouring bed separate");
            helper.assertTrue(room.grade() != HousingRooms.Grade.EXPOSED, "enclosed lit bedroom: " + room);
            helper.assertValueEqual(room.area(), 4 * 4 + 1, "sixteen interior cells plus the barrel standing on the floor");
            helper.assertTrue(room.grade() == HousingRooms.Grade.COMFORTABLE, "carpet and barrel furnish the room: " + room);
            helper.assertTrue(survey(rooms, level, otherBed).grade() == HousingRooms.Grade.COMFORTABLE,
                "the other room is surveyed");
            // Dropping one room leaves the shared barrel to the other.
            rooms.retain(level, Set.of(otherBed));
            helper.assertTrue(rooms.comfortableAt(level, barrel), "both rooms share the partition barrel");
            rooms.retain(level, Set.of());
            helper.assertFalse(rooms.comfortableAt(level, barrel), "the barrel leaves with the last room");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void lightingCountsAboveTheFloorAndLanternsAreWalkable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 22, 2));
        BlockPos bed = bedroom(level, origin);
        BlockPos torch = origin.offset(1, 1, 3);
        BlockPos lantern = origin.offset(3, 0, 2);
        for (BlockPos at : BlockPos.betweenClosed(origin.above(3), origin.offset(5, 3, 5))) {
            level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(torch, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST));
        HousingRooms rooms = new HousingRooms();
        rooms.get(level, bed);
        helper.runAfterDelay(4, () -> {
            settle(rooms);
            helper.assertValueEqual(rooms.get(level, bed).furnishings(), 2, "storage and a wall torch above feet level");
            level.setBlockAndUpdate(lantern, Blocks.LANTERN.defaultBlockState());
            HousingRooms.Room lit = resurvey(rooms, level, lantern, bed);
            helper.assertValueEqual(lit.area(), 16, "a floor lantern remains walkable room area");
            helper.assertValueEqual(lit.furnishings(), 2, "more lighting is still one category");
            level.setBlockAndUpdate(torch, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(lantern, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(lantern.above(2), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            helper.assertValueEqual(resurvey(rooms, level, lantern, bed).furnishings(), 2, "a ceiling lantern supplies lighting");
            level.setBlockAndUpdate(lantern.above(2), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(lantern.above(3), Blocks.GLOWSTONE.defaultBlockState());
            helper.assertValueEqual(resurvey(rooms, level, lantern, bed).furnishings(), 2, "a glowstone ceiling supplies lighting");
            level.setBlockAndUpdate(lantern.above(3), Blocks.STONE.defaultBlockState());
            helper.assertValueEqual(resurvey(rooms, level, lantern, bed).furnishings(), 1,
                "a stone ceiling without fixtures gives no lighting");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void outsideWallSignNamesOccupantsAndClearsAfterReload(GameTestHelper helper) {
        try (Nameplates fixture = new Nameplates(helper)) {
            fixture.add("Ada");
            fixture.inspect();
            helper.assertValueEqual(fixture.sign().getText(true).getMessage(0, false).getString(), "Ada",
                "an empty sign on the outside face of the bedroom wall names its resident");
            helper.assertValueEqual(fixture.unrelated().getText(true).getMessage(0, false).getString(), "",
                "a sign attached to another wall is left empty");
            helper.assertValueEqual(fixture.saved().children("nameplates").size(), 1, "the chosen sign is owned");
            fixture.living = new LivingPresence(fixture.colony);
            fixture.add("Bea");
            fixture.inspect();
            helper.assertValueEqual(fixture.sign().getText(true).getMessage(1, false).getString(), "Bea",
                "saved ownership allows rewriting when another resident moves in");
            fixture.leave();
            fixture.living.refresh(fixture.level.getServer());
            helper.assertTrue(blank(fixture.sign()), "the last occupant leaving clears both sides of our sign");
            helper.assertTrue(fixture.saved().children("nameplates").isEmpty(), "the empty sign is no longer owned");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void roomSignsIncludeHangingSignsAndTheDoorLintel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 22, 2));
        BlockPos bed = bedroom(level, origin);
        BlockPos standing = origin.offset(3, 0, 2);
        BlockPos hanging = origin.offset(3, 2, 3);
        BlockPos lintel = origin.offset(2, 2, -1);
        BlockPos crossbar = origin.offset(-1, 1, 3);
        BlockPos aboveRoof = origin.offset(3, 4, 3);
        level.setBlockAndUpdate(standing, Blocks.OAK_SIGN.defaultBlockState());
        level.setBlockAndUpdate(hanging, Blocks.OAK_HANGING_SIGN.defaultBlockState());
        level.setBlockAndUpdate(lintel, Blocks.OAK_WALL_SIGN.defaultBlockState()
            .setValue(WallSignBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(crossbar, Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState()
            .setValue(WallHangingSignBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(aboveRoof, Blocks.OAK_SIGN.defaultBlockState());
        HousingRooms rooms = new HousingRooms();
        survey(rooms, level, bed);
        Set<BlockPos> signs = rooms.signsOf(level).get(rooms.surveyed(level).getFirst());
        helper.assertTrue(signs.containsAll(Set.of(standing, hanging, lintel, crossbar)),
            "standing, ceiling, lintel and crossbar attachments all belong to the room: " + signs);
        helper.assertFalse(signs.contains(aboveRoof), "a sign above the roof is outside the room");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void insidePartitionSignBelongsToItsBedroom(GameTestHelper helper) {
        try (Nameplates fixture = new Nameplates(helper)) {
            BlockPos otherBed = adjacentBedroom(fixture);
            BlockPos sign = fixture.origin.offset(6, 1, 2);
            fixture.level.setBlockAndUpdate(sign, Blocks.OAK_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, Direction.EAST));
            fixture.add("Ada");
            fixture.colony.hold(LivingContent.BED, new Claimed.Block(WorldPos.of(fixture.level, otherBed))).orElseThrow();
            fixture.add("Bea", otherBed);
            fixture.claim();
            fixture.inspect();
            SignBlockEntity plate = (SignBlockEntity) fixture.level.getBlockEntity(sign);
            helper.assertValueEqual(plate.getText(true).getMessage(0, false).getString(), "Bea",
                "a sign inside the eastern bedroom names its resident, not the western neighbour");
            helper.assertValueEqual(fixture.saved().children("nameplates").size(), 1, "the shared-wall sign has one owner");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void northFacingCorridorSignNamesTheWesternRoom(GameTestHelper helper) {
        corridorNameplate(helper, Direction.NORTH);
    }

    @GameTest(template = "empty")
    public static void southFacingCorridorSignTransfersToTheEasternRoom(GameTestHelper helper) {
        corridorNameplate(helper, Direction.SOUTH);
    }

    private static void corridorNameplate(GameTestHelper helper, Direction facing) {
        try (Nameplates fixture = new Nameplates(helper)) {
            BlockPos otherBed = adjacentBedroom(fixture);
            BlockPos sign = fixture.origin.offset(5, 1, facing == Direction.NORTH ? 0 : 5);
            fixture.level.setBlockAndUpdate(sign, Blocks.OAK_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, facing));
            fixture.add("Ada");
            fixture.inspect();
            SignBlockEntity plate = (SignBlockEntity) fixture.level.getBlockEntity(sign);
            helper.assertValueEqual(plate.getText(true).getMessage(0, false).getString(), "Ada",
                "a sign attached to the only surveyed bedroom belongs to it");
            fixture.colony.hold(LivingContent.BED, new Claimed.Block(WorldPos.of(fixture.level, otherBed))).orElseThrow();
            fixture.add("Bea", otherBed);
            fixture.claim();
            fixture.inspect();
            helper.assertValueEqual(plate.getText(true).getMessage(0, false).getString(),
                facing == Direction.NORTH ? "Ada" : "Bea", "the corridor sign names the room on the viewer's right");
            helper.assertValueEqual(fixture.saved().children("nameplates").size(), 1, "the corridor sign has one owner");
            fixture.living.refresh(fixture.level.getServer());
            helper.assertValueEqual(plate.getText(true).getMessage(0, false).getString(),
                facing == Direction.NORTH ? "Ada" : "Bea", "the rightful room keeps its nameplate on later refreshes");
        }
        helper.succeed();
    }

    private static BlockPos adjacentBedroom(Nameplates fixture) {
        fixture.level.setBlockAndUpdate(fixture.outside, Blocks.AIR.defaultBlockState());
        fixture.level.setBlockAndUpdate(fixture.origin.offset(4, 0, 4), Blocks.AIR.defaultBlockState());
        return bedroom(fixture.level, fixture.origin.east(5));
    }

    @GameTest(template = "empty")
    public static void playerEditedNameplateIsNeverReclaimed(GameTestHelper helper) {
        try (Nameplates fixture = new Nameplates(helper)) {
            fixture.add("Ada");
            fixture.inspect();
            SignBlockEntity sign = fixture.sign();
            sign.setText(sign.getText(false).setMessage(2, Component.literal("Private")), false);
            fixture.leave();
            fixture.living.refresh(fixture.level.getServer());
            helper.assertValueEqual(sign.getText(true).getMessage(0, false).getString(), "Ada",
                "a player edit prevents clearing even when the resident leaves");
            helper.assertValueEqual(sign.getText(false).getMessage(2, false).getString(), "Private",
                "edits on the back are preserved");
            helper.assertTrue(fixture.saved().children("nameplates").isEmpty(), "edited signs lose ownership");
            for (boolean front : List.of(true, false)) {
                for (int line = 0; line < 4; line++) {
                    final int index = line;
                    sign.updateText(text -> text.setMessage(index, Component.empty()), front);
                }
            }
            fixture.living = new LivingPresence(fixture.colony);
            fixture.add("Bea");
            fixture.inspect();
            helper.assertTrue(blank(sign), "a previously edited sign stays untouched after it is emptied and reloaded");
            BlockState placed = fixture.level.getBlockState(fixture.outside);
            fixture.level.setBlockAndUpdate(fixture.outside, Blocks.AIR.defaultBlockState());
            fixture.living.refresh(fixture.level.getServer());
            fixture.level.setBlockAndUpdate(fixture.outside, placed);
            fixture.inspect();
            helper.assertValueEqual(fixture.sign().getText(true).getMessage(0, false).getString(), "Bea",
                "a sign placed again where the edited one stood is a new nameplate");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nameplatesSortNamesAndContinueOnTheBack(GameTestHelper helper) {
        try (Nameplates fixture = new Nameplates(helper)) {
            for (String name : List.of("Eve", "Ada", "Dia", "Bea", "Cam")) { fixture.add(name); }
            fixture.inspect();
            SignBlockEntity sign = fixture.sign();
            helper.assertValueEqual(sign.getText(true).getMessage(0, false).getString(), "Ada", "names sort alphabetically");
            helper.assertValueEqual(sign.getText(true).getMessage(3, false).getString(), "Dia", "four names on the front");
            helper.assertValueEqual(sign.getText(false).getMessage(0, false).getString(), "Eve", "overflow goes on the back");
            fixture.leave();
            fixture.living.refresh(fixture.level.getServer());
            helper.assertTrue(blank(sign), "leaving clears the back as well as the front");
        }
        helper.succeed();
    }

    private static boolean blank(SignBlockEntity sign) {
        for (boolean front : List.of(true, false)) {
            for (int line = 0; line < 4; line++) {
                if (!sign.getText(front).getMessage(line, false).getString().isEmpty()) { return false; }
            }
        }
        return true;
    }

    // A lit, furnished bedroom: walls and floor from origin to origin+(5, 3, 5), a bed, a door and a chest.
    private static BlockPos bedroom(ServerLevel level, BlockPos origin) {
        shell(level, origin, 5, Blocks.GLOWSTONE);
        BlockPos bed = bed(level, origin.offset(1, 0, 1));
        door(level, origin.offset(2, 0, 0));
        level.setBlockAndUpdate(origin.offset(4, 0, 4), Blocks.CHEST.defaultBlockState());
        return bed;
    }

    // A stone floor and walls three high under the given roof, with an inner wall at each partition x.
    private static void shell(ServerLevel level, BlockPos origin, int width, Block roof, int... partitions) {
        for (int x = 0; x <= width; x++) {
            final int column = x;
            boolean wall = x == 0 || x == width || Arrays.stream(partitions).anyMatch(at -> at == column);
            for (int z = 0; z <= 5; z++) {
                level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(origin.offset(x, 3, z), roof.defaultBlockState());
                for (int y = 0; y < 3; y++) {
                    level.setBlockAndUpdate(origin.offset(x, y, z), (wall || z == 0 || z == 5)
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // A bed whose foot is here and whose head lies to the south.
    private static BlockPos bed(ServerLevel level, BlockPos foot) {
        level.setBlockAndUpdate(foot, Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT));
        level.setBlockAndUpdate(foot.south(), Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD));
        return foot;
    }

    // A closed door in the north wall, with a step outside it.
    private static BlockPos door(ServerLevel level, BlockPos door) {
        level.setBlockAndUpdate(door.north().below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(door, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        level.setBlockAndUpdate(door.above(), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        return door;
    }

    private static void settle(HousingRooms rooms) {
        for (int i = 0; i < 40; i++) { rooms.tick(); }
    }

    private static HousingRooms.Room survey(HousingRooms rooms, ServerLevel level, BlockPos bed) {
        rooms.get(level, bed);
        settle(rooms);
        return rooms.get(level, bed);
    }

    // The room is surveyed again after a block in it changes.
    private static HousingRooms.Room resurvey(HousingRooms rooms, ServerLevel level, BlockPos changed, BlockPos bed) {
        rooms.changed(level, changed);
        return survey(rooms, level, bed);
    }

    private static final class Nameplates implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final BlockPos origin;
        final BlockPos bed;
        final BlockPos outside;
        final BlockPos elsewhere;
        final List<ResidentEntity> residents = new ArrayList<>();
        LivingPresence living;

        Nameplates(GameTestHelper helper) {
            level = helper.getLevel();
            origin = helper.absolutePos(new BlockPos(2, 22, 2));
            bed = bedroom(level, origin);
            outside = origin.offset(-1, 1, 2);
            elsewhere = origin.offset(8, 1, 2);
            level.setBlockAndUpdate(outside, Blocks.OAK_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, Direction.WEST));
            level.setBlockAndUpdate(elsewhere.east(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(elsewhere, Blocks.OAK_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, Direction.WEST));
            colony = Colonies.mint(level.getServer());
            ColonyFront.of(colony).setSetting(LivingContent.ID, LivingContent.FOOD.key(),
                new ColonySettings.Value.Items(List.of()));
            colony.hold(LivingContent.BED, new Claimed.Block(WorldPos.of(level, bed))).orElseThrow();
            living = new LivingPresence(colony);
        }

        void add(String name) {
            if (!residents.isEmpty()) {
                int index = residents.size();
                BlockPos foot = bed(level, origin.offset(1 + index % 4, 0, 1 + index / 4 * 2));
                colony.hold(LivingContent.BED, new Claimed.Block(WorldPos.of(level, foot))).orElseThrow();
                living.changed(level, foot);
            }
            add(name, bed);
        }

        void add(String name, BlockPos foot) {
            ResidentEntity person = PersonBody.RESIDENT.get().create(level);
            person.setNoAi(true);
            person.setCustomName(Component.literal(name));
            person.moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5);
            level.addFreshEntity(person);
            colony.hold(PersonContent.ID, Claimed.Entity.of(person)).orElseThrow();
            person.joinColony(colony.id());
            colony.entered(person);
            residents.add(person);
        }

        void inspect() {
            living.refresh(level.getServer());
            for (BlockPos foot : colony.view(level).blocks(LivingContent.BED).stream().sorted().toList()) {
                living.blockLines(foot, colony.view(level));
                for (int i = 0; i < 40; i++) { living.tick(level.getServer()); }
            }
            living.refresh(level.getServer());
        }

        void claim() {
            Beds claimed = new Beds();
            for (ResidentEntity person : residents) {
                claimed.claim(person.getUUID(), WorldPos.of(level, person.blockPosition()));
            }
            colony.keep(LivingContent.ID, Writer.of(new LivingPresence(colony).save().merge(claimed.save()))
                .blob("pets", Writer.of().longValue("next_attempt", -1L).uuids("pets", List.of()).tag()).tag());
            living = new LivingPresence(colony);
        }

        SignBlockEntity sign() { return (SignBlockEntity) level.getBlockEntity(outside); }

        SignBlockEntity unrelated() { return (SignBlockEntity) level.getBlockEntity(elsewhere); }

        Reader saved() { return Reader.of(colony.kept(LivingContent.ID)); }

        void leave() {
            for (ResidentEntity person : residents) {
                colony.release(colony.claimOf(person.getUUID()).orElseThrow().id(), ClaimRelease.LET_GO);
                person.discard();
            }
            residents.clear();
        }

        @Override
        public void close() {
            leave();
            colony.raze();
            for (BlockPos at : BlockPos.betweenClosed(origin.offset(-1, -1, -1), origin.offset(10, 3, 5))) {
                level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            }
        }
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
