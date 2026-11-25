package io.github.izakyl.folkways.core.colony.charter;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.testing.Purity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CharterPurityGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CharterPurityGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void authorizationIsFreshWhileRosterPersistenceIsExplicit(GameTestHelper helper)
            throws ReflectiveOperationException {
        var server = helper.getLevel().getServer();
        var colony = Colonies.mint(server);
        var installed = Teams.class.getDeclaredField("installed");
        installed.setAccessible(true);
        Object previous = installed.get(null);
        UUID team = UUID.randomUUID();
        UUID oldOwner = UUID.randomUUID();
        UUID nextOwner = UUID.randomUUID();
        var heard = new AtomicReference<>(
            new Teams.Heard(team, Component.literal("team"), nextOwner, List.of(nextOwner)));
        Teams.install(new Teams.Source() {
            public Optional<Teams.Heard> partyOf(ServerPlayer player) {
                return Optional.empty();
            }
            public Optional<Teams.Heard> team(MinecraftServer host, UUID id) {
                return Optional.of(heard.get());
            }
        });
        Charters.keep(colony, Charter.founded("test", oldOwner).keptBy(Optional.of(team)));
        try {
            Purity.assertPure(helper, "live authorization", () -> {
                helper.assertTrue(Charters.member(server, colony, nextOwner), "new member is authorized immediately");
                helper.assertTrue(!Charters.member(server, colony, oldOwner),
                    "removed member loses authority immediately");
                helper.assertTrue(Charters.founder(server, colony, nextOwner), "founder follows the current owner");
                Charters.joined(server, nextOwner);
            });
            helper.assertTrue(Charters.written(colony).founder(oldOwner), "read leaves persisted fallback unchanged");
            Charters.refresh(server, colony);
            helper.assertTrue(Charters.written(colony).founder(nextOwner), "refresh persists the live roster");
            Purity.assertPure(helper, "unchanged refresh", () -> Charters.refresh(server, colony));
        } finally {
            installed.set(null, previous);
            colony.raze();
        }
        helper.succeed();
    }
}
