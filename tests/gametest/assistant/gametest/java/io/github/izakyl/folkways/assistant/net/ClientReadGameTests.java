package io.github.izakyl.folkways.assistant.net;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class ClientReadGameTests {

    private ClientReadGameTests() {
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void historyPageRoundTripsAndIgnoresAnotherPlayer(GameTestHelper helper) {
        ClientReads reads = new ClientReads();
        ServerPlayer owner = player(helper);
        ServerPlayer stranger = player(helper);
        UUID runId = UUID.randomUUID();
        String path = "client/history/" + UUID.randomUUID();
        var check = run(owner, reads, runId, path, outbound -> {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                ClientRead.CODEC.encode(buffer, outbound);
                ClientRead request = ClientRead.CODEC.decode(buffer);
                helper.assertTrue(request.runId().equals(runId) && request.path().equals(path),
                        "the server addresses the history read to the active run");
                JsonObject page = new JsonObject();
                page.addProperty("title", "Earlier conversation");
                reads.receive(stranger, ClientReadReply.failure(request.requestId(), "wrong player"));
                ClientReadReply.CODEC.encode(buffer, ClientReadReply.success(request.requestId(), page));
                reads.receive(owner, ClientReadReply.CODEC.decode(buffer));
                reads.receive(owner, ClientReadReply.failure(request.requestId(), "duplicate reply"));
            } finally {
                buffer.release();
            }
        }).thenAccept(run -> helper.assertTrue(run.historyReads() == 1
                && run.reply().data().get("result").getAsString().equals("Earlier conversation"),
                "the worker resumed with the matching player's decoded reply"));
        AsyncWait.succeedWhen(helper, check, () -> { }, () -> reads.disconnect(owner));
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void historyReadTimesOutAndFailsOnDisconnect(GameTestHelper helper) {
        ClientReads reads = new ClientReads();
        ServerPlayer owner = player(helper);
        UUID runId = UUID.randomUUID();
        String path = "client/history/" + UUID.randomUUID();
        AtomicReference<ClientRead> timedOut = new AtomicReference<>();
        var check = run(owner, reads, runId, path, timedOut::set).thenCompose(timeout -> {
            helper.assertTrue(timeout.reply().data().get("message").getAsString().contains("timed out after 5 seconds"),
                    "missing replies fail the read with the per-fetch timeout");
            reads.receive(owner, ClientReadReply.failure(timedOut.get().requestId(), "late reply"));
            return run(owner, reads, runId, path, request -> reads.disconnect(owner));
        }).thenAccept(disconnected -> helper.assertTrue(
                disconnected.reply().data().get("message").getAsString().contains("Player disconnected"),
                "disconnect completes a pending fetch with a diagnostic"));
        AsyncWait.succeedWhen(helper, check, () -> { }, () -> reads.disconnect(owner));
    }

    private static ServerPlayer player(GameTestHelper helper) {
        var level = helper.getLevel();
        return new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "history-test"),
                ClientInformation.createDefault()) {
            @Override
            public boolean hasDisconnected() {
                return false;
            }
        };
    }

    private static CompletableFuture<ScriptHost.RunReply> run(ServerPlayer player, ClientReads reads,
            UUID runId, String path, Consumer<ClientRead> send) {
        var client = new ScriptHost.Client(new JsonObject(), "{}", false, "en_us",
                reading -> reads.read(player, runId, reading, send));
        return new ScriptHost().run(player, "result = get(\"" + path + "\")[\"title\"]", client);
    }
}
