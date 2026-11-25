package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ActionSseServer implements AutoCloseable {

    final List<JsonObject> requests = new CopyOnWriteArrayList<>();

    private final ExecutorService workers = Executors.newCachedThreadPool();

    private final HttpServer server;

    ActionSseServer(String script) throws IOException {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("script", script);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject());
                JsonObject delta = new JsonObject();
                if (requests.size() == 1) {
                    var function = new JsonObject();
                    function.addProperty("name", "run");
                    function.addProperty("arguments", arguments.toString());
                    var call = new JsonObject();
                    call.addProperty("index", 0);
                    call.addProperty("id", "action1");
                    call.addProperty("type", "function");
                    call.add("function", function);
                    var calls = new JsonArray();
                    calls.add(call);
                    delta.add("tool_calls", calls);
                } else {
                    delta.addProperty("content", "Done.");
                }
                String reason = requests.size() == 1 ? "tool_calls" : "stop";
                String body = "data: {\"choices\":[{\"index\":0,\"delta\":" + delta
                        + ",\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""
                        + reason + "\"}]}\n\ndata: [DONE]\n\n";
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
