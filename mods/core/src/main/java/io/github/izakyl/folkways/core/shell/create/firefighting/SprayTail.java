package io.github.izakyl.folkways.core.shell.create.firefighting;

// Releasing the cabinet stops emission, while the last burst still has time to appear and finish flying.
public record SprayTail(long seen, long stopped) {
    public static SprayTail start(long now) {
        return new SprayTail(now, Long.MIN_VALUE);
    }

    public SprayTail stop(long now) {
        return stopped == Long.MIN_VALUE ? new SprayTail(seen, now) : this;
    }

    public boolean hose(long now) {
        return now - seen <= 20 && (stopped == Long.MIN_VALUE || now - stopped < 4);
    }

    public boolean finished(long now, boolean empty) {
        return empty && !hose(now);
    }
}
