package io.github.izakyl.folkways.assistant.tool.api;

import com.google.gson.JsonObject;

public record ToolSpec(String id, String description, JsonObject schema) {

    public ToolSpec {
        schema = schema.deepCopy();
    }

    @Override
    public JsonObject schema() {
        return schema.deepCopy();
    }
}
