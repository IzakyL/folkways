package io.github.izakyl.folkways.plugins.wares;

/** Implemented on furnaces: training belongs to the input batch, survives saves and expires with it. */
public interface FurnaceTraining {
    void folkways$removed();

    void folkways$loaded(int previousCount, int inserted, double speed);
}
