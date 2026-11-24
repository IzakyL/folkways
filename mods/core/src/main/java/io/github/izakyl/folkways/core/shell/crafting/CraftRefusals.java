package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

/** Native crafting failures with each network's original translation keys. */
public record CraftRefusals(RefusalKind noDevice, RefusalKind noStock, RefusalKind busy, RefusalKind calculationFailed,
        RefusalKind missingInputs, RefusalKind submissionFailed, RefusalKind canceled) {

    public static final CraftRefusals AE2 = new CraftRefusals("ae2", "cpu_busy");
    public static final CraftRefusals RS2 = new CraftRefusals("rs2", "task_busy");

    private CraftRefusals(String network, String busy) {
        this(kind(network, "no_device"), kind(network, "no_stock"), kind(network, busy),
            kind(network, "calculation_failed"), kind(network, "missing_inputs"), kind(network, "submission_failed"),
            kind(network, "canceled"));
    }

    private static RefusalKind kind(String network, String reason) {
        return new Kind("folkways.refusal." + network + "." + reason);
    }

    private record Kind(String translationKey) implements RefusalKind {
    }
}
