package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ColonyPetsGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(ColonyPetsGameTests.class); }

    @GameTest(template = "empty", batch = "colony_pets")
    public static void arrivalsRespectDevelopmentCooldownAndUnloadedQuota(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        helper.runAfterDelay(4, () -> {
            try (fixture) {
                fixture.survey();
                helper.assertTrue(fixture.rooms.get(fixture.level, fixture.beds.getFirst()).grade()
                    == HousingRooms.Grade.DORMITORY, "real enclosed dormitory qualifies: "
                        + fixture.rooms.get(fixture.level, fixture.beds.getFirst()));
                fixture.addResidents(2);
                fixture.attempts(32);
                helper.assertTrue(fixture.animals().isEmpty(), "two residents cannot attract a pet");
                fixture.addResidents(1);
                ColonyPets initial = new ColonyPets(fixture.colony, fixture.rooms);
                initial.tick(fixture.level.getServer());
                long next = initial.save().getLong("next_attempt");
                helper.assertTrue(next > fixture.level.getServer().overworld().getGameTime(), "first tick schedules a future arrival");
                fixture.pets.load(Reader.of(initial.save()));
                fixture.pets.tick(fixture.level.getServer());
                helper.assertTrue(fixture.animals().isEmpty(), "saved cooldown prevents an immediate arrival after reload");
                fixture.attempts(32);
                List<Entity> arrived = fixture.animals();
                helper.assertValueEqual(arrived.size(), 1, "three housed residents support one pet");
                Entity cat = arrived.getFirst();
                helper.assertTrue(cat.getType() == EntityType.CAT, "early colony unlocks cats");
                helper.assertTrue(ColonyPets.belongsTo(cat, fixture.colony), "arrival belongs to its colony");
                helper.assertFalse(ColonyPets.belongsTo(fixture.residents.getFirst(), fixture.colony), "ordinary residents are not pets");
                fixture.pets.tick(fixture.level.getServer());
                helper.assertValueEqual(fixture.animals().size(), 1, "an ordinary tick cannot bypass cooldown");
                CompoundTag saved = fixture.pets.save();
                cat.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                fixture.pets = new ColonyPets(fixture.colony, fixture.rooms);
                fixture.pets.load(Reader.of(saved));
                fixture.attempts(32);
                helper.assertTrue(fixture.animals().isEmpty(), "unloaded pet still occupies persisted quota");
                helper.assertTrue(fixture.pets.removed(cat.getUUID()), "permanent removal releases tracked quota");
                helper.assertFalse(fixture.pets.removed(cat.getUUID()), "quota is released only once");
                fixture.attempts(32);
                helper.assertValueEqual(fixture.animals().size(), 1, "released quota permits a replacement");
                fixture.removePets();
                fixture.addResidents(3);
                Set<EntityType<?>> middle = fixture.sampleSpecies(40);
                helper.assertTrue(middle.contains(EntityType.WOLF), "six housed residents unlock wolves");
                helper.assertFalse(middle.contains(EntityType.PARROT), "parrots remain locked before ten residents");
                fixture.addResidents(4);
                helper.assertTrue(fixture.sampleSpecies(60).contains(EntityType.PARROT), "ten housed residents unlock parrots");
                fixture.addResidents(17);
                fixture.attempts(128);
                helper.assertValueEqual(fixture.animals().size(), 9, "27 housed residents support nine pets, beyond the former fixed cap");
                fixture.attempts(32);
                helper.assertValueEqual(fixture.animals().size(), 9, "population-based quota still limits arrivals");
                fixture.removePets();
                fixture.level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, fixture.level.getServer());
                fixture.attempts(32);
                helper.assertTrue(fixture.animals().isEmpty(), "doMobSpawning disables arrivals");
                fixture.level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, fixture.level.getServer());
                for (BlockPos floor : BlockPos.betweenClosed(fixture.origin.offset(-17, -1, -17), fixture.origin.offset(26, -1, 26))) {
                    fixture.level.setBlockAndUpdate(floor, Blocks.MAGMA_BLOCK.defaultBlockState());
                }
                for (BlockPos roof : BlockPos.betweenClosed(fixture.origin.above(3), fixture.origin.offset(13, 3, 13))) {
                    fixture.level.setBlockAndUpdate(roof, Blocks.MAGMA_BLOCK.defaultBlockState());
                }
                fixture.attempts(32);
                helper.assertTrue(fixture.animals().isEmpty(), "hazardous ground and roof leave no safe arrival space");
                for (BlockPos floor : BlockPos.betweenClosed(fixture.origin.offset(-17, -1, -17), fixture.origin.offset(26, -1, 26))) {
                    fixture.level.setBlockAndUpdate(floor, Blocks.GLOWSTONE.defaultBlockState());
                }
                for (BlockPos roof : BlockPos.betweenClosed(fixture.origin.above(3), fixture.origin.offset(13, 3, 13))) {
                    fixture.level.setBlockAndUpdate(roof, Blocks.AIR.defaultBlockState());
                }
                fixture.rooms.changed(fixture.level, fixture.beds.getFirst());
                fixture.survey();
                helper.assertTrue(fixture.rooms.get(fixture.level, fixture.beds.getFirst()).grade()
                    == HousingRooms.Grade.EXPOSED, "removed roof invalidates previously qualifying housing");
                fixture.attempts(32);
                helper.assertTrue(fixture.animals().isEmpty(), "exposed beds do not count as developed housing");
                helper.succeed();
            }
        });
    }

    private static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final HousingRooms rooms = new HousingRooms();
        final BlockPos origin;
        final List<BlockPos> beds = new ArrayList<>();
        final List<ResidentEntity> residents = new ArrayList<>();
        final boolean spawning;
        ColonyPets pets;
        int seed;

        Fixture(GameTestHelper helper) {
            level = helper.getLevel(); colony = Colonies.mint(level.getServer());
            origin = helper.absolutePos(new BlockPos(2, 42, 2));
            spawning = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, level.getServer());
            for (BlockPos at : BlockPos.betweenClosed(origin.offset(-17, -1, -17), origin.offset(26, -1, 26))) {
                level.setBlockAndUpdate(at, Blocks.GLOWSTONE.defaultBlockState());
            }
            for (int x = 0; x <= 13; x++) for (int z = 0; z <= 13; z++) {
                level.setBlockAndUpdate(origin.offset(x, 3, z), Blocks.GLOWSTONE.defaultBlockState());
                for (int y = 0; y < 3; y++) level.setBlockAndUpdate(origin.offset(x, y, z),
                    (x == 0 || x == 13 || z == 0 || z == 13) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            BlockPos door = origin.offset(12, 0, 0);
            level.setBlockAndUpdate(door, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            level.setBlockAndUpdate(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            for (int row = 0; row < 3; row++) for (int x = 1; x <= 9; x++) {
                BlockPos bed = origin.offset(x, 0, 1 + row * 4);
                level.setBlockAndUpdate(bed, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT));
                level.setBlockAndUpdate(bed.south(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD));
                colony.hold(LivingContent.BED, new Held.Block(WorldPos.of(level, bed))).orElseThrow();
                beds.add(bed);
            }
            pets = new ColonyPets(colony, rooms);
        }

        void survey() {
            for (BlockPos bed : beds) {
                rooms.get(level, bed);
                for (int i = 0; i < 100; i++) rooms.tick();
            }
        }

        void addResidents(int count) {
            for (int i = 0; i < count; i++) {
                ResidentEntity person = PersonBody.RESIDENT.get().create(level);
                person.setNoAi(true);
                person.moveTo(origin.getX() + 4.5, origin.getY(), origin.getZ() + 4.5);
                level.addFreshEntity(person);
                colony.hold(PersonContent.ID, Held.Entity.of(person)).orElseThrow();
                person.joinColony(colony.id()); colony.entered(person); residents.add(person);
            }
        }

        void attempts(int count) {
            for (int i = 0; i < count; i++) {
                CompoundTag saved = pets.save();
                saved.putLong("next_attempt", level.getServer().overworld().getGameTime());
                pets.load(Reader.of(saved));
                level.getServer().overworld().random.setSeed(seed++);
                pets.tick(level.getServer());
            }
        }

        List<Entity> animals() {
            return level.getEntitiesOfClass(Entity.class, new AABB(origin).inflate(40), entity -> ColonyPets.belongsTo(entity, colony));
        }

        Set<EntityType<?>> sampleSpecies(int count) {
            Set<EntityType<?>> found = new HashSet<>();
            for (int i = 0; i < count; i++) {
                attempts(1);
                for (Entity entity : animals()) found.add(entity.getType());
                removePets();
            }
            return found;
        }

        void removePets() {
            for (Entity entity : animals()) { pets.removed(entity.getUUID()); entity.discard(); }
        }

        @Override public void close() {
            removePets(); residents.forEach(Entity::discard); pets.clear(); colony.raze();
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(spawning, level.getServer());
            for (BlockPos at : BlockPos.betweenClosed(origin.offset(-17, -1, -17), origin.offset(26, -1, 26))) {
                level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            }
            for (BlockPos at : BlockPos.betweenClosed(origin, origin.offset(13, 3, 13))) {
                level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            }
        }
    }
}
