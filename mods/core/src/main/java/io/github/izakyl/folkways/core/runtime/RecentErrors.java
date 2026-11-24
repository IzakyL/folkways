package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.base.task.ColonyLog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

public final class RecentErrors extends AbstractAppender {

    private static final String PREFIX = "io.github.izakyl.folkways";
    private static final RecentErrors INSTANCE = new RecentErrors();
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private long dropped;
    private long since;

    private record Fingerprint(String logger, String level, String exception, String head) {
    }

    // A fingerprint must never merge different colonies' contents or counters.
    private record Key(Optional<UUID> colony, Fingerprint fingerprint) {
    }

    public record Failure(String exception, String message, List<String> frames,
                          Optional<Failure> cause, List<Failure> suppressed) {

        public Failure {
            frames = List.copyOf(frames);
            suppressed = List.copyOf(suppressed);
        }
    }

    public record Entry(long time, String level, String logger, String message, Optional<String> exception,
                        Optional<String> exceptionMessage, List<String> frames, Optional<Failure> cause,
                        List<Failure> suppressed, boolean detailsTruncated, Optional<UUID> colony,
                        long count, long firstTime, long lastTime) {

        public Entry {
            frames = List.copyOf(frames);
            suppressed = List.copyOf(suppressed);
        }
    }

    public record Snapshot(List<Entry> entries, long dropped, long since) {

        public Snapshot {
            entries = List.copyOf(entries);
        }
    }

    private static final class Chain {
        private final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private int remaining = 16;
        private boolean truncated;

        private Optional<Failure> read(Throwable error, int depth) {
            if (error == null) {
                return Optional.empty();
            }
            if (depth > 4 || remaining == 0 || !seen.add(error)) {
                truncated = true;
                return Optional.empty();
            }
            remaining--;
            String message = text(error.getMessage());
            truncated |= error.getMessage() != null && error.getMessage().length() > message.length();
            StackTraceElement[] stack = error.getStackTrace();
            truncated |= stack.length > 4;
            return Optional.of(new Failure(error.getClass().getName(), message, frames(stack, 4),
                read(error.getCause(), depth + 1), suppressed(error, depth + 1)));
        }

        private List<Failure> suppressed(Throwable error, int depth) {
            List<Failure> found = new ArrayList<>();
            Throwable[] children = error.getSuppressed();
            int limit = depth > 4 ? 0 : Math.min(16, children.length);
            int index = 0;
            while (index < limit && remaining > 0) {
                read(children[index++], depth).ifPresent(found::add);
            }
            truncated |= index < children.length;
            return found;
        }
    }

    private RecentErrors() {
        super("folkways-recent-errors", null, null, true, Property.EMPTY_ARRAY);
    }

    public static void install() {
        synchronized (INSTANCE) {
            if (!INSTANCE.isStarted()) {
                INSTANCE.entries.clear();
                INSTANCE.dropped = 0;
                INSTANCE.since = System.currentTimeMillis();
                INSTANCE.start();
            }
        }
        ((LoggerContext) LogManager.getContext(false)).getRootLogger().addAppender(INSTANCE);
    }

    public static void uninstall() {
        ((LoggerContext) LogManager.getContext(false)).getRootLogger().removeAppender(INSTANCE);
        INSTANCE.stop();
        synchronized (INSTANCE) {
            INSTANCE.entries.clear();
            INSTANCE.dropped = 0;
            INSTANCE.since = 0;
        }
    }

    @Override
    public synchronized void append(LogEvent event) {
        String logger = event.getLoggerName();
        if (logger == null || !(logger.startsWith(PREFIX) || logger.startsWith("folkways-")
                || logger.equals("folkways"))
                || event.getLevel() != Level.WARN && event.getLevel() != Level.ERROR) {
            return;
        }
        Throwable thrown = event.getThrown();
        StackTraceElement[] stack = thrown == null ? new StackTraceElement[0] : thrown.getStackTrace();
        Optional<String> exception = Optional.ofNullable(thrown).map(error -> error.getClass().getName());
        Fingerprint fingerprint = new Fingerprint(logger, event.getLevel().name(), exception.orElse(""),
            thrown == null ? event.getMessage().getFormat() : stack.length == 0 ? "" : stack[0].toString());
        Optional<UUID> colony = colony(event);
        Key key = new Key(colony, fingerprint);
        Entry previous = entries.remove(key);
        Chain chain = new Chain();
        Optional<Failure> cause = Optional.empty();
        List<Failure> suppressed = List.of();
        Optional<String> exceptionMessage = Optional.empty();
        if (thrown != null) {
            chain.seen.add(thrown);
            exceptionMessage = Optional.of(text(thrown.getMessage()));
            chain.truncated |= thrown.getMessage() != null
                && thrown.getMessage().length() > exceptionMessage.get().length() || stack.length > 8;
            cause = chain.read(thrown.getCause(), 1);
            suppressed = chain.suppressed(thrown, 1);
        }
        String message = event.getMessage().getFormattedMessage();
        long now = event.getTimeMillis();
        entries.put(key, new Entry(now, event.getLevel().name(), logger, text(message), exception, exceptionMessage,
            frames(stack, 8), cause, suppressed, chain.truncated || message != null && message.length() > 500,
            colony, previous == null ? 1 : previous.count() + 1, previous == null ? now : previous.firstTime(), now));
        if (entries.size() > 200) {
            entries.remove(entries.keySet().iterator().next());
            dropped++;
        }
    }

    private static String text(String value) {
        if (value == null) {
            return "";
        }
        int end = Math.min(500, value.length());
        if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static List<String> frames(StackTraceElement[] stack, int limit) {
        return Arrays.stream(stack).limit(limit).map(frame -> text(frame.toString())).toList();
    }

    private static Optional<UUID> colony(LogEvent event) {
        String value = event.getContextData().getValue(ColonyLog.KEY);
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public static List<Entry> latest(int limit) {
        return snapshot(limit, Optional.empty()).entries();
    }

    public static Snapshot snapshot(int limit, Optional<UUID> colony) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be 1..200");
        }
        synchronized (INSTANCE) {
            List<Entry> visible = INSTANCE.entries.values().stream()
                .filter(entry -> colony.isEmpty() || entry.colony().equals(colony)).toList();
            return new Snapshot(visible.subList(Math.max(0, visible.size() - limit), visible.size()),
                INSTANCE.dropped, INSTANCE.since);
        }
    }
}
