package io.github.izakyl.folkways.core.work.support;

import io.github.izakyl.folkways.core.work.api.ActionHandle;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingFactory;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkHolding;

public final class StandardHoldings implements HoldingFactory {
    public static final StandardHoldings INSTANCE = new StandardHoldings();

    private StandardHoldings() {
    }

    @Override
    public ActionHandle action(HoldingContext context, WorkAction action, Placement placement) {
        return new ActionExecutor(context, action, placement, false);
    }

    @Override
    public WorkHolding standard(HoldingContext context, Node node) {
        return new StandardHolding(node, context.worker().placement(), context.joining(), context);
    }
}
