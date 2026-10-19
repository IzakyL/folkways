package io.github.izakyl.folkways.plugins.build.draft;

import net.minecraft.core.BlockPos;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.HasBinary;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.syntax.TokenKind;

@StarlarkBuiltin(name = "point", doc = "One cell, counted from the site's anchor.")
public record Point(int x, int y, int z) implements HasBinary {

    static Point of(BlockPos pos) {
        return new Point(pos.getX(), pos.getY(), pos.getZ());
    }

    BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    @StarlarkMethod(name = "x", doc = "Cells east of the anchor.", structField = true)
    public StarlarkInt starlarkX() {
        return StarlarkInt.of(x);
    }

    @StarlarkMethod(name = "y", doc = "Cells above the anchor.", structField = true)
    public StarlarkInt starlarkY() {
        return StarlarkInt.of(y);
    }

    @StarlarkMethod(name = "z", doc = "Cells south of the anchor.", structField = true)
    public StarlarkInt starlarkZ() {
        return StarlarkInt.of(z);
    }

    @Override
    public Object binaryOp(TokenKind op, Object that, boolean thisLeft) throws EvalException {
        if (!(that instanceof Point other)) {
            return null;
        }
        Point left = thisLeft ? this : other;
        Point right = thisLeft ? other : this;
        return switch (op) {
            case PLUS -> new Point(left.x + right.x, left.y + right.y, left.z + right.z);
            case MINUS -> new Point(left.x - right.x, left.y - right.y, left.z - right.z);
            default -> throw Starlark.errorf("points add and subtract, and nothing else");
        };
    }

    @Override
    public boolean isImmutable() {
        return true;
    }

    @Override
    public void repr(Printer printer) {
        printer.append("point(" + x + ", " + y + ", " + z + ")");
    }
}
