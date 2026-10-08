package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import java.util.List;

record Batch(WorkSite at, Stances stances, Crop crop, List<Job> jobs) {

    Batch {
        jobs = List.copyOf(jobs);
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("a batch covering no cell is not a batch");
        }
    }
}
