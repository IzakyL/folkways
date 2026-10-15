package io.github.izakyl.folkways.core.api.vocation;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VocationKitGameTests {
    private static final ResourceLocation BEFORE = id("kit_before");
    private static final ResourceLocation AFTER = id("kit_after");
    private static final ItemSpec BASE = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_pickaxe"));
    private static final ItemSpec ADDED = ItemSpec.of(ResourceLocation.withDefaultNamespace("shears"));

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.kit(BEFORE, ADDED);
        event.kit(BEFORE, BASE);
        event.vocation(new VocationSpec(BEFORE, List.of(), List.of(BASE)));
        event.vocation(new VocationSpec(AFTER, List.of(), List.of(BASE)));
        event.kit(AFTER, ADDED);
        event.kit(AFTER, ADDED);
        // These test-only trades must not add equipment requests to real residents in unrelated tests.
        Participations.register(PersonBody.ID, Stake.vocation(BEFORE), Participation.NONE);
        Participations.register(PersonBody.ID, Stake.vocation(AFTER), Participation.NONE);
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(VocationKitGameTests.class);
    }

    @GameTest(template = "empty")
    public static void pluginToolsCombineWithTheOriginalKitInEitherDeclarationOrder(GameTestHelper helper) {
        helper.assertValueEqual(Vocations.required(BEFORE).kit(), List.of(BASE, ADDED),
            "adding before registration preserves the base kit and removes duplicates");
        helper.assertValueEqual(Vocations.required(AFTER).kit(), List.of(BASE, ADDED),
            "adding after registration gives the same immutable kit");
        var resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        helper.assertTrue(resident.licences().trade(Vocations.required(BEFORE)).isEmpty()
                && resident.licences().trade(Vocations.required(AFTER)).isEmpty(),
            "test trades never change another test's resident equipment");
        resident.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void toolsCannotBeAddedAfterTheDeclarationWindowCloses(GameTestHelper helper) {
        List<ItemSpec> original = Vocations.required(BEFORE).kit();
        boolean refused = false;
        try {
            new Declaring().kit(BEFORE, ItemSpec.of(ResourceLocation.withDefaultNamespace("fishing_rod")));
        } catch (IllegalStateException expected) {
            refused = true;
        }
        helper.assertTrue(refused, "runtime callers cannot change declared equipment");
        helper.assertValueEqual(Vocations.required(BEFORE).kit(), original,
            "the rejected addition leaves the published kit unchanged");
        boolean immutable = false;
        try {
            original.add(BASE);
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        helper.assertTrue(immutable, "readers cannot mutate a published kit");
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways_test", path);
    }
}
