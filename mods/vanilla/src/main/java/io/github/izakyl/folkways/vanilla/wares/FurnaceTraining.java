package io.github.izakyl.folkways.vanilla.wares;

/** Implemented on furnaces: training belongs to the input batch, survives saves and expires with it. */
public interface FurnaceTraining {
    long folkways$cookingTicks();

    void folkways$removed();

    void folkways$loaded(int previousCount, int inserted, double speed);
}
