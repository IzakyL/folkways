package net.starlark.java.eval;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import net.starlark.java.syntax.TokenKind;

/** Allocation guards for the pinned interpreter. They are active only inside a Folkways evaluation. */
public final class AllocationBudget implements AutoCloseable {

    private static final ThreadLocal<AllocationBudget> ACTIVE = new ThreadLocal<>();
    private static final ThreadMXBean ALLOCATIONS = allocationBean();

    private final long hardLimit;
    private final long cumulativeLimit;
    private final AllocationBudget parent;
    private long started;
    private long counted;
    private boolean counting = true;
    private long remaining;
    private int checkpoints;

    private AllocationBudget(long hardLimit, long cumulativeLimit) {
        this.hardLimit = hardLimit;
        this.cumulativeLimit = cumulativeLimit;
        this.remaining = hardLimit;
        this.parent = ACTIVE.get();
        this.started = allocatedBytes();
        ACTIVE.set(this);
    }

    public static AllocationBudget open(long hardLimit, long cumulativeLimit) {
        return new AllocationBudget(hardLimit, cumulativeLimit);
    }

    @Override
    public void close() {
        if (parent == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(parent);
        }
    }

    private static ThreadMXBean allocationBean() {
        if (ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported()) {
            try {
                if (!bean.isThreadAllocatedMemoryEnabled()) {
                    bean.setThreadAllocatedMemoryEnabled(true);
                }
                return bean;
            } catch (SecurityException denied) {
                return null;
            }
        }
        return null;
    }

