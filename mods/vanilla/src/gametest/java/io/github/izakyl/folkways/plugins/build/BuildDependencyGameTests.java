package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.RegisterEvent;
import static io.github.izakyl.folkways.plugins.build.SiteRig.floor;
import static io.github.izakyl.folkways.plugins.build.SiteRig.target;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuildDependencyGameTests {

    private static Block eastSupport;
    private static Block westSupport;

    @SubscribeEvent
    public static void registerBlocks(RegisterEvent event) {
        event.register(Registries.BLOCK, registry -> {
            eastSupport = new MutualSupportBlock(Direction.EAST);
            westSupport = new MutualSupportBlock(Direction.WEST);
            registry.register(id("east"), eastSupport);
            registry.register(id("west"), westSupport);
        });
        event.register(Registries.ITEM, registry -> {
            registry.register(id("east"), new BlockItem(eastSupport, new Item.Properties()));
            registry.register(id("west"), new BlockItem(westSupport, new Item.Properties()));
        });
    }

    private static ResourceLocation id(String side) {
        return ResourceLocation.fromNamespaceAndPath(FolkwaysVanillaMod.ID, "test_mutual_support_" + side);
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BuildDependencyGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void refusingOneSolidBlockDoesNotHoldTheRestOfItsCourse(GameTestHelper helper) {
        floor(helper);
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (int x = 5; x <= 15; x++) {
            cells.put(at(helper, x, 1, 8), Blocks.STONE.defaultBlockState());
        }
        SiteRig rig = SiteRig.start(helper.getLevel(), target(helper.getLevel(), cells));
        BlockPos failed = cells.keySet().iterator().next();
        var node = rig.action(failed);
        for (int attempt = 0; attempt < BuildSite.MOST_TRIES; attempt++) {
            rig.end(node, new Ending.Failed(rig.pieceAt(failed).id(), BuildRefusal.NO_WAY_TO_BUILD));
            rig.site.tick(helper.getLevel());
        }
        helper.assertValueEqual(rig.site.refusals().get(failed), BuildRefusal.NO_WAY_TO_BUILD,
            "the failed location is reported");
        helper.assertTrue(cells.keySet().stream().filter(cell -> !cell.equals(failed))
            .allMatch(cell -> rig.at(cell).isPresent() && rig.free(cell)),
            "every other solid location remains independently available");
        rig.site.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void mutuallySupportingAddonBlocksProduceAnExecutableAcyclicPlan(GameTestHelper helper) {
        floor(helper);
        BlockPos left = at(helper, 8, 1, 8);
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        cells.put(left, eastSupport.defaultBlockState());
        cells.put(left.east(), westSupport.defaultBlockState());
        Layering.Plan plan = Layering.of(helper.getLevel(), target(helper.getLevel(), cells));
        helper.assertTrue(plan.refused().isEmpty() && plan.works().size() == 2,
            "the complete addon structure is valid and has both pieces");
        helper.assertTrue(plan.broken() > 0, "the mutually dependent fixture exercises cycle resolution");
        helper.assertValueEqual(plan.after().values().stream().mapToInt(Set::size).sum(), 1,
            "only the forward support edge remains after breaking the cycle");
        Map<Integer, Long> keys = new LinkedHashMap<>();
        plan.works().forEach(work -> keys.put(work.id(), work.key()));
        helper.assertTrue(plan.after().entrySet().stream().allMatch(entry -> entry.getValue().stream()
            .allMatch(before -> keys.get(before) < keys.get(entry.getKey()))),
            "every executable dependency follows the resolved order");
        SiteRig rig = SiteRig.start(helper.getLevel(), target(helper.getLevel(), cells));
        helper.assertTrue(rig.site.pieces().size() == 2
            && rig.site.pieces().stream().anyMatch(piece -> rig.site.before(piece).isEmpty()),
            "the site can release both pieces and begin one without deadlocking");
        rig.site.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void supportAndContainmentRemainPhysicalPrerequisites(GameTestHelper helper) {
        floor(helper);
        BlockPos support = at(helper, 6, 1, 6);
        BlockPos sand = support.above();
        BlockPos water = at(helper, 12, 1, 12);
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        cells.put(support, Blocks.STONE.defaultBlockState());
        cells.put(sand, Blocks.SAND.defaultBlockState());
        cells.put(water, Blocks.WATER.defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL) {
            cells.put(water.relative(side), Blocks.STONE.defaultBlockState());
        }
        Layering.Plan plan = Layering.of(helper.getLevel(), target(helper.getLevel(), cells));
        Map<BlockPos, Integer> ids = new LinkedHashMap<>();
        plan.works().forEach(work -> ids.put(work.focus(), work.id()));
        helper.assertTrue(plan.after().getOrDefault(ids.get(sand), Set.of()).contains(ids.get(support)),
            "falling sand waits for its support");
        for (Direction side : Direction.Plane.HORIZONTAL) {
            helper.assertTrue(plan.after().getOrDefault(ids.get(water), Set.of())
                .contains(ids.get(water.relative(side))),
                "water waits for its containing wall on " + side);
        }
        helper.succeed();
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** Models an addon whose two separately placed parts each expect the other to exist. */
    private static final class MutualSupportBlock extends Block {
        private final Direction toward;

        private MutualSupportBlock(Direction toward) {
            super(BlockBehaviour.Properties.of());
            this.toward = toward;
        }

        @Override
        protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
            return level.getBlockState(pos.relative(toward)).getBlock() instanceof MutualSupportBlock;
        }
    }
}
