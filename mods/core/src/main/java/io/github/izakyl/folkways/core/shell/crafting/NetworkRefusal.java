package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

public enum NetworkRefusal implements RefusalKind.Named {
    NOT_A_DEVICE,
    NOT_ALLOWED,
    LIMIT;
}
