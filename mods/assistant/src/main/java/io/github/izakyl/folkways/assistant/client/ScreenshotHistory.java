package io.github.izakyl.folkways.assistant.client;

import io.github.izakyl.folkways.assistant.tool.api.CaptureRequest;
import io.github.izakyl.jpi.ai.ContentBlock;
import io.github.izakyl.jpi.ai.Message;
import io.github.izakyl.jpi.ai.ToolResultMessage;
import io.github.izakyl.jpi.ai.UserMessage;
import java.util.ArrayList;
import java.util.List;

final class ScreenshotHistory {

    private static final int KEPT = 2;

    private ScreenshotHistory() {
    }

    /**
     * Keeps the newest set of screenshots whole, so a multi-view check stays in view as the model reasons about it,
     * and otherwise the two newest images.
     */
    static List<Message> trim(List<Message> messages) {
        int latest = 0;
        for (int index = messages.size() - 1; index >= 0 && latest == 0; index--) {
            Message message = messages.get(index);
            if (message instanceof UserMessage || message instanceof ToolResultMessage) {
                latest = (int) message.content().stream().filter(ContentBlock.Image.class::isInstance).count();
            }
        }
        return trim(messages, Math.min(CaptureRequest.MAX_PER_RUN, Math.max(KEPT, latest)));
    }

    private static List<Message> trim(List<Message> messages, int remaining) {
        List<Message> result = new ArrayList<>(messages);
        for (int index = result.size() - 1; index >= 0; index--) {
            Message message = result.get(index);
            if (!(message instanceof UserMessage || message instanceof ToolResultMessage)) {
                continue;
            }
            List<ContentBlock> content = new ArrayList<>(message.content());
            boolean changed = false;
            for (int block = content.size() - 1; block >= 0; block--) {
                if (content.get(block) instanceof ContentBlock.Image && remaining-- <= 0) {
                    content.set(block, new ContentBlock.Text("[earlier screenshot omitted]"));
                    changed = true;
                }
            }
            if (changed) {
                result.set(index, switch (message) {
                    case UserMessage user -> new UserMessage(content, user.timestamp());
                    case ToolResultMessage tool -> ToolResultMessage.builder(tool.toolCallId(), tool.toolName())
                            .content(content).details(tool.details()).isError(tool.isError())
                            .timestamp(tool.timestamp()).build();
                    default -> message;
                });
            }
        }
        return List.copyOf(result);
    }
}
