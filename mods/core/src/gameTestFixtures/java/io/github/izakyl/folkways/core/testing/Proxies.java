package io.github.izakyl.folkways.core.testing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

public final class Proxies {
    private Proxies() {
    }

    // A stand-in for an interface that answers every call through the handler.
    public static <T> T of(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
