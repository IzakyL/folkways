package io.github.izakyl.folkways.plugins.person;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.resident.api.WorkPriorities;
import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.front.api.Endorsed;
import io.github.izakyl.folkways.front.api.Endorsement;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.colony.Endorsements;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.living.LivingContent;
import io.github.izakyl.folkways.plugins.person.living.LivingNeed;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentWorkDefaultsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentWorkDefaultsGameTests.class);
    }

    @GameTest(template = "empty")
    public static void incompleteInventoryWaitsForTheImmigrationFoodGate(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        try {
            BlockPos foot = helper.absolutePos(new BlockPos(1, 1, 1));
            level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST), 2);
            level.setBlock(foot.east(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.HEAD), 2);
            colony.hold(LivingContent.BED, new Claimed.Block(WorldPos.of(level, foot))).orElseThrow();
            BlockPos chest = helper.absolutePos(new BlockPos(4, 1, 1));
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.BREAD, 64));
            WorldPos store = WorldPos.of(level, chest);
            colony.stores(PersonContent.ID, level.dimension(), List.of(store));
            ColonyData data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            data.inventory().advance(level, data.works(), Map.of());
            var food = LivingContent.settlerNeeds(colony, level.getServer()).stream()
                .filter(need -> need.kind() == LivingNeed.FOOD)
                .findFirst().orElseThrow();
            helper.assertTrue(food.met(0), "the observed food count alone exceeds the requirement");
            var queue = colony.service(PersonContent.ID, PersonPresence.class).orElseThrow();
            var queueField = PersonPresence.class.getDeclaredField("settlers");
            queueField.setAccessible(true);
            var waitingField = SettlerQueue.class.getDeclaredField("waiting");
            waitingField.setAccessible(true);
            waitingField.setInt(queueField.get(queue), 1);
            int waiting = queue.waiting();
            helper.assertTrue(waiting > 0, "a settler is waiting");
            data.inventory().changed(Stash.at(store));
            helper.assertFalse(colony.inventory().snapshot().coverage().complete(), "dirty food is incomplete");
            var asked = new Endorsement.Called(colony, colony.view(level),
                helper.makeMockPlayer(GameType.SURVIVAL), foot, PersonBody.ID);
            var answer = new Immigration().answer(asked).orElseThrow();
            helper.assertTrue(answer instanceof Endorsed.Pending,
                "last-known sufficient food waits while coverage is incomplete");
            data.inventory().advance(level, data.works(), Map.of());
            queue.tick(level.getServer());
            helper.assertTrue(queue.waiting() == waiting - 1 && !colony.residents().isEmpty(),
                "the pending admission retries after the observation is published");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void realImmigrationCopiesTheCurrentTemplateOnlyToTheNextArrival(GameTestHelper helper) {
        var level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        List<ResidentEntity> people = new ArrayList<>();
        for (int z : new int[]{1, 3, 5}) {
            BlockPos foot = helper.absolutePos(new BlockPos(1, 1, z));
            level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST), 2);
            level.setBlock(foot.east(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.HEAD), 2);
            colony.hold(LivingContent.BED, new Claimed.Block(WorldPos.of(level, foot))).orElseThrow();
        }
        BlockPos chest = helper.absolutePos(new BlockPos(4, 1, 1));
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.BREAD, 64));
        colony.stores(PersonContent.ID, level.dimension(), List.of(WorldPos.of(level, chest)));
        var queue = colony.service(PersonContent.ID, PersonPresence.class).orElseThrow();
        // Start this colony's clock far enough back to gather two settlers on its next sweep.
        long day = level.getServer().overworld().getDayTime();
        try {
            level.getServer().overworld().setDayTime(day - 3L * FolkwaysVanillaConfig.settlerIntervalTicks());
            queue.tick(level.getServer());
        } finally {
            level.getServer().overworld().setDayTime(day);
        }
        Vocation trade = Vocations.all().getFirst();
        WorkDefaults.set(colony, PersonBody.ID, trade.id(), false, 1);
        helper.runAfterDelay(105, () -> {
            try {
                queue.tick(level.getServer());
                helper.assertTrue(queue.waiting() >= 2, "settlers have gathered through the real queue");
                var player = helper.makeMockPlayer(GameType.SURVIVAL);
                var firstAttempt = Endorsements.called(level, colony, player, PersonBody.ID);
                helper.assertFalse(firstAttempt.refused(), "the supplied colony admits a human: " + firstAttempt);
                ResidentEntity first = resident(level, colony.residents().getFirst().id());
                people.add(first);
                assertRule(helper, first, trade, false, Keenness.FIRST);
                var known = first.licences().trade(trade).orElseThrow();
                first.licences().allow(known, true);
                first.licences().setKeenness(known, Keenness.SECOND);
                WorkDefaults.set(colony, PersonBody.ID, trade.id(), false, 4);
                assertRule(helper, first, trade, true, Keenness.SECOND);
                var secondAttempt = Endorsements.called(level, colony, player, PersonBody.ID);
                helper.assertFalse(secondAttempt.refused(), "the next human also joins: " + secondAttempt);
                ResidentEntity second = colony.residents().stream().filter(who -> !who.id().equals(first.id()))
                    .map(who -> resident(level, who.id())).findFirst().orElseThrow();
                people.add(second);
                assertRule(helper, second, trade, false, Keenness.FOURTH);
                helper.succeed();
            } finally {
                people.forEach(Entity::discard);
                colony.raze();
            }
        });
    }

    @GameTest(template = "empty")
    public static void personalChangesSurviveEntityReloadAndMaidLooks(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        Vocation trade = Vocations.all().getFirst();
        WorkDefaults.set(colony, PersonBody.ID, trade.id(), false, 1);
        ResidentEntity person = spawn(helper);
        var maid = ResourceKey.create(ResidentLooks.REGISTRY,
            ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "maid/touhou/reimu"));
        person.setLookId(maid);
        try {
            colony.hold(PersonContent.ID, Claimed.Entity.of(person)).orElseThrow();
            assertRule(helper, person, trade, false, Keenness.FIRST);
            var known = person.licences().trade(trade).orElseThrow();
            person.licences().allow(known, true);
            person.licences().setKeenness(known, Keenness.SECOND);
            CompoundTag saved = person.saveWithoutId(new CompoundTag());
            person.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            ResidentEntity loaded = PersonBody.RESIDENT.get().create(helper.getLevel());
            loaded.load(saved);
            helper.assertValueEqual(loaded.lookId().orElseThrow(), maid, "maid appearance round trips with a human");
            colony.entered(loaded);
            assertRule(helper, loaded, trade, true, Keenness.SECOND);
            loaded.setLookId(ResourceKey.create(ResidentLooks.REGISTRY,
                ResourceLocation.fromNamespaceAndPath("folkways", "alex_slim")));
            colony.entered(loaded);
            assertRule(helper, loaded, trade, true, Keenness.SECOND);
            helper.assertTrue(colony.claimOf(loaded.id()).isPresent(), "reload keeps the existing holding");
        } finally {
            person.discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedOrOfflineAdmissionsDoNotChangeLicencesAndRejoiningUsesTheDestination(GameTestHelper helper) {
        Colony first = Colonies.mint(helper.getLevel().getServer());
        Colony second = Colonies.mint(helper.getLevel().getServer());
        Vocation trade = Vocations.all().getFirst();
        ResidentEntity person = spawn(helper);
        try {
            first.hold(PersonContent.ID, Claimed.Entity.of(person)).orElseThrow();
            assertRule(helper, person, trade, true, Keenness.DEFAULT);
            WorkDefaults.set(first, PersonBody.ID, trade.id(), false, 1);
            WorkDefaults.set(second, PersonBody.ID, trade.id(), false, 4);
            helper.assertTrue(first.hold(PersonContent.ID, Claimed.Entity.of(person)).isEmpty(), "duplicate admission fails");
            helper.assertTrue(second.hold(PersonContent.ID, Claimed.Entity.of(person)).isEmpty(), "another colony cannot take them");
            assertRule(helper, person, trade, true, Keenness.DEFAULT);
            int held = second.claims().size();
            helper.assertTrue(second.hold(PersonContent.ID, new Claimed.Entity(UUID.randomUUID(), PersonBody.ID)).isEmpty(),
                "an offline new human cannot miss template initialization");
            helper.assertTrue(second.claims().size() == held, "offline failure adds no holding");
            first.release(first.claimOf(person.id()).orElseThrow().id(), ClaimRelease.LET_GO);
            second.hold(PersonContent.ID, Claimed.Entity.of(person)).orElseThrow();
            assertRule(helper, person, trade, false, Keenness.FOURTH);
        } finally {
            person.discard();
            first.raze();
            second.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void emptyColonyCanBeEditedOnlyByItsKeepersWithoutOverwritingOtherEntries(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var keeper = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "keeper"),
            ClientInformation.createDefault());
        var stranger = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "stranger"),
            ClientInformation.createDefault());
        Charters.keep(colony, Charter.founded("defaults", keeper.getUUID()));
        keeper.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        stranger.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var hauling = Vocations.HAULING;
        var building = ResourceLocation.fromNamespaceAndPath("folkways", "building");
        try {
            WorkDefaultsPage.editDefault(stranger, hauling, true, 0);
            helper.assertValueEqual(WorkDefaults.of(colony, PersonBody.ID).of(hauling), WorkPriorities.DEFAULT,
                "naming someone else's colony grants no authority");
            WorkDefaultsPage.editDefault(keeper, hauling, true, 0);
            WorkDefaultsPage.editDefault(keeper, building, false, -1);
            var priorities = WorkDefaults.of(colony, PersonBody.ID);
            helper.assertFalse(priorities.of(hauling).allowed(), "a keeper can toggle work before anyone joins");
            helper.assertTrue(priorities.of(building).priority() == Keenness.SECOND, "ranking another entry keeps the first edit");
            helper.assertTrue(colony.residents().isEmpty(), "defaults do not create roster entries");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    private static ResidentEntity spawn(GameTestHelper helper) {
        ResidentEntity person = PersonBody.RESIDENT.get().create(helper.getLevel());
        person.moveTo(helper.absolutePos(new BlockPos(1, 2, 1)).getCenter());
        helper.getLevel().addFreshEntity(person);
        return person;
    }

    private static ResidentEntity resident(net.minecraft.server.level.ServerLevel level, UUID id) {
        return (ResidentEntity) level.getEntity(id);
    }

    private static void assertRule(GameTestHelper helper, ResidentEntity person, Vocation trade,
            boolean allowed, Keenness priority) {
        var known = person.licences().trade(trade).orElseThrow();
        helper.assertTrue(person.licences().allows(known) == allowed && person.licences().keennessOf(known) == priority,
            "resident work settings must be " + allowed + ", " + priority);
    }
}
