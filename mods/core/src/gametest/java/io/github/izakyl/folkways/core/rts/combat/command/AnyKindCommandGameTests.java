package io.github.izakyl.folkways.core.rts.combat.command;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Commands;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Locomotions;
import io.github.izakyl.folkways.core.resident.api.ResidentKind;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Direct command is not a person's privilege: any kind that stands to the command stakes takes orders. */
@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AnyKindCommandGameTests {
    // A kind is keyed by its entity type; only golems tagged by this test are embodied.
    private static final ResourceLocation COMMANDED = ResourceLocation.withDefaultNamespace("iron_golem");
    private static final ResourceLocation UNCOMMANDED = ResourceLocation.withDefaultNamespace("snow_golem");
    private static final String TAG = "folkways_core.test.command";
    private static final ResourceLocation HOLDER = id("command_holder");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(AnyKindCommandGameTests.class);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void declare(Declaring event) {
        event.residentKind(golem(COMMANDED).taking(Commands.rows()));
        event.residentKind(golem(UNCOMMANDED).taking(Map.of()));
    }

    // Only golems tagged for this test take these kinds, so every other golem stays an ordinary mob.
    private static ResidentKind golem(ResourceLocation kind) {
        return ResidentKind.growing(kind, Locomotions.named(Locomotions.WALKING))
            .embodiedBy((declared, entity) -> entity instanceof AbstractGolem golem && golem.getTags().contains(TAG)
                ? Optional.of(new Body() {
                    public Mob mob() {
                        return golem;
                    }

                    public ResidentKind kind() {
                        return declared;
                    }
                })
                : Optional.empty());
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void aNonPersonKindThatOptsInFollowsAMoveOrder(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        LaborFixture simulation = new LaborFixture(colony, helper.getLevel());
        for (int x = 0; x < 22; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Body commanded = golem(helper, colony, COMMANDED, new BlockPos(2, 1, 2));
        Body uncommanded = golem(helper, colony, UNCOMMANDED, new BlockPos(2, 1, 8));
        Cleanup.on(helper, () -> {
            simulation.close();
            commanded.mob().discard();
            uncommanded.mob().discard();
            colony.raze();
        });
        helper.onEachTick(simulation::tick);
        ResidentCommands commands = ResidentCommands.of(colony);

        helper.assertTrue(Commands.commandable(commanded), "a kind standing to the command stakes is commandable");
        helper.assertFalse(Commands.commandable(uncommanded), "a kind that did not opt in is not");
        try {
            commands.move(uncommanded, at(helper, 8, 1, 8));
            helper.fail("orders must refuse a kind that did not opt in");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(commands.current(uncommanded.id()).isEmpty(), "the refused body holds no order");
        }

        WorldPos destination = at(helper, 10, 1, 2);
        ResidentCommands.Order move = commands.move(commanded, destination);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(move.status() == ResidentCommands.Status.ARRIVED,
                "the golem walks to the ordered cell"))
            .thenExecute(() -> helper.assertTrue(
                commanded.mob().blockPosition().closerThan(destination.block(helper.getLevel()), 2),
                "the golem stands at the destination"))
            .thenSucceed();
    }

    private static Body golem(GameTestHelper helper, Colony colony, ResourceLocation kind, BlockPos at) {
        AbstractGolem golem = (AbstractGolem) BuiltInRegistries.ENTITY_TYPE.get(kind).create(helper.getLevel());
        golem.addTag(TAG);
        // The kind's AI is the colony's: the vanilla golem's own wandering and village goals step aside.
        golem.goalSelector.removeAllGoals(goal -> true);
        golem.targetSelector.removeAllGoals(goal -> true);
        BlockPos absolute = helper.absolutePos(at);
        golem.moveTo(absolute.getX() + .5, absolute.getY(), absolute.getZ() + .5);
        helper.getLevel().addFreshEntity(golem);
        Body body = Bodies.of(golem).orElseThrow(() -> new IllegalStateException(kind + " must embody the golem"));
        colony.hold(HOLDER, Claimed.Entity.of(golem)).orElseThrow();
        body.joinColony(colony.id());
        return body;
    }

    private static WorldPos at(GameTestHelper helper, int x, int y, int z) {
        return WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z)));
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("folkways_core", "test/" + name);
    }
}
