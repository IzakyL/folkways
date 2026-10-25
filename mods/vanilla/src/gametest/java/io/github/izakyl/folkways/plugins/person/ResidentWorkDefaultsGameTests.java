package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.resident.body.Keenness;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.colony.WorkDefaults;
import io.github.izakyl.folkways.front.engine.colony.Endorsements;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.living.LivingContent;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
            colony.hold(LivingContent.BED, new Held.Block(WorldPos.of(level, foot))).orElseThrow();
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
            colony.hold(PersonContent.ID, Held.Entity.of(person)).orElseThrow();
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
            helper.assertTrue(colony.holdingOf(loaded.id()).isPresent(), "reload keeps the existing holding");
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
            first.hold(PersonContent.ID, Held.Entity.of(person)).orElseThrow();
            assertRule(helper, person, trade, true, Keenness.DEFAULT);
            WorkDefaults.set(first, PersonBody.ID, trade.id(), false, 1);
            WorkDefaults.set(second, PersonBody.ID, trade.id(), false, 4);
            helper.assertTrue(first.hold(PersonContent.ID, Held.Entity.of(person)).isEmpty(), "duplicate admission fails");
            helper.assertTrue(second.hold(PersonContent.ID, Held.Entity.of(person)).isEmpty(), "another colony cannot take them");
            assertRule(helper, person, trade, true, Keenness.DEFAULT);
            int held = second.holdings().size();
            helper.assertTrue(second.hold(PersonContent.ID, new Held.Entity(UUID.randomUUID(), PersonBody.ID)).isEmpty(),
                "an offline new human cannot miss template initialization");
            helper.assertTrue(second.holdings().size() == held, "offline failure adds no holding");
            first.release(first.holdingOf(person.id()).orElseThrow().id(), Release.LET_GO);
            second.hold(PersonContent.ID, Held.Entity.of(person)).orElseThrow();
            assertRule(helper, person, trade, false, Keenness.FOURTH);
        } finally {
            person.discard();
            first.raze();
            second.raze();
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
