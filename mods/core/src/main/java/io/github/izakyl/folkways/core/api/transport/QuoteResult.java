package io.github.izakyl.folkways.core.api.transport;

public sealed interface QuoteResult {

    // An incomplete minimum is still a usable candidate, but cannot establish an optimal path.
    record Available(TransportQuote quote, boolean complete) implements QuoteResult {
    }

    record Unavailable() implements QuoteResult {
    }

    record Unknown() implements QuoteResult {
    }

    QuoteResult NO = new Unavailable();
    QuoteResult LATER = new Unknown();
}
