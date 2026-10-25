package io.github.izakyl.folkways.plugins.person.look;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Molang as Touhou Little Maid's models write it, read two ways. What an expression comes to for a model at
// rest, with a healthy, awake resident and `a ?? b` falling back for unknown names, reads a bone's scale.
// And the same expression in the narrower Molang GeckoLib takes, for the animations a resident plays.
final class Molang {
    // The queries GeckoLib answers for something that is not itself an entity; a resident's model is drawn by one
    // stand-in for them all, and any other query is an error logged on every frame.
    private static final Set<String> ANSWERED = Set.of("anim_time", "life_time", "actor_count", "time_of_day",
        "moon_phase", "moon_brightness", "time_stamp", "frame_alpha", "controller_speed");
    private static final Set<String> KEPT = Set.of("v", "variable", "t", "temp");
    private static final Pattern STATEMENT = Pattern.compile("\\s*(return\\s+)?(?:([A-Za-z_][\\w.]*)\\s*=(?!=))?(.*)",
        Pattern.DOTALL);

    private final String text;
    private int at;
    private boolean guessed;

    private Molang(String text) {
        this.text = text;
    }

    static OptionalDouble atRest(String expression) {
        String trimmed = expression.strip();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        Molang reading = new Molang(trimmed);
        Optional<Value> value = reading.whole();
        return value.isPresent() && !reading.guessed
            ? OptionalDouble.of(value.get().number) : OptionalDouble.empty();
    }

    // Statement by statement; one that cannot be read is left as it was written.
    static String portable(String expression) {
        List<String> statements = new ArrayList<>();
        for (String statement : expression.split(";")) {
            if (statement.isBlank()) {
                continue;
            }
            Matcher parts = STATEMENT.matcher(statement);
            Optional<Value> value = parts.matches()
                ? new Molang(parts.group(3).strip()).whole() : Optional.empty();
            if (value.isEmpty()) {
                statements.add(statement.strip());
                continue;
            }
            statements.add((parts.group(1) == null ? "" : "return ")
                + (parts.group(2) == null ? "" : parts.group(2) + " = ")
                + value.get().written);
        }
        return statements.size() == 1 ? statements.getFirst() : String.join("; ", statements) + ";";
    }

