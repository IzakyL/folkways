package io.github.izakyl.folkways.assistant.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.jpi.agent.AgentEvent;
import io.github.izakyl.jpi.ai.AssistantMessage;
import io.github.izakyl.jpi.ai.Subscription;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

final class AssistantTranscript {

    private final List<Component> entries = new ArrayList<>();

    private final List<String> roles = new ArrayList<>();

    private final Set<Runnable> listeners = new LinkedHashSet<>();

    private final Map<Component, String> scripts = new IdentityHashMap<>();

    private final Map<Component, Component> results = new IdentityHashMap<>();

    private final Map<String, Component> calls = new HashMap<>();

    private int streaming = -1;

    private final Runnable markDirty;
    private final Consumer<Component> narrate;

    AssistantTranscript(Runnable markDirty, Consumer<Component> narrate) {
        this.markDirty = markDirty;
        this.narrate = narrate;
    }

    void clear() {
        endStream();
        entries.clear();
        roles.clear();
        scripts.clear();
        results.clear();
        calls.clear();
    }

    void endStream() {
        streaming = -1;
    }

    void restore(List<Component> restored, List<String> restoredRoles, Map<Component, String> restoredScripts,
            Map<Component, Component> restoredResults) {
        entries.addAll(restored);
        roles.addAll(restoredRoles);
        scripts.putAll(restoredScripts);
        results.putAll(restoredResults);
    }

    List<Component> entries() {
        return List.copyOf(entries);
    }

    Subscription subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void note(Component text) {
        note(text, "notice");
    }

    String role(int index) {
        return roles.get(index);
    }

    void note(Component text, String role) {
        entries.add(text);
        roles.add(role);
        changed();
    }

    private void changed() {
        markDirty.run();
        notifyViews();
    }

    void notifyViews() {
        List.copyOf(listeners).forEach(Runnable::run);
    }

    void receive(AgentEvent event) {
        if (event instanceof AgentEvent.MessageStart start && start.message() instanceof AssistantMessage) {
            streaming = entries.size();
            note(Component.empty(), "assistant");
        } else if (event instanceof AgentEvent.MessageUpdate update && streaming >= 0) {
            entries.set(streaming, Component.literal(update.message().text()));
            changed();
        } else if (event instanceof AgentEvent.MessageEnd end && end.message() instanceof AssistantMessage message) {
            if (streaming >= 0) {
                entries.set(streaming, Component.literal(message.text()));
                streaming = -1;
                changed();
            }
            if (message.errorMessage() != null) {
                note(Component.translatable("folkways.assistant.request_error"));
            }
            if (message.toolCalls().isEmpty() && !message.text().isBlank()) {
                narrate.accept(Component.literal(message.text()));
            }
        } else if (event instanceof AgentEvent.ToolExecutionStart start) {
            Component line = toolLine();
            calls.put(start.toolCall().id(), line);
            JsonObject arguments = start.toolCall().arguments();
            if (start.toolCall().name().equals("run") && arguments.has("script")
                    && arguments.get("script").isJsonPrimitive()
                    && arguments.getAsJsonPrimitive("script").isString()) {
                scripts.put(line, arguments.get("script").getAsString());
            }
            note(line, "tool");
        } else if (event instanceof AgentEvent.ToolExecutionEnd end) {
            Component line = calls.remove(end.toolCall().id());
            if (line != null && end.toolCall().name().equals("run")) {
                Component summary = Component.translatable("folkways.assistant.script_error");
                try {
                    JsonObject data = end.result().details().getAsJsonObject();
                    if (data.has("error") || data.has("truncated")) {
                        summary = scriptError(data);
                    } else if (data.has("declined")) {
                        summary = Component.translatable("folkways.assistant.script_rejected");
                    } else if (!data.has("error") && !data.has("truncated")) {
                        summary = Component.translatable("folkways.assistant.script_result",
                                data.has("prints") ? data.getAsJsonArray("prints").size() : 0,
                                data.has("applied") ? data.getAsJsonArray("applied").size() : 0);
                    }
                    if (data.has("history_reads")) {
                        summary = summary.copy().append(" · ").append(Component.translatable(
                                "folkways.assistant.history_result", data.get("history_reads").getAsInt()));
                    }
                    if (data.has("captures")) {
                        for (JsonElement value : data.getAsJsonArray("captures")) {
                            JsonObject shot = value.getAsJsonObject();
                            Component captured = shot.has("error")
                                    ? Component.translatable("folkways.assistant.screenshot_failed")
                                    : Component.translatable("folkways.assistant.screenshot_result",
                                            shot.get("width").getAsInt(), shot.get("height").getAsInt());
                            summary = summary.copy().append(" · ").append(captured);
                        }
                    }
                } catch (RuntimeException malformed) {
                    // A failed transport may return a diagnostic rather than JSON.
                }
                results.put(line, summary);
                changed();
            }
        }
    }

    private static Component scriptError(JsonObject data) {
        String code = data.has("error") ? data.get("error").getAsString() : "result_size_cap";
        String kind = switch (code) {
            case "syntax_error", "eval_error", "step_limit", "memory_limit", "timeout", "call_limit", "too_many_intents",
                    "result_size_cap", "not_member", "rate_limited", "already_running", "cancelled",
                    "invalid_arguments", "script_size_cap", "invalid_result", "busy",
                    "unknown_state_path", "state_read_only", "state_delete_unsupported", "unknown_run",
                    "scratch_size_cap", "image_input_disabled", "capture_limit", "client_read_disconnected" -> code;
            default -> "unknown";
        };
        Component label = Component.translatable("folkways.assistant.script_error." + kind);
        if (data.has("line") && data.get("line").getAsInt() > 0) {
            label = Component.translatable("folkways.assistant.script_error.line", label, data.get("line").getAsInt());
        }
        if (data.has("message") && !data.get("message").isJsonNull()) {
            String message = data.get("message").getAsString().replaceAll("\\s+", " ").strip();
            if (message.length() > 160) {
                message = message.substring(0, 157) + "...";
            }
            if (!message.isEmpty()) {
                label = Component.translatable("folkways.assistant.script_error.message", label, message);
            }
        }
        return label;
    }

    String script(int index) {
        return scripts.get(entries.get(index));
    }

    Component result(int index) {
        return results.get(entries.get(index));
    }

    static Component toolLine() {
        return Component.translatable("folkways.assistant.tool.run");
    }

}
