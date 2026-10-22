package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.api.ui.TextSearch;
import me.towdium.jecharacters.utils.Match;
import me.towdium.pinin.PinIn;

// Loaded only by client setup when JEC is present; its context owns the dictionary and spelling settings.
final class JechSearch {

    // JEC's accelerated matcher retains a thread-local spelling cache when its settings change. Renew a
    // ticket on that same thread, clearing the cache once per change instead of rebuilding the dictionary
    // or discarding the accelerator on every candidate.
    private static final ThreadLocal<PinIn.Ticket> settings = ThreadLocal.withInitial(() -> {
        Match.contains("", "");
        return Match.context.ticket(() -> Match.contains("", ""));
    });

    private JechSearch() {
    }

    static void install() {
        TextSearch.install(JechSearch::contains);
    }

    private static boolean contains(String text, String query) {
        settings.get().renew();
        return Match.contains(text, query);
    }
}
