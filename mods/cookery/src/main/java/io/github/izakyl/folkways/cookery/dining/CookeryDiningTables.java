package io.github.izakyl.folkways.cookery.dining;

import com.github.ysbbbbbb.kaleidoscopecookery.block.decoration.ChairBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.decoration.TableBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.entity.SitEntity;
import io.github.izakyl.folkways.plugins.person.living.DiningTables;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** Uses the native four display slots, keeping full item components and native rendering/save data. */
public final class CookeryDiningTables {
    private static boolean registered;
    private CookeryDiningTables() { }
    public static synchronized void register() {
        if (registered) { return; }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("folkways", "cookery_dining");
        DiningTables.register(id, new DiningTables.Table() {
            public boolean matches(ServerLevel level, BlockPos at) { return level.getBlockEntity(at) instanceof TableBlockEntity; }
            public Container inventory(ServerLevel level, BlockPos at) {
                return level.getBlockEntity(at) instanceof TableBlockEntity table ? new TableStore(table) : null;
            }
        });
        DiningTables.registerSeat(id, new DiningTables.Seat() {
            public boolean matches(ServerLevel level, BlockPos at) { return level.getBlockState(at).getBlock() instanceof ChairBlock; }
            public boolean available(ServerLevel level, BlockPos at, Mob diner) {
                return level.getEntitiesOfClass(SitEntity.class, new AABB(at)).stream()
                    .allMatch(seat -> seat.getPassengers().stream().allMatch(passenger -> passenger == diner));
            }
            public boolean sit(ServerLevel level, BlockPos at, Mob body) {
                if (!matches(level, at) || body.isPassenger() || !available(level, at, body)) { return false; }
                var existing = level.getEntitiesOfClass(SitEntity.class, new AABB(at));
                boolean created = existing.isEmpty();
                SitEntity seat = created ? new SitEntity(level, at, 0.5125) : existing.getFirst();
                seat.setYRot(level.getBlockState(at).getValue(ChairBlock.FACING).toYRot());
                if (created && !level.addFreshEntity(seat)) { return false; }
                if (!body.startRiding(seat, true)) { if (created) { seat.discard(); } return false; }
                return true;
            }
        });
        registered = true;
    }
    private static final class TableStore implements Container {
        private final TableBlockEntity table;
        TableStore(TableBlockEntity table) { this.table = table; }
        public int getContainerSize() { return table.getItems().getSlots(); }
        public boolean isEmpty() {
            for (int slot = 0; slot < getContainerSize(); slot++) { if (!getItem(slot).isEmpty()) { return false; } }
            return true;
        }
        public int getMaxStackSize() { return 1; }
        public int getMaxStackSize(ItemStack stack) { return edible(stack) ? 1 : 0; }
        public ItemStack getItem(int slot) { return table.getItems().getStackInSlot(slot); }
        public ItemStack removeItem(int slot, int count) {
            ItemStack taken = table.getItems().extractItem(slot, count, false);
            if (!taken.isEmpty()) { setChanged(); }
            return taken;
        }
        public ItemStack removeItemNoUpdate(int slot) { return removeItem(slot, Integer.MAX_VALUE); }
        public void setItem(int slot, ItemStack stack) {
            if (!stack.isEmpty() && (stack.getCount() > 1 || !edible(stack))) {
                throw new IllegalArgumentException("dining table display accepts one food per slot");
            }
            table.getItems().setStackInSlot(slot, stack);
            setChanged();
        }
        public boolean canPlaceItem(int slot, ItemStack stack) { return edible(stack); }
        private static boolean edible(ItemStack stack) { return !stack.isEmpty() && stack.getFoodProperties(null) != null; }
        public void setChanged() {
            // The native inventory mutation has already settled. A failing notification must not make
            // Commit refund a delivered portion or let an eating action replay its removed food.
            table.setChanged();
            try { table.refresh(); }
            catch (RuntimeException | LinkageError broken) {
                org.slf4j.LoggerFactory.getLogger("folkways-dining")
                    .warn("dining table refresh failed after its inventory settled at {}", table.getBlockPos(), broken);
            }
        }
        public boolean stillValid(Player player) { return Container.stillValidBlockEntity(table, player); }
        public void clearContent() {
            for (int slot = 0; slot < getContainerSize(); slot++) { table.getItems().setStackInSlot(slot, ItemStack.EMPTY); }
            setChanged();
        }
    }
}
