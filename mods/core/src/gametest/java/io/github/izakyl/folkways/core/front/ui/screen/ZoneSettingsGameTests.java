package io.github.izakyl.folkways.core.front.ui.screen;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ZoneAction;
import io.github.izakyl.folkways.core.front.api.notice.Attempt;
import io.github.izakyl.folkways.core.front.engine.charter.Charter;
import io.github.izakyl.folkways.core.front.engine.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.front.engine.colony.ZoneDrafts;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class ZoneSettingsGameTests {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "test/panel_action");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ZoneSettingsGameTests.class);
    }

    @SubscribeEvent
    public static void enroll(Registering event) {
        ZoneAction action = new ZoneAction("run", "folkways.action.confirm", (level, colony, zone) -> {
            CompoundTag tag = colony.kept(ID).copy();
            tag.putInt("calls", tag.getInt("calls") + 1);
            tag.putUUID("zone", zone);
            colony.keep(ID, tag);
            return Attempt.went();
        });
        event.enrollment(ID, new Enrollment(List.of(new Delegation(ID, new Shape.Volume(8), Schema.none(),
            ResourceLocation.withDefaultNamespace("paper"), List.of(action))), Schema.none(), List.of()));
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void panelActionsRecheckSelectionAndKeeperAuthority(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "zone_keeper"));
        Charters.keep(colony, Charter.founded("Panel action", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        try {
            BlockPos at = helper.absolutePos(BlockPos.ZERO);
            var first = ZoneDrafts.zone(level, colony, ID, at, at, ColonySettings.empty()).orElseThrow();
            var second = ZoneDrafts.zone(level, colony, ID, at, at, ColonySettings.empty()).orElseThrow();
            var panel = ZoneSettings.create();
            panel.hold(first.id());
            CompoundTag reading = (CompoundTag) panel.reading(player);
            helper.assertTrue(reading.getBoolean("open"), "ordinary settings opens on a held zone");
            helper.assertValueEqual(reading.getList("actions", Tag.TAG_COMPOUND).size(), 1,
                "the delegation contributes its panel action");
            panel.act(player, "1|" + first.id() + "|run");
            helper.assertValueEqual(colony.kept(ID).getInt("calls"), 1, "the keeper can run the action");
            helper.assertValueEqual(colony.kept(ID).getUUID("zone"), first.id(), "the handler gets the zone id");
            panel.hold(second.id());
            panel.act(player, "2|" + first.id() + "|run");
            panel.act(player, "3|" + second.id() + "|unknown");
            helper.assertValueEqual(colony.kept(ID).getInt("calls"), 1,
                "stale selections and undeclared actions cannot execute");
            Charters.keep(colony, Charter.founded("Revoked", UUID.randomUUID()));
            panel.act(player, "4|" + second.id() + "|run");
            helper.assertValueEqual(colony.kept(ID).getInt("calls"), 1, "revoked keeper authority cannot execute");
            helper.assertFalse(((CompoundTag) panel.reading(player)).getBoolean("open"),
                "the panel closes when the keeper loses authority");
        } finally {
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.empty());
            colony.raze();
        }
        helper.succeed();
    }
}
