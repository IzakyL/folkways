package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CombatIntentGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CombatIntentGameTests.class); }

    @GameTest(template = "empty")
    public static void routePacketPreservesAPrefixAndItsExplicitCycleStart(GameTestHelper helper) {
        UUID colony = UUID.randomUUID();
        List<UUID> residents = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<WorldPos> points = List.of(WorldPos.of(helper.getLevel(), BlockPos.ZERO),
            WorldPos.of(helper.getLevel(), BlockPos.ZERO.east()), WorldPos.of(helper.getLevel(), BlockPos.ZERO.south()));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            CombatSelectionPacket sent = new CombatSelectionPacket(colony, residents, points, 1);
            CombatSelectionPacket.STREAM_CODEC.encode(buffer, sent);
            helper.assertValueEqual(CombatSelectionPacket.STREAM_CODEC.decode(buffer), sent,
                "the loop's start index survives packet transport");
            helper.assertTrue(SelectionOrders.validCycle(3, 1), "a prefix followed by a two-point loop is accepted");
            helper.assertFalse(SelectionOrders.validCycle(3, 2), "a one-point loop is rejected");
            helper.assertFalse(SelectionOrders.validCycle(3, -1), "an unclosed multi-point route is rejected");
            helper.assertFalse(SelectionOrders.validCycle(1, -2), "unknown negative modes are rejected");
            helper.assertTrue(SelectionOrders.validCycle(1, -1), "a single move stays compatible");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void attackPacketTransportsOnlyTheColonyMembersAndTargetIdentity(GameTestHelper helper) {
        CombatAttackPacket sent = new CombatAttackPacket(UUID.randomUUID(), List.of(UUID.randomUUID()), UUID.randomUUID());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            CombatAttackPacket.STREAM_CODEC.encode(buffer, sent);
            helper.assertValueEqual(CombatAttackPacket.STREAM_CODEC.decode(buffer), sent,
                "the server receives identifiers and must resolve their current entities itself");
            helper.assertValueEqual(buffer.readableBytes(), 0, "the complete bounded intent is consumed");
        } finally { buffer.release(); }
        helper.succeed();
    }
}
