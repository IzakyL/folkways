package io.github.izakyl.folkways.assistant;

import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.StateRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.internal.ActionTools;
import io.github.izakyl.folkways.assistant.tool.internal.ReadTools;
import io.github.izakyl.folkways.assistant.tool.internal.StateTools;
import net.neoforged.fml.common.Mod;

@Mod(AssistantMod.ID)
public final class AssistantMod {

    public static final String ID = "folkways_assistant";

    public AssistantMod() {
        ReadTools.register(ToolRegistry.READS);
        ReadTools.registerDirect(ToolRegistry.DIRECT_READS);
        ActionTools.register(ActionRegistry.ACTIONS);
        StateTools.register(StateRegistry.STATES);
    }
}
