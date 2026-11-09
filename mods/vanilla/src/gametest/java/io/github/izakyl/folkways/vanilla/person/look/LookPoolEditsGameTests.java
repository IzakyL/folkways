package io.github.izakyl.folkways.vanilla.person.look;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class LookPoolEditsGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LookPoolEditsGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void thousandsOfLooksStayBelowTheServerboundLimit(GameTestHelper helper) {
        List<LookPoolEdits.Edit> edits = new ArrayList<>();
        for (int at = 0; at < 10_000; at++) {
            edits.add(new LookPoolEdits.Edit(ResourceLocation.parse("test:model/collection/skin_" + at), at % 2 == 0));
        }
        List<CustomPacketPayload> packets = new ArrayList<>();
        LookPoolEdits.send(edits, packets::add);
        helper.assertTrue(packets.size() > 1, "the selection does not fit in a single string or packet");
        LookPoolEdits.Pending pending = new LookPoolEdits.Pending(packets.size(), null);
        for (int at = 0; at < packets.size(); at++) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                LookPoolEdits packet = (LookPoolEdits) packets.get(at);
                buffer.writeResourceLocation(packet.type().id());
                LookPoolEdits.CODEC.encode(buffer, packet);
                helper.assertTrue(buffer.readableBytes() < 32_767, "the payload, including its id, fits C2S");
                buffer.readResourceLocation();
                helper.assertTrue(pending.take(LookPoolEdits.CODEC.decode(buffer)), "each slice is read in order");
                helper.assertTrue(pending.whole().isPresent() == (at == packets.size() - 1),
                    "the edit becomes available only when the last slice arrives");
            } finally {
                buffer.release();
            }
        }
        helper.assertValueEqual(pending.whole().orElseThrow(), edits, "all ten thousand edits arrive unchanged");
        var bad = new LookPoolEdits.Pending(2, null);
        helper.assertFalse(bad.take(new LookPoolEdits(1, 2, new byte[0])), "an out-of-order slice is refused");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void editsPreserveUnseenLooksAndValidateEveryId(GameTestHelper helper) {
        ResourceLocation unseen = ResourceLocation.parse("missing:model/skin");
        ResourceLocation changed = ResourceLocation.parse("test:model/skin");
        ResourceLocation invalid = ResourceLocation.parse("invalid:model/skin");
        for (LookPool before : List.of(LookPool.UNTOUCHED.with(unseen, true),
                new LookPool(Optional.of(Set.of(unseen, changed)), Set.of(), Set.of()))) {
            LookPool after = LookPoolEdits.apply(before, List.of(new LookPoolEdits.Edit(changed, false),
                new LookPoolEdits.Edit(invalid, false)), Set.of(changed));
            helper.assertTrue(after.allows(unseen) == before.allows(unseen), "an unseen look stays as it was");
            helper.assertTrue(after.only().isPresent() == before.only().isPresent(), "an edit keeps default semantics");
            helper.assertFalse(after.allows(changed), "a validated look is changed");
            helper.assertTrue(after.allows(invalid) == before.allows(invalid), "an unoffered id is ignored");
        }
        helper.succeed();
    }
}
