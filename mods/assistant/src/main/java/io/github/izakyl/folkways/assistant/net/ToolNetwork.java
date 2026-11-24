package io.github.izakyl.folkways.assistant.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost.RunReply;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = AssistantMod.ID, bus = EventBusSubscriber.Bus.MOD)
public final class ToolNetwork {

    private static final ClientReads READS = new ClientReads();

    private static Consumer<ClientRead> readReceiver = request -> {
    };

    private static Consumer<ToolResponse> receiver = response -> {
    };

    private static Consumer<CalibrationMark> markReceiver = mark -> {
    };

    private ToolNetwork() {
    }

    public static void receivers(Consumer<ToolResponse> installed, Consumer<ClientRead> history) {
        receiver = installed;
        readReceiver = history;
    }

    public static void marks(Consumer<CalibrationMark> installed) {
        markReceiver = installed;
    }

    /** Shows a calibration read to the player who ran it; players without a connection (tests) see nothing. */
    public static void mark(ServerPlayer player, String json) {
        if (player.connection == null || json.length() > CalibrationMark.MAX_BYTES) {
            return;
        }
        player.getServer().execute(() -> {
            if (!player.hasDisconnected()) {
                PacketDistributor.sendToPlayer(player, new CalibrationMark(json));
            }
        });
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(FolkwaysCoreMod.NETWORK_VERSION + "-calibration-marks");
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent logout) -> {
            if (logout.getEntity() instanceof ServerPlayer player) {
                READS.disconnect(player);
            }
        });
        registrar.playToClient(ClientRead.TYPE, ClientRead.CODEC,
                (request, context) -> context.enqueueWork(() -> readReceiver.accept(request)));
        registrar.playToServer(ClientReadReply.TYPE, ClientReadReply.CODEC, (reply, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                context.enqueueWork(() -> READS.receive(player, reply));
            }
        });
        registrar.playToServer(ToolRequest.TYPE, ToolRequest.CODEC, (request, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                context.enqueueWork(() -> handle(player, request));
            }
        });
        registrar.playToServer(ActionRequest.TYPE, ActionRequest.CODEC, (request, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                context.enqueueWork(() -> action(player, request));
            }
        });
        registrar.playToClient(CalibrationMark.TYPE, CalibrationMark.CODEC,
                (mark, context) -> context.enqueueWork(() -> markReceiver.accept(mark)));
        registrar.playToClient(ToolResponse.TYPE, ToolResponse.CODEC,
                (response, context) -> context.enqueueWork(() -> receiver.accept(response)));
    }

    private static void action(ServerPlayer player, ActionRequest request) {
        try {
            ScriptHost.INSTANCE.decide(player, request.revision(), request.accept(), request.raise())
                    .thenApply(RunReply::new)
                    .whenComplete((result, error) -> send(player, request.requestId(),
                            error == null ? result : failure("script_failed")));
        } catch (RuntimeException invalid) {
            send(player, request.requestId(), failure("action_failed"));
        }
    }

    private static void handle(ServerPlayer player, ToolRequest request) {
        try {
            JsonObject view = JsonParser.parseString(request.view()).getAsJsonObject();
            var client = new ScriptHost.Client(view, request.scratch(), request.imageInput(), request.language(),
                    path -> READS.read(player, request.requestId(), path,
                            read -> PacketDistributor.sendToPlayer(player, read)));
            ScriptHost.INSTANCE.run(player, request.script(), client)
                    .whenComplete((result, error) -> send(player, request.requestId(),
                            error == null ? result : failure("script_failed")));
        } catch (RuntimeException invalid) {
            send(player, request.requestId(), failure("invalid_request"));
        }
    }

    private static RunReply failure(String code) {
        return new RunReply(ToolReply.error(code));
    }

    private static void send(ServerPlayer player, UUID request, RunReply result) {
        player.getServer().execute(() -> {
            if (!player.hasDisconnected()) {
                PacketDistributor.sendToPlayer(player, new ToolResponse(request, result));
            }
        });
    }
}