    private static long allocatedBytes() {
        return ALLOCATIONS == null ? -1 : ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    public static void checkpoint() {
        AllocationBudget active = ACTIVE.get();
        if (active != null && (++active.checkpoints & 63) == 0) {
            checkNow(active);
        }
    }

    public static void checkNow() {
        checkNow(ACTIVE.get());
    }

    // The interpreter dispatches both annotated methods and Java callables through these hooks.
    // Argument binding and result conversion still run in the interpreter.
    static Object invoke(Method method, Object receiver, Object[] args)
            throws IllegalAccessException, InvocationTargetException {
        AllocationBudget active = ACTIVE.get();
        if (active == null || !folkways(method.getDeclaringClass())) {
            return method.invoke(receiver, args);
        }
        boolean previous = active.setCounting(false);
        try {
            return method.invoke(receiver, args);
        } finally {
            active.setCounting(previous);
        }
    }

    static Object fastcall(StarlarkCallable callable, StarlarkThread thread, Object[] positional, Object[] named)
            throws EvalException, InterruptedException {
        AllocationBudget active = ACTIVE.get();
        if (active == null) {
            return callable.fastcall(thread, positional, named);
        }
        boolean previous = active.setCounting(!folkways(callable.getClass()));
        try {
            return callable.fastcall(thread, positional, named);
        } finally {
            active.setCounting(previous);
        }
    }

    private static boolean folkways(Class<?> type) {
        return type.getName().startsWith("io.github.izakyl.folkways.");
    }

    private boolean setCounting(boolean enabled) {
        boolean previous = counting;
        if (previous != enabled) {
            if (previous) {
                counted = countedBytes();
            } else {
                started = allocatedBytes();
            }
            counting = enabled;
        }
        return previous;
    }

    public static long currentCountedBytes() {
        AllocationBudget active = ACTIVE.get();
        return active == null ? 0 : active.countedBytes();
    }

    private long countedBytes() {
        return counting && started >= 0 ? counted + Math.max(0, allocatedBytes() - started) : counted;
    }

    private static void checkNow(AllocationBudget active) {
        if (active != null && active.countedBytes() > active.cumulativeLimit) {
            throw new Exceeded("Script exceeded its cumulative JVM allocation limit (" + active.cumulativeLimit
                + " bytes)");
        }
    }

    private static void reserve(long bytes) {
        AllocationBudget active = ACTIVE.get();
        if (active != null) {
            active.checkpoints++;
            if ((active.checkpoints & 63) == 0) {
                checkNow(active);
            }
            if (bytes > active.remaining) {
                refuse(active);
            }
            active.remaining -= bytes;
        }
    }

    private static void refuse(AllocationBudget active) {
        throw new Exceeded("Script exceeded its pre-allocation memory budget (" + active.hardLimit + " bytes)");
    }

    static int array(int length, int width) {
        if (length > 0) {
            reserve(24L + (long) length * width);
        }
        return length;
    }

    static int references(int length) {
        return array(length, 8);
    }

    static int interpreterReferences(int length) {
        // The interpreter uses short arrays for argument passing and little values on each step.
        // The JVM counter measures that transient churn. Growth and bulk allocation reserve separately.
        return length <= 16 ? length : references(length);
    }

    static Object[] copyReferences(Object[] source, int length, Class<? extends Object[]> type) {
        return Arrays.copyOf(source, references(length), type);
    }

    static void materialize(Iterable<?> values) {
        if (values instanceof Collection<?> collection) {
            references(collection.size());
            rangeElements(values);
        }
    }

    static void rangeElements(Object values) {
        if (values instanceof RangeList range) {
            reserve(24L * range.size());
        }
    }

    static void binary(TokenKind operator, Object left, Object right) {
        AllocationBudget active = ACTIVE.get();
        if (active == null) {
            return;
        }
        if (operator == TokenKind.PLUS && left instanceof String a && right instanceof String b) {
            reserve(24L + 2L * ((long) a.length() + b.length()));
        } else if (operator == TokenKind.STAR) {
            if (left instanceof StarlarkInt count) {
                repeat(active, right, count);
            } else if (right instanceof StarlarkInt count) {
                repeat(active, left, count);
            }
        }
    }

    private static void repeat(AllocationBudget active, Object value, StarlarkInt count) {
        long width;
        if (value instanceof String string) {
            width = 2L * string.length();
        } else if (value instanceof StarlarkList<?> list) {
            width = 8L * list.size();
        } else if (value instanceof Tuple tuple) {
            width = 8L * tuple.size();
        } else {
            return;
        }
        // Check before the interpreter multiplies two ints to size its backing array.
        if (width == 0 || count.signum() <= 0) {
            return;
        }
        if (count.toBigInteger().bitLength() > 31
                || active.remaining < 24 || count.toIntUnchecked() > (active.remaining - 24) / width) {
            refuse(active);
        }
        int repetitions = count.toIntUnchecked();
        if (value instanceof String || width * repetitions <= 16 * 8) {
            reserve(24 + width * repetitions);
        }
    }

    static void join(String separator, Object values) throws EvalException {
        AllocationBudget active = ACTIVE.get();
        if (active == null) {
            return;
        }
        long chars = 0;
        long delimiters = 0;
        for (Object value : Starlark.toIterable(values)) {
            if (!(value instanceof String string)) {
                return; // The original builtin reports the offending element and index.
            }
            chars += delimiters + string.length();
            delimiters = separator.length();
            if (chars > active.remaining / 2) {
                refuse(active);
            }
        }
        reserve(24 + 2 * chars);
    }

    private static void grow(StringBuilder builder, int added) {
        long length = (long) builder.length() + added;
        if (length > builder.capacity()) {
            reserve(24 + 2 * Math.max(length, 2L * builder.capacity() + 2));
        }
    }

    static StringBuilder append(StringBuilder builder, char value) {
        grow(builder, 1);
        return builder.append(value);
    }

    static StringBuilder append(StringBuilder builder, String value) {
        grow(builder, value == null ? 4 : value.length());
        return builder.append(value);
    }

    static StringBuilder append(StringBuilder builder, CharSequence value) {
        grow(builder, value == null ? 4 : value.length());
        return builder.append(value);
    }

    static StringBuilder append(StringBuilder builder, CharSequence value, int start, int end) {
        grow(builder, end - start);
        return builder.append(value, start, end);
    }

    static String finishString(StringBuilder builder) {
        reserve(24 + 2L * builder.length());
        return builder.toString();
    }

    static Object put(LinkedHashMap<Object, Object> map, Object key, Object value) {
        if (ACTIVE.get() != null && !map.containsKey(key)) {
            reserve(64);
        }
        return map.put(key, value);
    }

    public static final class Exceeded extends RuntimeException {

        private Exceeded(String message) {
            super(message);
        }
    }
}
