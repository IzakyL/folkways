package io.github.izakyl.folkways.plugins.fishing;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.effect.Modifier;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FishingPerkGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(FishingPerkGameTests.class); }

    @GameTest(template = "empty")
    public static void schoolsAndDiscoveriesAreIncludedInTheActualCatch(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(at, Blocks.WATER.defaultBlockState(), 3);
        var body = PersonBody.RESIDENT.get().create(level);
        var effects = Effects.of(List.of(
            new Effects.Part("school", Modifier.add(FishingContent.EXTRA_FISH, 1)),
            new Effects.Part("size", Modifier.add(FishingContent.SCHOOL_SIZE, 2)),
            new Effects.Part("treasure", Modifier.add(FishingContent.TREASURE_CHANCE, 1))));
        Worker worker = (Worker) java.lang.reflect.Proxy.newProxyInstance(Worker.class.getClassLoader(),
            new Class<?>[] {Worker.class}, (proxy, method, args) -> switch (method.getName()) {
                case "body" -> body.mob();
                case "held" -> new ItemStack(Items.FISHING_ROD);
                case "effects" -> effects;
                default -> throw new AssertionError(method.getName());
            });
        var node = new CastNode(UUID.randomUUID(), new Spot(WorldPos.of(level, at), Stances.WHEREVER, 20),
            () -> { }, new PastDay());
        boolean fishFound = false;
        for (int i = 0; i < 32 && !fishFound; i++) {
            Outcome result = node.commit(level, worker);
            helper.assertTrue(result instanceof Outcome.Done, "catch succeeds");
            var made = ((Outcome.Done) result).made();
            helper.assertTrue(made.size() >= 2, "guaranteed discovery supplies extra local loot");
            for (ItemStack stack : made) {
                if (WaterLoot.fish(stack)) {
                    helper.assertValueEqual(stack.getCount(), 3, "school adds two fish to the original catch");
                    fishFound = true;
                }
            }
        }
        helper.assertTrue(fishFound, "ordinary fishing produces fish within repeated catches");
        helper.succeed();
    }
}
