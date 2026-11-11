package io.github.izakyl.folkways.firearms.tacz;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The clock TACZ stamps its shooter timers with: the wall clock before 1.2.0, GunTime from 1.2.0 on. */
final class TaczClock {

    private static final MethodHandle GUN_TIME = gunTime();

    private TaczClock() {
    }

    static long nowMillis() {
        if (GUN_TIME == null) {
            return System.currentTimeMillis();
        }
        try {
            return (long) GUN_TIME.invokeExact();
        } catch (Throwable failed) {
            throw new IllegalStateException("TACZ GunTime.nowMillis failed", failed);
        }
    }

    private static MethodHandle gunTime() {
        try {
            Class<?> type = Class.forName("com.tacz.guns.util.time.GunTime");
            return MethodHandles.publicLookup().findStatic(type, "nowMillis", MethodType.methodType(long.class));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException absent) {
            return null;
        }
    }
}
