package io.github.izakyl.folkways.vanilla.create.dispatch;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.SupplySources;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.create.dispatch.PackageNetwork;
import io.github.izakyl.folkways.core.shell.create.dispatch.PackageNetworks;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class DispatchSourceGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DispatchSourceGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void sourcesReadPublishedDepotsWithoutRefreshingTheNetwork(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        PackageNetwork original = PackageNetworks.get();
        try {
            var state = colony.service(DispatchContent.ID, DispatchPresence.class).orElseThrow();
            UUID id = UUID.randomUUID();
            state.take(id);
            ItemSpec item = ItemSpec.of(ResourceLocation.parse("minecraft:bread"));
            helper.assertTrue(state.sources(item).getFirst().reason().equals(Optional.of("not_captured")),
                    "new bindings remain unknown until their normal refresh");
            var supply = List.of(new PackageNetwork.Supply(new ItemStack(Items.BREAD), 40));
            set(state, "seen", new NetworkSnapshot(Map.of(id, new NetworkSnapshot.Reach(supply, false)), Map.of()));
            set(state, "sourceTick", 123L);
            WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
            var shelf = new Depot.Shelf(at, Stances.at(at), Map.of(Goods.specOf(new ItemStack(Items.BREAD)), 5L));
            var tally = new Tally(id, supply, 123L, false, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
            var depot = new Depot(UUID.randomUUID(), new WorkSite.AtBlock(at), tally, List.of(), List.of(shelf),
                    new Eta(), 20);
            set(state, "sourceDepots", Map.of(id, List.of(depot)));
            PackageNetworks.install((PackageNetwork) Proxy.newProxyInstance(PackageNetwork.class.getClassLoader(),
                    new Class<?>[]{PackageNetwork.class}, (proxy, method, args) -> {
                        throw new AssertionError("read refreshed network: " + method.getName());
                    }));
            Purity.assertPure(helper, "published network supplies", () -> {
                var source = SupplySources.of(colony, item).getFirst();
                helper.assertTrue(source.kind().equals("network") && source.count() == 5
                        && source.reason().isEmpty() && source.captureTick() == 123L,
                        "only accessible shelf stock is visible without a manned order route");
            });
            set(state, "sourceDepots", Map.of());
            Purity.assertPure(helper, "unavailable bound network", () -> helper.assertTrue(
                    state.sources(item).getFirst().reason().equals(Optional.of("network_unavailable")),
                    "a binding with no collection route stays invisible"));
        } finally {
            PackageNetworks.install(original);
            colony.raze();
        }
        helper.succeed();
    }

    private static void set(DispatchPresence state, String name, Object value) {
        try {
            var field = DispatchPresence.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(state, value);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }
}
