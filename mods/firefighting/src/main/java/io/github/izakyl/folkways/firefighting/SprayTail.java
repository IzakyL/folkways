package io.github.izakyl.folkways.firefighting;

// Releasing the cabinet stops emission, while the last burst still has time to appear and finish flying.
record SprayTail(long seen, long stopped) {
    static SprayTail start(long now) { return new SprayTail(now, Long.MIN_VALUE); }

    SprayTail stop(long now) { return stopped == Long.MIN_VALUE ? new SprayTail(seen, now) : this; }

    boolean hose(long now) { return now - seen <= 20 && (stopped == Long.MIN_VALUE || now - stopped < 4); }

    boolean finished(long now, boolean empty) { return empty && !hose(now); }
}
