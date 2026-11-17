package io.github.izakyl.folkways.core.rts.combat.net;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
public final class UnitPackets {
    private static final Map<ServerPlayer, Long> REQUESTS = new WeakHashMap<>();

    private UnitPackets() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(FolkwaysCoreMod.NETWORK_VERSION)
            .playToServer(UnitRequest.TYPE, UnitRequest.STREAM_CODEC, (packet, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    long last = REQUESTS.getOrDefault(player, -1L);
                    if (packet.request() <= last) {
                        PacketDistributor.sendToPlayer(player, new UnitReply(packet.request(), packet.colony(),
                            Vehicles.Result.refused("stale"), Optional.empty()));
                    } else {
                        REQUESTS.put(player, packet.request());
                        PacketDistributor.sendToPlayer(player, UnitOrders.handle(player, packet));
                    }
                }
            }))
            .playToClient(UnitReply.TYPE, UnitReply.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> ClientUnits.accept(packet)));
    }
}
