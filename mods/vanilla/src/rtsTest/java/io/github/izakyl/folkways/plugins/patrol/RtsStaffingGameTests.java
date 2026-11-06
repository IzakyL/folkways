package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.plugins.combat.SpottedTargets;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.rts.FolkwaysRtsMod;
import io.github.izakyl.folkways.rts.combat.command.ResidentCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysRtsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class RtsStaffingGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RtsStaffingGameTests.class);
    }

    @GameTest(template = "empty_large")
    public static void manualTakeoverRevokesWholeTeamCallWithoutReplacingManualHandle(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        List<ResidentEntity> people = new ArrayList<>();
        Zombie target = EntityType.ZOMBIE.create(level);
        try {
            for (int x = 0; x < 25; x++) {
                for (int z = 0; z < 23; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            for (BlockPos at : List.of(new BlockPos(2, 1, 3), new BlockPos(2, 1, 6), new BlockPos(8, 1, 15))) {
                ResidentEntity person = PersonBody.RESIDENT.get().create(level);
                move(helper, person, at);
                level.addFreshEntity(person);
                colony.hold(PersonContent.ID, Held.Entity.of(person)).orElseThrow();
                person.joinColony(colony.id());
                person.pack().insert(new ItemStack(Items.IRON_SWORD));
                arm(colony, person, "Melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS));
                people.add(person);
            }
            ResidentEntity first = people.get(0);
            ResidentEntity second = people.get(1);
            UUID profile = LoadoutProfiles.of(colony).assigned(first.id()).orElseThrow().id();
            LoadoutProfiles.of(colony).assign(List.of(second.id()), Optional.of(profile));
            UUID route = colony.hold(PatrolContent.BEAT, new Held.Line(List.of(
                WorldPos.of(level, helper.absolutePos(new BlockPos(3, 1, 5))),
                WorldPos.of(level, helper.absolutePos(new BlockPos(23, 1, 5)))))).orElseThrow().id();
            PatrolStaffing staffing = PatrolStaffing.of(colony);
            LoadoutProfiles.of(colony).assign(List.of(people.get(2).id()), Optional.empty());
            staffing.configure(level, route, java.util.Set.of(profile), 2);
            move(helper, target, new BlockPos(9, 1, 18));
            target.setNoAi(true);
            level.addFreshEntity(target);
            PatrolPresence patrol = colony.service(PatrolContent.ID, PatrolPresence.class).orElseThrow();
            patrol.refresh(level.getServer());
            patrol.tick(level.getServer());
            patrol.urges(worker(first), colony.view(level));
            patrol.urges(worker(second), colony.view(level));
            SpottedTargets.of(colony).report(people.get(2), target);
            patrol.call(people.get(2), target);
            BooleanSupplier permission = patrol.teams().permission(worker(first), target);
            helper.assertTrue(permission.getAsBoolean() && patrol.responding(second.id()),
                "the live team call initially authorizes both members");
            var commands = ResidentCommands.of(colony);
            var manual = commands.hold(first, WorldPos.of(level, first.blockPosition()));
            helper.assertFalse(permission.getAsBoolean(), "manual takeover immediately revokes captured combat");
            helper.assertFalse(patrol.responding(second.id()), "one manual takeover revokes the whole response");
            staffing.tick(level.getServer());
            patrol.tick(level.getServer());
            helper.assertTrue(commands.current(first.id()).orElse(null) == manual,
                "autonomous regroup cannot replace or close the manual handle");
            helper.assertValueEqual(staffing.positions(route).stream().filter(position -> position.member()
                .filter(first.id()::equals).isPresent()).findFirst().orElseThrow().status(), "controlled",
                "the incumbent visibly suspends during direct control");
        } finally {
            people.forEach(Entity::discard);
            target.discard();
            colony.raze();
        }
        helper.succeed();
    }

    private static void move(GameTestHelper helper, Mob body, BlockPos at) {
        BlockPos cell = helper.absolutePos(at);
        body.moveTo(cell.getX() + .5, cell.getY(), cell.getZ() + .5);
    }

    private static Worker worker(ResidentEntity body) {
        return TestWorker.of(body)
            .held(need -> body.pack().contents().stream().filter(stack -> Tools.answers(stack, need)).findFirst()
                .orElse(ItemStack.EMPTY))
            .spill(body::spawnAtLocation);
    }

    private static void arm(Colony colony, Body body, String name, ItemSpec weapon) {
        LoadoutProfiles profiles = LoadoutProfiles.of(colony);
        LoadoutProfile profile =
            profiles.all().stream()
            .filter(one -> one.name().equals(name))
            .findFirst()
            .orElseGet(() -> profiles.add(profiles.create(name).id(), LoadoutProfile.Entry.weapon(weapon)));
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
    }
}
