package io.github.izakyl.folkways.core.testing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

public final class Proxies {
    private Proxies() {
    }

    // A stand-in for an interface that answers every call through the handler. A default method with a result
    // that the handler leaves unanswered runs its default body, so stand-ins keep working as interfaces grow.
    public static <T> T of(Class<T> type, InvocationHandler handler) {
        InvocationHandler answering = (proxy, method, args) -> {
            Object answer = handler.invoke(proxy, method, args);
            if (answer == null && method.isDefault() && method.getReturnType() != void.class) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return answer;
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, answering));
    }
}
