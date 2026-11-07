package io.github.izakyl.folkways.core.work.api;

public interface HoldingFactory {

    ActionHandle action(HoldingContext context, WorkAction action, Placement placement);

    WorkHolding standard(HoldingContext context, Node node);
}
