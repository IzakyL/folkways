package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ApiaryHarvestGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) {
        event.register(ApiaryHarvestGameTests.class);
    }

    @GameTest(template = "empty")
    public static void bottleHarvestSpendsOneBottleAndCannotTakeHoneyTwice(GameTestHelper helper) {
        var node = hive(helper, true);
        var pack = new SimpleContainer(new ItemStack(Items.GLASS_BOTTLE, 2));
        Outcome first = Commit.run(helper.getLevel(), node, worker(helper, pack));
        helper.assertTrue(first instanceof Outcome.Done done && done.made().size() == 1
            && done.made().getFirst().is(Items.HONEY_BOTTLE), "one full hive gives one bottle");
        helper.assertTrue(pack.countItem(Items.GLASS_BOTTLE) == 1, "one empty bottle is spent");
        helper.assertTrue(Commit.run(helper.getLevel(), node, worker(helper, pack)) instanceof Outcome.Failed,
            "a second harvest cannot duplicate honey");
        helper.assertTrue(pack.countItem(Items.GLASS_BOTTLE) == 1, "a failed harvest returns its bottle");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removingSmokeAfterPlanningPreservesHoneyAndBottle(GameTestHelper helper) {
        var node = hive(helper, true);
        helper.assertTrue(node.ready(helper.getLevel()), "smoked mature hive is ready");
        helper.getLevel().setBlockAndUpdate(pos(helper).below(2), Blocks.AIR.defaultBlockState());
        var pack = new SimpleContainer(new ItemStack(Items.GLASS_BOTTLE));
        helper.assertTrue(Commit.run(helper.getLevel(), node, worker(helper, pack)) instanceof Outcome.Failed,
            "smoke is rechecked at commit");
        helper.assertTrue(pack.countItem(Items.GLASS_BOTTLE) == 1, "empty bottle is returned");
        helper.assertTrue(helper.getLevel().getBlockState(pos(helper)).getValue(BeehiveBlock.HONEY_LEVEL) == 5,
            "honey remains in the hive");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void combHarvestDeclaresShearsAndProducesThreeComb(GameTestHelper helper) {
        var node = hive(helper, false);
        helper.assertTrue(node.spec().needs().isEmpty() && node.spec().tools().size() == 1,
            "comb needs shears rather than bottles");
        helper.assertTrue(node.spec().tools().getFirst().baseWear() == 1, "one durability is declared");
        Outcome result = node.commit(helper.getLevel(), worker(helper, new SimpleContainer(2)));
        helper.assertTrue(result instanceof Outcome.Done done && done.made().getFirst().is(Items.HONEYCOMB)
            && done.made().getFirst().getCount() == 3, "one hive gives three comb");
        helper.assertFalse(node.ready(helper.getLevel()), "honey is reset after cutting");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void retiredNodesCannotHarvestAndOneHiveHasOneClaim(GameTestHelper helper) {
        var node = hive(helper, true);
        helper.assertTrue(node.planned(helper.getLevel()).isEmpty(), "first claim succeeds");
        helper.assertTrue(node.planned(helper.getLevel()).isPresent(), "second claim is refused");
        node.retire();
        helper.assertFalse(node.ready(helper.getLevel()), "removed or reconfigured zone retires its old work");
        helper.assertTrue(node.commit(helper.getLevel(), worker(helper, new SimpleContainer(2))) instanceof Outcome.Failed,
            "stale work cannot change a hive");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingProductAndRemovingZoneRetiresPreviouslyPublishedWork(GameTestHelper helper) {
        hive(helper, true);
        var level = helper.getLevel();
        boolean[] bottles = {true};
        boolean[] present = {true};
        var settings = proxy(io.github.izakyl.folkways.front.api.Settings.class, (method, args) -> switch (method) {
            case "choice" -> net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                bottles[0] ? "honey_bottle" : "honeycomb");
            default -> throw new AssertionError(method);
        });
        var zone = proxy(io.github.izakyl.folkways.front.api.ZoneView.class, (method, args) -> switch (method) {
            case "id" -> new java.util.UUID(0, 42);
            case "delegation" -> PastureContent.APIARY;
            case "settings" -> settings;
            case "cells" -> java.util.Set.of(pos(helper));
            case "at" -> WorldPos.of(level, (BlockPos) args[0]);
            default -> throw new AssertionError(method);
        });
        var view = proxy(io.github.izakyl.folkways.core.api.colony.ColonyView.class, (method, args) -> switch (method) {
            case "level" -> level;
            case "dimension" -> level.dimension();
            default -> throw new AssertionError(method);
        });
        var front = proxy(io.github.izakyl.folkways.front.api.FrontView.class, (method, args) -> switch (method) {
            case "zones", "zonesIn" -> present[0] ? List.of(zone) : List.of();
            default -> throw new AssertionError(method);
        });
        var apiaries = new Apiaries(null, new PastDay(), () -> { });
        apiaries.refresh(view, front);
        var bottleNode = apiaries.goals(view).getFirst().nodes().getFirst();
        apiaries.refresh(view, front);
        helper.assertTrue(apiaries.goals(view).getFirst().nodes().getFirst() == bottleNode,
            "refresh keeps the same hive claim");
        bottles[0] = false;
        apiaries.refresh(view, front);
        var combNode = apiaries.goals(view).getFirst().nodes().getFirst();
        helper.assertTrue(combNode != bottleNode && !bottleNode.ready(level), "product changes retire old work");
        helper.assertTrue(combNode.spec().needs().isEmpty(), "new work uses the configured comb product");
        present[0] = false;
        apiaries.refresh(view, front);
        helper.assertTrue(apiaries.goals(view).isEmpty() && !combNode.ready(level), "zone removal retires work");
        helper.succeed();
    }

    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> invoke) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (ignored, method, args) -> invoke.apply(method.getName(), args)));
    }

    private static BlockPos pos(GameTestHelper helper) { return helper.absolutePos(new BlockPos(2, 3, 2)); }
    private static ApiaryHarvestNode hive(GameTestHelper helper, boolean bottles) {
        var level = helper.getLevel();
        BlockPos hive = pos(helper);
        level.setBlockAndUpdate(hive.below(3), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(hive.below(2), Blocks.CAMPFIRE.defaultBlockState());
        level.setBlockAndUpdate(hive.below(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(hive, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, 5));
        return new ApiaryHarvestNode(WorldPos.of(level, hive), Stances.at(level, hive.east()), bottles,
            new PastDay(), () -> { });
    }
    private static Worker worker(GameTestHelper helper, SimpleContainer pack) {
        var resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        return (Worker) Proxy.newProxyInstance(Worker.class.getClassLoader(), new Class<?>[] {Worker.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "body" -> resident;
                case "resident" -> resident.resident();
                case "pack" -> pack;
                case "within", "route", "supplied" -> List.of();
                case "placement" -> Placement.NOWHERE;
                case "held" -> new ItemStack(Items.SHEARS);
                case "effects" -> Effects.NONE;
                default -> throw new AssertionError(method.getName());
            });
    }
}
