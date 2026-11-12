package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Native-free planning snapshots and a mailbox drained exclusively by the server. */
public final class CraftQuotes<N> {

    public record Request<N>(N network, ItemSpec item, long count) {
    }

    public record Quote(long bytes, int ticks, Map<String, Long> patterns, boolean possible) {
        public Quote {
            patterns = Map.copyOf(patterns);
        }

        public static Quote impossible() {
            return new Quote(0, 0, Map.of(), false);
        }
    }

    public record Snapshot<N>(Map<Request<N>, Quote> quotes, Set<Request<N>> requests) {
        public Snapshot {
            quotes = Map.copyOf(quotes);
        }

        public Optional<Quote> find(N network, ItemSpec item, long count) {
            Request<N> request = new Request<>(network, item, count);
            Quote quote = quotes.get(request);
            if (quote == null) {
                requests.add(request);
            }
            return Optional.ofNullable(quote).filter(Quote::possible);
        }
    }

    private final Map<Request<N>, Quote> quotes = new HashMap<>();
    private final Set<Request<N>> requests = ConcurrentHashMap.newKeySet();

    public Snapshot<N> snapshot() {
        return new Snapshot<>(quotes, requests);
    }

    public Set<Request<N>> requested(N network) {
        return requests.stream().filter(request -> request.network().equals(network))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public void put(Request<N> request, Quote quote) {
        quotes.put(request, quote);
        requests.remove(request);
    }

    public void invalidate(N network) {
        quotes.keySet().removeIf(request -> request.network().equals(network));
    }

    public void retain(Set<N> networks) {
        quotes.keySet().removeIf(request -> !networks.contains(request.network()));
        requests.removeIf(request -> !networks.contains(request.network()));
    }
}
