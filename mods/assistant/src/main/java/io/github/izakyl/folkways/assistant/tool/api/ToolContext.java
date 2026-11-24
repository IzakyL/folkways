package io.github.izakyl.folkways.assistant.tool.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import net.minecraft.server.level.ServerPlayer;

public record ToolContext(ServerPlayer player, Colony colony) {
}
