package io.github.izakyl.folkways.core.shell.rs2;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

public enum Rs2Refusal implements RefusalKind.Named {
    NO_DEVICE,
    NO_STOCK,
    TASK_BUSY,
    CALCULATION_FAILED,
    MISSING_INPUTS,
    SUBMISSION_FAILED,
    CANCELED;
}
