package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class BuildCacheGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BuildCacheGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 280)
    public static void revokedHandLeavesStoneForAnotherResident(GameTestHelper helper) {
        var level = helper.getLevel();
        SiteRig rig = stoneSite(helper);
        Node original = rig.asked.getFirst().nodes().getFirst();
        Worker first = atTurn(helper, original, new SimpleContainer(new ItemStack(Items.STONE, 2)));
        SiteRig.Shift shift = new SiteRig.Shift(original);
        shift.tick(level, first);
        shift.revoke(level, first);
        rig.site.tick(level);
        helper.assertTrue(first.pack().isEmpty(), "the revoked hand leaves its stone at the site");
        helper.assertValueEqual(rig.site.cache.stock().getOrDefault(ItemSpec.of(Items.STONE), 0), 2,
            "both unspent stones stay at the site");
        Node replacement = rig.asked.getLast().nodes().getFirst();
        helper.assertTrue(!original.id().equals(replacement.id()) && replacement.spec().needs().isEmpty(),
            "the replacement turn needs no delivery");
        Worker next = atTurn(helper, replacement, new SimpleContainer(4));
        SiteRig.Shift nextShift = new SiteRig.Shift(replacement);
        nextShift.step(level, next);
        rig.site.tick(level);
        nextShift.step(level, next);
        rig.site.tick(level);
        helper.assertValueEqual(rig.site.left(), 0, "the second resident uses both cached stones");
        helper.assertTrue(rig.site.cache.stock().isEmpty(), "each stone is spent once");
        rig.site.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void excavatedDirtFeedsTheNextPiece(GameTestHelper helper) {
        var level = helper.getLevel();
        SiteRig.floor(helper);
        BlockPos dig = helper.absolutePos(new BlockPos(5, 1, 5));
        BlockPos lay = dig.east();
        level.setBlockAndUpdate(dig, Blocks.DIRT.defaultBlockState());
        SiteRig rig = SiteRig.start(level, SiteRig.target(level,
            Map.of(dig, Blocks.AIR.defaultBlockState(), lay, Blocks.DIRT.defaultBlockState())));
        Worker hand = SiteRig.worker(helper.spawn(EntityType.PIG, new BlockPos(5, 1, 6)), new SimpleContainer(4));
        Node turn = rig.asked.stream().flatMap(work -> work.nodes().stream())
            .filter(node -> node.spec().needs().isEmpty()).findFirst().orElseThrow();
        SiteRig.Shift shift = new SiteRig.Shift(turn);
        Outcome dug = shift.step(level, hand);
        helper.assertTrue(dug instanceof Outcome.Done done && done.made().isEmpty(), "dirt stays out of the pack");
        helper.assertValueEqual(rig.site.cache.stock().getOrDefault(ItemSpec.of(Items.DIRT), 0), 1,
            "the excavated dirt is cached");
        int asked = rig.asked.size();
        rig.site.tick(level);
        helper.assertValueEqual(rig.asked.size(), asked,
            "a turn already asked for is not withdrawn and asked again: its fetch may be on the way");
        Node laying = rig.asked.stream().flatMap(work -> work.nodes().stream())
            .filter(node -> !node.spec().needs().isEmpty()).findFirst().orElseThrow();
        helper.assertTrue(new SiteRig.Shift(laying).step(level, hand) instanceof Outcome.Done,
            "a hand at the site without dirt lays the cached dirt");
        helper.assertTrue(level.getBlockState(lay).is(Blocks.DIRT), "the dug dirt is laid in the next cell");
        helper.assertTrue(rig.site.cache.stock().isEmpty(), "laying spends the cached block once");
        rig.site.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void aFullCacheLeavesExcessInThePack(GameTestHelper helper) {
        var level = helper.getLevel();
        SiteRig rig = stoneSite(helper);
        Node turn = rig.asked.getFirst().nodes().getFirst();
        Worker hand = atTurn(helper, turn, new SimpleContainer(new ItemStack(Items.STONE, 2)));
        SiteRig.Shift shift = new SiteRig.Shift(turn);
        shift.tick(level, hand);
        int size = Items.COBBLESTONE.getDefaultMaxStackSize();
        int cap = Carving.Cache.WHOLE_STACKS * size;
        int remaining = cap - 1;
        while (remaining > 0) {
            ItemStack stack = new ItemStack(Items.COBBLESTONE, Math.min(remaining, size));
            helper.assertValueEqual(rig.site.cache.deposit(stack, remaining), stack.getCount(),
                "the cache accepts whole blocks within its cap");
            remaining -= stack.getCount();
        }
        shift.revoke(level, hand);
        rig.site.tick(level);
        helper.assertValueEqual(rig.site.cache.stock().getOrDefault(ItemSpec.of(Items.STONE), 0), 1,
            "the last space accepts one of the two stones");
        helper.assertValueEqual(hand.pack().countItem(Items.STONE), 1, "the excess stays in the pack");
        helper.assertValueEqual(rig.site.cache.stock().values().stream().mapToInt(Integer::intValue).sum(), cap,
            "the order cache holds at most nine stacks");
        rig.site.close();
        helper.succeed();
    }

    private static SiteRig stoneSite(GameTestHelper helper) {
        SiteRig.floor(helper);
        BlockPos first = helper.absolutePos(new BlockPos(5, 1, 5));
        var stone = Blocks.STONE.defaultBlockState();
        var level = helper.getLevel();
        return SiteRig.start(level, SiteRig.target(level, Map.of(first, stone, first.east(), stone)));
    }

    private static Worker atTurn(GameTestHelper helper, Node turn, SimpleContainer pack) {
        var pig = helper.spawn(EntityType.PIG, new BlockPos(5, 1, 7));
        var stance = turn.spec().stances().cells().iterator().next();
        pig.setPos(Vec3.atBottomCenterOf(stance.block(helper.getLevel())));
        return SiteRig.worker(pig, pack);
    }
}
