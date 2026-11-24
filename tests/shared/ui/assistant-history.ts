import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

// Presentation-only history makes the restored-panel layout regression independent of model verbosity.
export const APPEND_ASSISTANT_HISTORY = defineTask<void, boolean>({
  name: "folkways.assistant.history-fixture", side: "client",
  source: javaTask({ imports: ["net.minecraft.network.chat.Component"], body: `
var type = Class.forName("io.github.izakyl.folkways.assistant.client.AssistantSession");
var current = type.getDeclaredMethod("current");
current.setAccessible(true);
var note = type.getDeclaredMethod("note", Component.class);
note.setAccessible(true);
note.invoke(current.invoke(null), Component.literal(("Earlier conversation line" + Character.toString(10)).repeat(80)));
return true;
` }),
});

// Remove only the synthetic fixture, leaving the player's transcript and pending decision intact.
export const REMOVE_ASSISTANT_HISTORY = defineTask<void, boolean>({
  name: "folkways.assistant.history-fixture-remove", side: "client",
  source: javaTask({ imports: ["net.minecraft.network.chat.Component"], body: `
var type = Class.forName("io.github.izakyl.folkways.assistant.client.AssistantSession");
var current = type.getDeclaredMethod("current");
current.setAccessible(true);
var transcriptField = type.getDeclaredField("transcript");
transcriptField.setAccessible(true);
var transcript = transcriptField.get(current.invoke(null));
var transcriptType = transcript.getClass();
var entriesField = transcriptType.getDeclaredField("entries");
entriesField.setAccessible(true);
var rolesField = transcriptType.getDeclaredField("roles");
rolesField.setAccessible(true);
var entries = (List<Component>) entriesField.get(transcript);
var roles = (List<String>) rolesField.get(transcript);
for (int index = entries.size() - 1; index >= 0; index--) {
    if (entries.get(index).getString().startsWith("Earlier conversation line")) {
        entries.remove(index);
        roles.remove(index);
    }
}
var changed = transcriptType.getDeclaredMethod("changed");
changed.setAccessible(true);
changed.invoke(transcript);
return true;
` }),
});

// Inspect only masking behavior, never the saved credential or its value.
export const ASSISTANT_KEY_MASKED = defineTask<void, boolean>({
  name: "folkways.assistant.masked-key", side: "client",
  source: javaTask({ imports: [
    "com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen",
    "com.lowdragmc.lowdraglib2.gui.ui.UIElement",
    "com.lowdragmc.lowdraglib2.gui.ui.elements.TextField",
    "java.util.ArrayDeque",
    "net.minecraft.client.Minecraft",
  ], body: `
if (!(Minecraft.getInstance().screen instanceof ModularUIScreen screen)) {
    return false;
}
var elements = new ArrayDeque<UIElement>();
elements.add(screen.modularUI.ui.getRootElement());
while (!elements.isEmpty()) {
    var element = elements.removeFirst();
    if (element instanceof TextField field && "folkways.assistant.api_key".equals(field.getId())) {
        return field.getText().isEmpty()
            && field.getFormatter().apply("example").getString().equals("*******")
            && field.getTextFieldStyle().placeholder().getString().startsWith("********");
    }
    elements.addAll(element.getChildren());
}
return false;
` }),
});
