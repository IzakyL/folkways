package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

final class DelegationNames {

    private DelegationNames() {
    }

    /** The delegation's glyph, where it has one, and its name. */
    static Sentence told(ResourceLocation delegation) {
        return Sentence.of(
            Sentence.glyph(ResourceLocation.fromNamespaceAndPath(delegation.getNamespace(),
                "delegation/" + delegation.getPath())),
            Sentence.word(new Notice(
                "folkways.delegation." + delegation.getNamespace() + "." + delegation.getPath(), List.of())));
    }
}
