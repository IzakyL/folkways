package io.github.izakyl.folkways.core.script.api;

import net.starlark.java.syntax.Location;

public final class ScriptFault extends RuntimeException {

    public enum Kind {
        SYNTAX, EVAL, STEPS, MEMORY, TIMEOUT, CALLS, SOURCE_SIZE, RESULT_SIZE, LOAD, FUNCTION, CANCELLED, BUSY
    }

    private final Kind kind;
    private final Location location;

    public ScriptFault(Kind kind, String detail, Location location) {
        this(kind, detail, location, null);
    }

    ScriptFault(Kind kind, String detail, Location location, Throwable cause) {
        super(detail, cause);
        this.kind = kind;
        this.location = location;
    }

    public Kind kind() {
        return kind;
    }

    public String detail() {
        return getMessage();
    }

    public Location location() {
        return location;
    }
}
