package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.library.Shelves;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.Templates;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlueprintShelfGameTests {

    private static final String TEMPLATE = "empty";

    // Three wide, two high, one deep: stone, stone, cobblestone along the bottom and a plank over the first.
    private static final ResourceLocation HUT =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "blueprint/test_hut");

    private BlueprintShelfGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BlueprintShelfGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aBlueprintOnTheShelfIsNoPattern(GameTestHelper helper) {
        helper.assertTrue(Templates.blueprints().contains(HUT), "the blueprint is read off its shelf");
        helper.assertTrue(Templates.blueprint(HUT).isPresent(), "and kept as the file it is, to be set down whole");
        helper.assertTrue(Patterns.find(HUT).isEmpty(), "it is not offered as a pattern");
        helper.assertTrue(!Patterns.sources().containsKey(HUT), "nor sent as one");
        helper.assertTrue(Shelves.stock(Templates.BLUEPRINTS).entries().containsKey(HUT),
            "the blueprint shelf lists it; actual=" + Shelves.stock(Templates.BLUEPRINTS));
        helper.assertTrue(!Shelves.stock(Patterns.SHELF).entries().containsKey(HUT),
            "the pattern shelf lists only patterns");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aShelvedBlueprintReadsAsItsBlocks(GameTestHelper helper) {
        Blueprint hut = read(helper);
        helper.assertValueEqual(counts(hut), Map.of(
            "minecraft:stone", 2, "minecraft:cobblestone", 1, "minecraft:oak_planks", 1),
            "every block of the blueprint and nothing else");
        helper.assertValueEqual(span(hut), List.of(3, 2, 1), "three wide, two high, one deep");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aTurnedBlueprintStillStandsFromItsCorner(GameTestHelper helper) {
        Blueprint turned = read(helper).transformed(Rotation.CLOCKWISE_90, Mirror.NONE)
            .orElseThrow(() -> new AssertionError("the hut does not turn"));
        helper.assertValueEqual(span(turned), List.of(1, 2, 3), "a quarter turn lays it one wide and three deep");
        helper.assertValueEqual(turned.blocks().stream().map(BlueprintBlock::offset).reduce(BlockPos::min)
            .orElseThrow(), BlockPos.ZERO, "and its lowest corner is where it is set down");
        helper.succeed();
    }

    private static Blueprint read(GameTestHelper helper) {
        CompoundTag file = Templates.blueprint(HUT).orElseThrow(() -> new AssertionError("the test hut is not shelved"));
        return StructureNbt.toBlueprint(file, helper.getLevel().registryAccess(), UUID.randomUUID(), "test_hut", 0L,
                StructureNbt.AirMeaning.UNCONSTRAINED, Integer.MAX_VALUE)
            .orElseThrow(() -> new AssertionError("the test hut does not read"));
    }

    private static List<Integer> span(Blueprint blueprint) {
        BlockPos low = blueprint.blocks().stream().map(BlueprintBlock::offset).reduce(BlockPos::min).orElseThrow();
        BlockPos high = blueprint.blocks().stream().map(BlueprintBlock::offset).reduce(BlockPos::max).orElseThrow();
        return List.of(high.getX() - low.getX() + 1, high.getY() - low.getY() + 1, high.getZ() - low.getZ() + 1);
    }

    private static Map<String, Integer> counts(Blueprint blueprint) {
        Map<String, Integer> counts = new TreeMap<>();
        for (BlueprintBlock block : blueprint.blocks()) {
            BlockState state = block.state();
            counts.merge(state.getBlock().builtInRegistryHolder().key().location().toString(), 1, Integer::sum);
        }
        return counts;
    }
}
