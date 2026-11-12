package io.github.izakyl.folkways.core.shell.ae2;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

public enum Ae2Refusal implements RefusalKind.Named {
    NO_DEVICE,
    NO_STOCK,
    CPU_BUSY,
    CALCULATION_FAILED,
    MISSING_INPUTS,
    SUBMISSION_FAILED,
    CANCELED;
}
