package io.github.izakyl.folkways.core.shell.storage.functional;

import com.buuz135.functionalstorage.inventory.CompactingInventoryHandler;
import com.buuz135.functionalstorage.util.CompactingUtil;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.PortIdentity;
import io.github.izakyl.folkways.core.facility.api.control.PortReader;
import io.github.izakyl.folkways.core.facility.api.control.Pull;
import io.github.izakyl.folkways.core.facility.api.control.Room;
import io.github.izakyl.folkways.core.facility.api.control.SlotWindow;
import io.github.izakyl.folkways.core.facility.api.control.TransferResult;
import io.github.izakyl.folkways.core.facility.api.control.TransformHint;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** The native handler accounts for all compaction forms in one shared amount. */
record CompactingPort(WorldPos anchor, CompactingInventoryHandler handler) implements ItemPort {

    @Override
    public PortIdentity identity() {
        return PortIdentity.block(anchor);
    }

    @Override
    public Set<Operation> operations() {
        return Set.of(Operation.TAKE, Operation.PUT);
    }

    @Override
    public List<TransformHint> transforms() {
        List<ItemStack> forms = handler.getResultList().stream()
            .sorted(Comparator.comparingInt(CompactingUtil.Result::getNeeded)).map(CompactingUtil.Result::getResult)
            .filter(stack -> !stack.isEmpty()).map(stack -> stack.copyWithCount(1)).toList();
        if (forms.size() < 2) {
            return List.of(new TransformHint.Compacting());
        }
        List<TransformHint> hints = new ArrayList<>();
        for (int index = 1; index < forms.size(); index++) {
            hints.add(new TransformHint.Convert(forms.get(index - 1), forms.get(index)));
        }
        return List.copyOf(hints);
    }

    @Override
    public PortReader reader(ServerLevel level, Optional<SlotWindow> window) {
        if (window.isPresent() || !valid(level)) {
            return PortReader.EMPTY;
        }
        List<PortReader.Entry> entries = List.of();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                entries = List.of(new PortReader.Entry(stack, stack.getCount()));
                break;
            }
        }
        var reading = new PortReader.PortReading(entries, Optional.empty(), Room.NONE, Set.of(), false);
        return new PortReader() {
            @Override
            public boolean step(int budget) {
                return true;
            }

            @Override
            public PortReading result() {
                return reading;
            }
        };
    }

    @Override
    public TransferResult take(ServerLevel level, List<Pull> requested, Optional<SlotWindow> window, boolean simulate) {
        return transfer(level, window, simulate, staged -> {
            List<ItemStack> moved = new ArrayList<>();
            for (Pull pull : requested) {
                long left = switch (pull) {
                    case Pull.Exact exact -> exact.stack().getCount();
                    case Pull.Matching matching -> matching.count();
                };
                for (int slot = 0; slot < staged.getSlots() && left > 0; slot++) {
                    ItemStack visible = staged.getStackInSlot(slot);
                    boolean matches = switch (pull) {
                        case Pull.Exact exact -> ItemStack.isSameItemSameComponents(visible, exact.stack());
                        case Pull.Matching matching -> Goods.matches(visible, matching.spec());
                    };
                    if (!matches) {
                        continue;
                    }
                    while (left > 0) {
                        ItemStack taken = staged.extractItem(slot, (int) Math.min(left, visible.getMaxStackSize()), false);
                        if (taken.isEmpty()) {
                            break;
                        }
                        moved.add(taken);
                        left -= taken.getCount();
                    }
                }
                if (left > 0) {
                    return new TransferResult.Refused(TransferResult.Why.NOTHING_TO_TAKE);
                }
            }
            return new TransferResult.Done(moved);
        });
    }

    @Override
    public TransferResult put(ServerLevel level, List<ItemStack> goods, Optional<SlotWindow> window, boolean simulate) {
        return transfer(level, window, simulate, staged -> {
            for (ItemStack stack : goods) {
                ItemStack remaining = stack.copy();
                for (int slot = 0; slot < staged.getSlots() && !remaining.isEmpty(); slot++) {
                    remaining = staged.insertItem(slot, remaining, false);
                }
                if (!remaining.isEmpty()) {
                    return new TransferResult.Refused(TransferResult.Why.NO_ROOM);
                }
            }
            return new TransferResult.Done(goods);
        });
    }

    private TransferResult transfer(ServerLevel level, Optional<SlotWindow> window, boolean simulate,
                                    Function<CompactingInventoryHandler, TransferResult> action) {
        if (!valid(level)) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }
        if (window.isPresent()) {
            return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
        }
        // Stage native insertion and extraction against a detached copy to retain its conversion arithmetic.
        int compactingSlots = handler.getSlots() - (handler.isVoid() ? 1 : 0);
        var staged = new CompactingInventoryHandler(compactingSlots) {
            @Override
            public void onChange() {
            }

            @Override
            public float getMultiplier() {
                return handler.getMultiplier();
            }

            @Override
            public boolean isVoid() {
                return handler.isVoid();
            }

            @Override
            public boolean isCreative() {
                return handler.isCreative();
            }

            @Override
            public boolean isLocked() {
                return handler.isLocked();
            }
        };
        var registries = level.registryAccess();
        staged.deserializeNBT(registries, handler.serializeNBT(registries));
        TransferResult result = action.apply(staged);
        if (result.done() && !simulate) {
            handler.deserializeNBT(registries, staged.serializeNBT(registries));
            handler.onChange();
            Ports.changed(level, anchor.block(level));
        }
        return result;
    }
}
