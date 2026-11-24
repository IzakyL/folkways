package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.testing.Purity;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RegistryPurityGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RegistryPurityGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void loadedColoniesSettleAtLifecycleAndLookupsOnlyRead(GameTestHelper helper)
            throws ReflectiveOperationException {
        var server = helper.getLevel().getServer();
        var original = ColonyRegistry.get(server);
        var colony = Colonies.mint(server);
        CompoundTag saved = new CompoundTag();
        var entries = new ListTag();
        entries.add(original.find(colony.id()).orElseThrow().save(new CompoundTag()));
        saved.put("colonies", entries);
        saved.putInt("schemaVersion", io.github.izakyl.folkways.core.base.persist.upgrade.DataVersion.CURRENT);
        saved.put("razed", new ListTag());
        colony.raze();
        var loaded = ColonyRegistry.load(saved, server.registryAccess());
        var data = loaded.find(colony.id()).orElseThrow();
        var field = server.overworld().getDataStorage().getClass().getDeclaredField("cache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (Map<String, SavedData>) field.get(server.overworld().getDataStorage());
        cache.put("folkways_colonies", loaded);
        try {
            ColonyRegistry.initialize(server);
            helper.assertTrue(!data.works().settled(), "loading binds data without settling runtimes");
            Purity.assertPure(helper, "unsettled registry lookup", () -> {
                Colonies.of(server, colony.id());
                Colonies.all(server);
                Colonies.razed(server, colony.id());
            });
            helper.assertTrue(!data.works().settled(), "queries do not settle runtime");
            ColonyRuntime.opened(server);
            helper.assertTrue(data.works().settled(), "server lifecycle settles loaded colonies");
            Purity.assertPure(helper, "settled registry lookup", () -> Colonies.all(server));
        } finally {
            loaded.raze(server, colony.id());
            cache.put("folkways_colonies", original);
            ColonyRegistry.initialize(server);
        }
        helper.succeed();
    }
}
