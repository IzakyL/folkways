package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.engine.travel.Urgency;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.ToLongFunction;

final class Pending extends AbstractCollection<Ask> {

    private static final class Entry {
        Ask ask;
        final long seq;
        long lastAsked;

        Entry(Ask ask, long seq, long now) {
            this.ask = ask;
            this.seq = seq;
            this.lastAsked = now;
        }
    }

    private static final Comparator<Entry> ORDER = Comparator.<Entry, Urgency>comparing(entry -> entry.ask.urgency())
        .thenComparingLong(entry -> entry.seq);

    private final Map<Ask.Key, Entry> entries = new HashMap<>();
    private final TreeSet<Entry> order = new TreeSet<>(ORDER);
    private long seq;

    @Override
    public boolean add(Ask ask) {
        return offer(ask, Long.MIN_VALUE);
    }

    boolean offer(Ask ask, long now) {
        Entry had = entries.get(ask.key());
        if (had != null) {
            order.remove(had);
            had.ask = ask;
            had.lastAsked = Math.max(had.lastAsked, now);
            order.add(had);
            return false;
        }
        Entry entry = new Entry(ask, seq++, now);
        entries.put(ask.key(), entry);
        order.add(entry);
        return true;
    }

    Ask pollFirst() {
        Entry first = order.pollFirst();
        if (first == null) {
            return null;
        }
        entries.remove(first.ask.key());
        return first.ask;
    }

    Ask peekFirst() {
        return order.isEmpty() ? null : order.first().ask;
    }

    List<Ask> expire(long now, ToLongFunction<Ask> lasting) {
        List<Ask> gone = new ArrayList<>();
        for (Iterator<Entry> walking = order.iterator(); walking.hasNext(); ) {
            Entry entry = walking.next();
            if (entry.lastAsked != Long.MIN_VALUE && now - entry.lastAsked > lasting.applyAsLong(entry.ask)) {
                walking.remove();
                entries.remove(entry.ask.key());
                gone.add(entry.ask);
            }
        }
        return gone;
    }

    @Override
    public boolean remove(Object ask) {
        if (!(ask instanceof Ask asked)) {
            return false;
        }
        Entry entry = entries.remove(asked.key());
        if (entry == null) {
            return false;
        }
        order.remove(entry);
        return true;
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public boolean contains(Object ask) {
        return ask instanceof Ask asked && entries.containsKey(asked.key());
    }

    @Override
    public Iterator<Ask> iterator() {
        Iterator<Entry> walking = order.iterator();
        return new Iterator<>() {
            private Entry current;

            @Override
            public boolean hasNext() {
                return walking.hasNext();
            }

            @Override
            public Ask next() {
                current = walking.next();
                return current.ask;
            }

            @Override
            public void remove() {
                walking.remove();
                entries.remove(current.ask.key());
            }
        };
    }
}
