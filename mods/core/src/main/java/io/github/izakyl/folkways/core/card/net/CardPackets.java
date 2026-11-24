package io.github.izakyl.folkways.core.card.net;

import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.card.client.ClientCards;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
public final class CardPackets {
    private static final Map<ServerPlayer, Long> PRESSES = new WeakHashMap<>();

    private CardPackets() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(FolkwaysCoreMod.NETWORK_VERSION)
            .playToServer(CardWatch.TYPE, CardWatch.STREAM_CODEC, (packet, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    CardSync.watch(player, packet);
                }
            }))
            .playToServer(CardPress.TYPE, CardPress.STREAM_CODEC, (packet, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    PacketDistributor.sendToPlayer(player, press(player, packet));
                }
            }))
            .playToClient(CardStatus.TYPE, CardStatus.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> ClientCards.accept(packet)))
            .playToClient(CardReply.TYPE, CardReply.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> ClientCards.accept(packet)))
            .playToClient(CardOpen.TYPE, CardOpen.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> ClientCards.accept(packet)));
    }

    static CardReply press(ServerPlayer player, CardPress packet) {
        long last = PRESSES.getOrDefault(player, -1L);
        if (packet.request() <= last) {
            return new CardReply(packet.request(), packet.facility(), Optional.of(new Notice("stale", List.of())));
        }
        PRESSES.put(player, packet.request());
        var colony = CurrentColony.of(player).filter(held -> held.id().equals(packet.colony())).orElse(null);
        if (colony == null) {
            return new CardReply(packet.request(), packet.facility(), Optional.of(new Notice("missing", List.of())));
        }
        var operating = Operations.find(colony, packet.facility()).orElse(null);
        if (operating == null) {
            return new CardReply(packet.request(), packet.facility(), Optional.of(new Notice("missing", List.of())));
        }
        return new CardReply(packet.request(), packet.facility(), CardOrders.apply(player, operating, packet.press()));
    }
}
