package io.github.izakyl.folkways.front.api.notice;

import static io.github.izakyl.folkways.core.api.work.Doings.WALKING;

import io.github.izakyl.folkways.core.api.work.Doing;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class Doings {

    private static final String ABOUT = ".about";

    private static final String NOW = "folkways.doing.now";

    private static final String HEADING = "folkways.doing.heading";

    private static final String ONWARD = "folkways.doing.onward";

    private Doings() {
    }

    public static String nameKey(ResourceLocation what) {
        return "folkways.doing." + what.getNamespace() + "." + what.getPath();
    }

    // A walk reads as the job it heads for; anything else done on the way, such as riding a train, reads as
    // itself and then the job.
    public static Component name(Doing doing) {
        Optional<ResourceLocation> toward = doing.toward();
        if (toward.isPresent() && !doing.what().equals(WALKING)) {
            Component now = Component.translatable(nameKey(doing.what()));
            return Component.translatable(ONWARD, now, phrase(toward.get(), doing.about()));
        }
        return toward
            .map(job -> Component.translatable(HEADING, phrase(job, doing.about())))
            .orElseGet(() -> Component.translatable(NOW, phrase(doing.what(), doing.about())));
    }

    /** What {@code what} is done to, without the "now": "Harvesting carrots". */
    public static Component phrase(ResourceLocation what, Optional<String> about) {
        return said(what, about).component();
    }

    /** The same phrase as a notice, to be put into words where it is read. */
    public static Notice said(ResourceLocation what, Optional<String> about) {
        return about
            .map(key -> new Notice(nameKey(what) + ABOUT, List.of(Notice.named(key))))
            .orElseGet(() -> new Notice(nameKey(what), List.of()));
    }
}