    private Optional<Value> whole() {
        try {
            Value value = ternary();
            blanks();
            return at == text.length() ? Optional.of(value) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    // unset marks a bare variable or query, the one thing `??` passes over.
    private record Value(double number, boolean unset, String written, int precedence) {
        Value(double number, boolean unset, String written) {
            this(number, unset, written, Integer.MAX_VALUE);
        }

        static Value of(double number, String written) {
            return new Value(number, false, written);
        }

        static Value of(boolean truth, String written) {
            return of(truth ? 1.0 : 0.0, written);
        }

        boolean truth() {
            return number != 0.0;
        }

        // GeckoLib 4 ranks comparisons and both boolean operators above arithmetic (all at 5).
        // Keep the tree we parsed using TLM's precedence, including right operands at equal precedence.
        static Value binary(double number, Value left, String sign, Value right, int precedence) {
            return new Value(number, false,
                left.operand(precedence, false) + " " + sign + " " + right.operand(precedence, true), precedence);
        }

        String operand(int parentPrecedence, boolean right) {
            return precedence < parentPrecedence || (right && precedence == parentPrecedence)
                ? "(" + written + ")" : written;
        }
    }

    // GeckoLib refuses a whole animation over an `a ? b`, so the `: 0` it implies is written out.
    private Value ternary() {
        Value condition = coalesce();
        if (!take("?")) {
            return condition;
        }
        Value then = ternary();
        Value otherwise = take(":") ? ternary() : Value.of(0.0, "0");
        return new Value(condition.truth() ? then.number : otherwise.number, false,
            condition.operand(1, false) + " ? " + then.operand(1, false) + " : " + otherwise.operand(1, false), 0);
    }

    private Value coalesce() {
        Value left = either();
        while (take("??")) {
            Value right = either();
            left = left.unset ? new Value(right.number, false, right.written, right.precedence) : left;
        }
        return left;
    }

    private Value either() {
        Value left = both();
        while (take("||")) {
            Value right = both();
            left = Value.binary(left.truth() || right.truth() ? 1 : 0, left, "||", right, 5);
        }
        return left;
    }

    private Value both() {
        Value left = compared();
        while (take("&&")) {
            Value right = compared();
            left = Value.binary(left.truth() && right.truth() ? 1 : 0, left, "&&", right, 5);
        }
        return left;
    }

    private Value compared() {
        Value left = sum();
        while (true) {
            String sign = taken("==", "!=", "<=", ">=", "<", ">");
            if (sign == null) {
                return left;
            }
            Value right = sum();
            boolean truth = switch (sign) {
                case "==" -> left.number == right.number;
                case "!=" -> left.number != right.number;
                case "<=" -> left.number <= right.number;
                case ">=" -> left.number >= right.number;
                case "<" -> left.number < right.number;
                default -> left.number > right.number;
            };
            left = Value.binary(truth ? 1 : 0, left, sign, right, 5);
        }
    }

    private Value sum() {
        Value left = product();
        while (true) {
            String sign = taken("+", "-");
            if (sign == null) {
                return left;
            }
            Value right = product();
            left = Value.binary(sign.equals("+") ? left.number + right.number : left.number - right.number,
                left, sign, right, 1);
        }
    }

    private Value product() {
        Value left = unary();
        while (true) {
            String sign = taken("*", "/");
            if (sign == null) {
                return left;
            }
            Value right = unary();
            left = Value.binary(sign.equals("*") ? left.number * right.number : left.number / right.number,
                left, sign, right, 2);
        }
    }

    private Value unary() {
        if (take("-")) {
            Value negated = unary();
            return Value.of(-negated.number, "-" + negated.written);
        }
        if (take("+")) {
            return unary();
        }
        blanks();
        if (at < text.length() && text.charAt(at) == '!' && !text.startsWith("!=", at)) {
            at++;
            Value denied = unary();
            return Value.of(!denied.truth(), "!" + denied.written);
        }
        return primary();
    }

    private Value primary() {
        blanks();
        if (take("(")) {
            Value inner = ternary();
            if (!take(")")) {
                throw new IllegalArgumentException("unmatched (");
            }
            return new Value(inner.number, false, "(" + inner.written + ")");
        }
        int from = at;
        if (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
            while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                at++;
            }
            String digits = text.substring(from, at);
            if (at < text.length() && (text.charAt(at) == 'f' || text.charAt(at) == 'F')) {
                at++;
            }
            return Value.of(Double.parseDouble(digits), digits);
        }
        if (at < text.length() && (text.charAt(at) == '\'' || text.charAt(at) == '"')) {
            int closing = text.indexOf(text.charAt(at), at + 1);
            if (closing < 0) {
                throw new IllegalArgumentException("unmatched quote");
            }
            at = closing + 1;
            return new Value(0.0, true, text.substring(from, at));
        }
        while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at))
                || text.charAt(at) == '_' || text.charAt(at) == '.')) {
            at++;
        }
        if (at == from) {
            throw new IllegalArgumentException("nothing to read at " + at);
        }
        String name = text.substring(from, at).toLowerCase(Locale.ROOT);
        Optional<List<Value>> arguments = arguments();
        if (name.equals("true") || name.equals("false")) {
            return Value.of(name.equals("true"), name.equals("true") ? "1" : "0");
        }
        int dot = name.indexOf('.');
        String scope = dot < 0 ? "" : name.substring(0, dot);
        if (scope.equals("math")) {
            List<Value> of = arguments.orElseGet(List::of);
            String written = name + (arguments.isEmpty() ? ""
                : "(" + String.join(", ", of.stream().map(Value::written).toList()) + ")");
            double number = math(name.substring(dot + 1), of);
            guessed |= Double.isNaN(number);
            return Value.of(number, written);
        }
        if (name.equals("ysm.second_order") && arguments.isPresent() && arguments.get().size() >= 2) {
            // TLM initializes a new filter at its input. Keep that steady-state value without borrowing
            // another resident's physics history or pulling a world-space prop toward the world origin.
            Value input = arguments.get().get(1);
            return Value.of(input.number, "(" + input.written + ")");
        }
        if (name.equals("ysm.bone_rot") && arguments.isPresent() && arguments.get().size() == 1 && take(".")) {
            String axis = taken("x", "y", "z");
            String bone = arguments.get().getFirst().written;
            if (axis != null && bone.length() >= 2 && (bone.startsWith("'") || bone.startsWith("\""))) {
                return Value.of(0, ResidentMolang.boneRotation(bone.substring(1, bone.length() - 1), axis));
            }
            throw new IllegalArgumentException("unsupported bone rotation member");
        }
        String reference = name + (arguments.isEmpty() ? "" : "(" + String.join(", ",
            arguments.get().stream().map(value -> value.written.toLowerCase(Locale.ROOT)).toList()) + ")");
        Optional<ResidentMolang> resident = ResidentMolang.named(reference);
        if (resident.isPresent()) {
            return Value.of(resident.get().resting(), resident.get().variable());
        }
        if (arguments.isEmpty() && KEPT.contains(scope)) {
            return new Value(0.0, true, name);
        }
        if (arguments.isEmpty() && (scope.equals("q") || scope.equals("query"))
                && ANSWERED.contains(name.substring(dot + 1))) {
            return new Value(0.0, true, "query." + name.substring(dot + 1));
        }
        return new Value(0.0, true, "0");
    }

    private Optional<List<Value>> arguments() {
        if (!take("(")) {
            return Optional.empty();
        }
        List<Value> arguments = new ArrayList<>();
        if (take(")")) {
            return Optional.of(arguments);
        }
        do {
            arguments.add(ternary());
        } while (take(","));
        if (!take(")")) {
            throw new IllegalArgumentException("unmatched (");
        }
        return Optional.of(arguments);
    }

    // A function this does not know still reads, and stays as written; only its value at rest is a guess.
    private static double math(String function, List<Value> arguments) {
        double[] of = arguments.stream().mapToDouble(Value::number).toArray();
        return switch (function + "/" + of.length) {
            case "pi/0" -> Math.PI;
            case "abs/1" -> Math.abs(of[0]);
            case "sin/1" -> Math.sin(Math.toRadians(of[0]));
            case "cos/1" -> Math.cos(Math.toRadians(of[0]));
            case "floor/1" -> Math.floor(of[0]);
            case "ceil/1" -> Math.ceil(of[0]);
            case "round/1" -> Math.round(of[0]);
            case "trunc/1" -> of[0] < 0 ? Math.ceil(of[0]) : Math.floor(of[0]);
            case "sqrt/1" -> Math.sqrt(of[0]);
            case "min/2" -> Math.min(of[0], of[1]);
            case "max/2" -> Math.max(of[0], of[1]);
            case "pow/2" -> Math.pow(of[0], of[1]);
            case "mod/2" -> of[0] % of[1];
            case "clamp/3" -> Math.min(Math.max(of[0], of[1]), of[2]);
            case "lerp/3" -> of[0] + (of[1] - of[0]) * of[2];
            default -> Double.NaN;
        };
    }

    private String taken(String... symbols) {
        for (String symbol : symbols) {
            if (take(symbol)) {
                return symbol;
            }
        }
        return null;
    }

    private boolean take(String symbol) {
        blanks();
        if (!text.startsWith(symbol, at)) {
            return false;
        }
        // One of a pair is not the other: `?` is not `??`, `<` not `<=`, and `=` alone is an assignment.
        int after = at + symbol.length();
        if (symbol.equals("?") && text.startsWith("??", at)) {
            return false;
        }
        if ((symbol.equals("<") || symbol.equals(">")) && after < text.length() && text.charAt(after) == '=') {
            return false;
        }
        at = after;
        return true;
    }

    private void blanks() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }
}
