package io.github.izakyl.folkways.core.shell.create;

import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;

public final class CreateMod {

    public static final String ID = "create";

    private CreateMod() {
    }

    public static boolean loaded() {
        return OptionalIntegrations.CREATE.enabled();
    }
}
