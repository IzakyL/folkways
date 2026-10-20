package io.github.izakyl.folkways.core.api.work.batch;

import io.github.izakyl.folkways.core.api.work.Node;

// An exact physical inventory move with its own rollback, not consumption or production.
// No Staged/BatchAction, needs, promised goods, made/unused goods or XP; leaves may only reserve unknown pack room.
public interface InventoryTransfer extends Node { }
