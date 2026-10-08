package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Grown;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

public sealed interface Message {

    record Submitted(ResourceLocation owner, Grown work) implements Message {
    }

    record Withdrawn(ResourceLocation owner, UUID node) implements Message {
    }

    record Offered() implements Message {
    }

    record Started(UUID node, UUID resident) implements Message {
    }

    record Finished(UUID node) implements Message {
    }

    record Failed(UUID node, RefusalKind why) implements Message {
    }

    record Released(UUID node) implements Message {
    }

    record Left(UUID resident) implements Message {
    }

    record Idle(UUID resident) implements Message {
    }

    record Answered() implements Message {
    }

    Message OFFERED = new Offered();
    Message ANSWERED = new Answered();
}
