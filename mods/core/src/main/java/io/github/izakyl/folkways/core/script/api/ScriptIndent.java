package io.github.izakyl.folkways.core.script.api;

import java.util.BitSet;
import net.starlark.java.syntax.DefStatement;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.NodeVisitor;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.StringLiteral;
import net.starlark.java.syntax.SyntaxError;

final class ScriptIndent {

    private ScriptIndent() {
    }

    record Wrapped(String source, BitSet continuations, int lines) {

        Location original(Location location) {
            if (location == null || location.line() == 0) {
                return location;
            }
            int line = Math.max(1, Math.min(lines, location.line() - 2));
            int column = Math.max(1, location.column() - (continuations.get(line) ? 0 : 4));
            return Location.fromFileLineColumn(location.file(), line, column);
        }
    }

    static Wrapped wrap(String source, StarlarkFile file, SyntaxError.Exception broken) {
        boolean restricted = broken.errors().stream().anyMatch(error ->
                error.message().startsWith("for loops are not allowed at the top level.")
                        || error.message().startsWith("if statements are not allowed at the top level."));
        if (!restricted) {
            return null;
        }
        class Visitor extends NodeVisitor {

            private final BitSet continuations = new BitSet();

            private boolean allowed = true;

            @Override
            public void visit(DefStatement statement) {
                allowed = false;
            }

            @Override
            public void visit(LoadStatement statement) {
                allowed = false;
            }

            @Override
            public void visit(StringLiteral literal) {
                continuations.set(literal.getStartLocation().line() + 1, literal.getEndLocation().line() + 1);
            }
        }
        Visitor visitor = new Visitor();
        visitor.visit(file);
        if (!visitor.allowed) {
            return null;
        }
        String[] lines = source.split("\n", -1);
        StringBuilder body = new StringBuilder("def __folkways_run():\n    result = None\n");
        for (int index = 0; index < lines.length; index++) {
            if (!visitor.continuations.get(index + 1)) {
                body.append("    ");
            }
            body.append(lines[index]).append('\n');
        }
        body.append("    return result\nresult = __folkways_run()\n");
        return new Wrapped(body.toString(), visitor.continuations, lines.length);
    }
}
