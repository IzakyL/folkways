package io.github.izakyl.folkways.plugins.combat.ui;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroups;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CombatDeskGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(CombatDeskGameTests.class); }

    @GameTest(template = "empty_large")
    public static void bookReplacesItsSingleGuardPostWithoutCreatingAPatrolAndRejectsAStaleGroup(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 2);
        try {
            for (Body body : f.people) { helper.assertTrue(f.desk.act(f.player, "resident", body.id().toString()), "keeper selects a loaded resident"); }
            helper.assertTrue(f.desk.act(f.player, "create_group", ""), "the page creates one actual group");
            var rows = f.desk.reading(f.player).getList("groups", Tag.TAG_COMPOUND);
            UUID id = rows.getCompound(0).getUUID("id");
            var group = UnitGroups.of(f.colony).group(id).orElseThrow();
            helper.assertTrue(f.desk.act(f.player, "add_point", "") && f.desk.act(f.player, "execute", ""), "one page point starts a guard post");
            helper.assertTrue(group.patrolRoute().isEmpty() && f.people.stream().allMatch(body -> ResidentCommands.of(f.colony)
                .current(body.id()).orElseThrow().mode() == ResidentCommands.Mode.GUARD), "one point creates real group GUARD orders");
            helper.assertTrue(f.desk.act(f.player, "release", ""), "release is authorized through the same controller");
            helper.assertTrue(f.people.stream().allMatch(body -> ResidentCommands.of(f.colony).current(body.id()).isEmpty()), "release leaves no command");
            BlockPos second = helper.absolutePos(new BlockPos(16, 1, 10));
            f.player.moveTo(second.getX() + .5, second.getY(), second.getZ() + .5);
            helper.assertTrue(f.desk.act(f.player, "add_point", "") && f.desk.act(f.player, "execute", ""), "a second point replaces the book's single guard post");
            helper.assertValueEqual(f.desk.reading(f.player).getInt("pointCount"), 1, "the book retains only its newest guard post");
            helper.assertTrue(group.patrolRoute().isEmpty(), "the removed book patrol cannot be recreated by a stale page action");
            helper.assertTrue(f.people.stream().allMatch(body -> ResidentCommands.of(f.colony).current(body.id())
                .filter(order -> order.mode() == ResidentCommands.Mode.GUARD && order.destination().cell().getX() >= second.getX() - 2).isPresent()),
                "the real squad receives guard orders at the replacement post");
            UnitGroups.of(f.colony).dissolve(group);
            helper.assertFalse(f.desk.act(f.player, "group", id.toString()), "a dissolved group ID cannot be selected");
            helper.assertFalse(f.desk.act(f.player, "execute", ""), "the old selected ID cannot reacquire control");
        } finally { f.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void combatDefaultsConfigureTheColonyWithoutSelectingOrReassigningASquad(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 2);
        try {
            helper.assertTrue(f.desk.act(f.player, "defaults_iron", ""), "the keeper checks all four iron armour parts");
            helper.assertTrue(f.desk.act(f.player, "defaults_weapon_bow", ""), "the keeper chooses a real bow goal");
            helper.assertTrue(f.desk.act(f.player, "defaults_ammo_32", ""), "the keeper chooses the actual arrow reserve");
            helper.assertTrue(f.desk.act(f.player, "defaults_enabled", ""), "the keeper enables the colony default");
            var defaults = f.desk.reading(f.player).getCompound("defaults");
            helper.assertTrue(defaults.getBoolean("enabled") && List.of("head", "chest", "legs", "feet").stream()
                .allMatch(defaults::getBoolean), "all four armour slots are included in the enabled goal");
            helper.assertValueEqual(defaults.getString("weapon"), "bow", "the configured weapon is retained");
            helper.assertValueEqual(defaults.getInt("ammo"), 32, "the configured reserve is retained");
            helper.assertTrue(f.people.stream().allMatch(body -> ResidentCommands.of(f.colony).current(body.id()).isEmpty()
                && UnitGroups.of(f.colony).ofResident(body.id()).isEmpty()), "configuration does not issue a movement order or secretly regroup anyone");
            helper.assertFalse(f.desk.act(f.player, "defaults_weapon_unknown", ""), "a forged weapon action cannot change defaults");
            helper.assertValueEqual(f.desk.reading(f.player).getCompound("defaults").getString("weapon"), "bow", "rejection leaves the valid configuration intact");
        } finally { f.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void pageAuthorityAndMembershipAreRecheckedAndSelectionNeverExceedsEight(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 9);
        Colony foreign = Colonies.mint(helper.getLevel().getServer());
        Body outsider = PersonBody.RESIDENT.get().create(helper.getLevel());
        outsider.mob().moveTo(f.player.getX(), f.player.getY(), f.player.getZ());
        foreign.hold(PersonContent.ID, Held.Entity.of(outsider.mob())).orElseThrow(); outsider.joinColony(foreign.id());
        helper.getLevel().addFreshEntity(outsider.mob());
        try {
            helper.assertFalse(f.desk.act(f.player, "resident", outsider.id().toString()), "a loaded foreign resident is still unauthorized");
            for (int n = 0; n < 8; n++) { helper.assertTrue(f.desk.act(f.player, "resident", f.people.get(n).id().toString()), "up to eight distinct people can be selected"); }
            helper.assertFalse(f.desk.act(f.player, "resident", f.people.get(8).id().toString()), "a ninth selection is rejected");
            ServerPlayer intruder = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "combat_intruder"));
            intruder.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(f.colony.id()));
            helper.assertFalse(f.desk.act(intruder, "create_group", ""), "forging current-colony state does not create keeper authority");
            Charters.keep(f.colony, Charter.founded("revoked", UUID.randomUUID()));
            helper.assertFalse(f.desk.act(f.player, "create_group", ""), "a formerly valid page cannot act after authority is revoked");
        } finally { outsider.mob().discard(); foreign.raze(); f.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void anotherKeeperReleasesTheSameSquadsRetreatWithoutCancellingANewerIndividualOrder(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 2);
        try {
            for (Body body : f.people) { f.desk.act(f.player, "resident", body.id().toString()); }
            helper.assertTrue(f.desk.act(f.player, "create_group", ""), "the first keeper creates an actual squad");
            UUID id = f.desk.reading(f.player).getList("groups", Tag.TAG_COMPOUND).getCompound(0).getUUID("id");
            var group = UnitGroups.of(f.colony).group(id).orElseThrow();
            ServerPlayer second = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "combat_second"));
            second.moveTo(f.player.getX(), f.player.getY(), f.player.getZ());
            Charters.keep(f.colony, Charters.written(f.colony).with(second.getUUID()));
            second.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(f.colony.id()));
            helper.assertTrue(f.desk.act(second, "group", id.toString()), "another current keeper selects the same squad");
            var firstReading = f.desk.reading(f.player);
            var secondReading = f.desk.reading(second);
            int number = firstReading.getList("groups", Tag.TAG_COMPOUND).getCompound(0).getInt("number");
            helper.assertTrue(number > 0 && secondReading.getList("groups", Tag.TAG_COMPOUND).getCompound(0).getInt("number") == number,
                "all keepers see the same simple squad number during this colony session");
            helper.assertFalse(firstReading.get("summary").toString().contains(id.toString().substring(0, 8))
                    || secondReading.get("summary").toString().contains(id.toString().substring(0, 8)),
                "the visible squad summary uses its number rather than an internal identifier");
            helper.assertTrue(f.desk.act(f.player, "retreat", ""), "the first keeper orders an actual group retreat");
            helper.assertTrue(group.destinations().size() == 2 && f.people.stream().allMatch(body -> ResidentCommands.of(f.colony)
                .current(body.id()).orElseThrow().mode() == ResidentCommands.Mode.RETREAT), "the squad itself owns every high-priority retreat handle");
            helper.assertTrue(f.desk.act(second, "release", ""), "a different keeper releases those same handles");
            helper.assertTrue(f.people.stream().allMatch(body -> ResidentCommands.of(f.colony).current(body.id()).isEmpty()),
                "retreat control is not hidden in the first keeper's page draft");
            helper.assertTrue(f.desk.act(f.player, "retreat", ""), "the group can retreat again");
            Body individual = f.people.getFirst();
            var newer = ResidentCommands.of(f.colony).hold(individual,
                io.github.izakyl.folkways.core.api.terms.WorldSpaces.at(individual.mob()));
            helper.assertTrue(f.desk.act(second, "release", ""), "the group remains releasable during an individual override");
            helper.assertTrue(ResidentCommands.of(f.colony).current(individual.id()).orElseThrow() == newer,
                "an old group retreat token cannot cancel a newer personal order");
            newer.close();
        } finally { f.close(); }
        helper.succeed();
    }

    private static final class Fixture {
        final Colony colony;
        final ServerPlayer player;
        final CombatDesk desk;
        final List<Body> people = new ArrayList<>();
        Fixture(GameTestHelper helper, int count) {
            var level = helper.getLevel();
            for (int x = 0; x < 22; x++) for (int z = 0; z < 22; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "combat_keeper"));
            BlockPos at = helper.absolutePos(new BlockPos(10, 1, 10)); player.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            colony = Colonies.mint(level.getServer()); Charters.keep(colony, Charter.founded("combat test", player.getUUID()));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            desk = CombatDesk.of(colony);
            for (int n = 0; n < count; n++) {
                Body body = PersonBody.RESIDENT.get().create(level);
                BlockPos feet = helper.absolutePos(new BlockPos(2 + n % 3 * 2, 1, 2 + n / 3 * 2));
                body.mob().moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
                colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow(); body.joinColony(colony.id());
                level.addFreshEntity(body.mob()); people.add(body);
            }
        }
        void close() { people.forEach(body -> body.mob().discard()); colony.raze(); }
    }
}
