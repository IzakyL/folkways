package io.github.izakyl.folkways.core.api.resident;

import java.util.Optional;

public final class Headway {

    private double began = -1.0D;
    private double closest = Double.MAX_VALUE;

    public void setOut(double apart) {
        began = apart;
        closest = apart;
    }

    public void closer(double apart) {
        closest = Math.min(closest, apart);
    }

    public Optional<Float> fraction() {
        if (began < 0.0D) {
            return Optional.empty();
        }
        if (began == 0.0D) {
            return Optional.of(1.0F);
        }
        return Optional.of((float) Math.clamp(1.0D - closest / began, 0.0D, 1.0D));
    }

    public void forget() {
        began = -1.0D;
        closest = Double.MAX_VALUE;
    }
}
