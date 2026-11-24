import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

export interface ScriptQuestion {
  question: string;
  runs: {
    id: string;
    script: string | null;
    reply: {
      ok: boolean;
      error: string | null;
      message: string | null;
      line: number | null;
      column: number | null;
      preview?: string;
    } | null;
  }[];
}

// Read only conversation scripts and tool replies, never settings, model options or credentials.
export const ASSISTANT_SCRIPT_TRANSCRIPT = defineTask<void, string>({
  name: "folkways.assistant.script-transcript", side: "client",
  source: javaTask({ imports: [
    "com.google.gson.JsonArray", "com.google.gson.JsonObject", "com.google.gson.JsonParser",
    "io.github.izakyl.jpi.agent.Agent", "io.github.izakyl.jpi.ai.AssistantMessage",
    "io.github.izakyl.jpi.ai.ContentBlock", "io.github.izakyl.jpi.ai.ToolResultMessage",
    "io.github.izakyl.jpi.ai.UserMessage", "io.github.izakyl.jpi.ai.Message",
  ], body: `
var type = Class.forName("io.github.izakyl.folkways.assistant.client.AssistantSession");
var current = type.getDeclaredMethod("current");
current.setAccessible(true);
var agentField = type.getDeclaredField("agent");
agentField.setAccessible(true);
var session = current.invoke(null);
var agent = (Agent) agentField.get(session);
List<Message> messages;
if (agent == null) {
    var history = type.getDeclaredField("history");
    history.setAccessible(true);
    messages = (List<Message>) history.get(session);
} else {
    messages = agent.state().messages();
}
var questions = new JsonArray();
var calls = new java.util.HashMap<String, JsonObject>();
JsonArray runs = null;
for (var message : messages) {
    if (message instanceof UserMessage user) {
        var question = new JsonObject();
        String text = user.content().stream().filter(ContentBlock.Text.class::isInstance)
                .map(ContentBlock.Text.class::cast).map(ContentBlock.Text::text)
                .collect(java.util.stream.Collectors.joining());
        question.addProperty("question", text);
        runs = new JsonArray();
        question.add("runs", runs);
        questions.add(question);
    } else if (message instanceof AssistantMessage assistant && runs != null) {
        for (var call : assistant.toolCalls()) {
            if (call.name().equals("run")) {
                var run = new JsonObject();
                run.addProperty("id", call.id());
                run.add("script", call.arguments().get("script"));
                run.add("reply", null);
                runs.add(run);
                calls.put(call.id(), run);
            }
        }
    } else if (message instanceof ToolResultMessage result && calls.containsKey(result.toolCallId())) {
        String text = result.content().stream().filter(ContentBlock.Text.class::isInstance)
                .map(ContentBlock.Text.class::cast).map(ContentBlock.Text::text)
                .collect(java.util.stream.Collectors.joining());
        var reply = new JsonObject();
        try {
            var data = JsonParser.parseString(text).getAsJsonObject();
            boolean ok = !result.isError() && !data.has("error") && !data.has("truncated");
            reply.addProperty("ok", ok);
            for (String key : List.of("error", "message", "line", "column")) {
                reply.add(key, data.get(key));
            }
            if (ok) {
                var output = new JsonObject();
                for (String key : List.of("prints", "result", "applied", "declined")) {
                    if (data.has(key)) {
                        output.add(key, data.get(key));
                    }
                }
                String preview = output.toString();
                reply.addProperty("preview", preview.length() > 800 ? preview.substring(0, 797) + "..." : preview);
            }
        } catch (RuntimeException malformed) {
            reply.addProperty("ok", false);
            reply.addProperty("error", "unstructured_reply");
            reply.addProperty("message", text);
            reply.add("line", null);
            reply.add("column", null);
        }
        calls.get(result.toolCallId()).add("reply", reply);
    }
}
return questions.toString();
` }),
});
