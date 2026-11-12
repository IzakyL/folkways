package io.github.izakyl.folkways.core.shell.mixin;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CoreMixinPlugin extends FolkwaysMixinPlugin {

    @Override
    protected boolean enabled(String mixin) {
        return !mixin.equals("CuriosResidentSlotsMixin")
            || net.neoforged.fml.loading.LoadingModList.get().getModFileById("cclayer") == null;
    }

    @Override
    protected Set<String> createOnly() {
        return Set.of();
    }

    @Override
    protected Map<String, String> modOnly() {
        return Map.ofEntries(
            Map.entry("SableAssemblyMixin", "sable"),
            Map.entry("CuriosResidentSlotsMixin", "curios"),
            Map.entry("AccessoriesResidentSlotsMixin", "accessories"),
            Map.entry("AccessoriesHolderAccessor", "accessories"),
            Map.entry("BackpackInventoryProviderAccessor", "sophisticatedbackpacks"),
            Map.entry("DrawerControllerAccessor", "storagedrawers"),
            Map.entry("DrawerSlotAccessor", "storagedrawers"),
            Map.entry("TomsInventoryAccessor", "toms_storage"),
            Map.entry("Ae2TerminalMenuMixin", "ae2"),
            Map.entry("Ae2CraftingInsertMixin", "ae2"),
            Map.entry("Ae2CommissionMixin", "ae2"),
            Map.entry("Rs2CraftingInsertMixin", "refinedstorage"),
            Map.entry("Rs2CommissionMixin", "refinedstorage"),
            Map.entry("Ae2TerminalScreenMixin", "ae2"),
            Map.entry("Rs2MenuOpenerMixin", "refinedstorage"),
            Map.entry("Rs2GridDataMixin", "refinedstorage"),
            Map.entry("Rs2TaskSnapshotMixin", "refinedstorage"),
            Map.entry("Rs2RepositoryAccessor", "refinedstorage"),
            Map.entry("Rs2TerminalMenuMixin", "refinedstorage"),
            Map.entry("Rs2TerminalScreenMixin", "refinedstorage"));
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of(
            Degradable.common("LevelMixin", "net.minecraft.world.level.Level",
                "a write into a colony's member block no longer reaches the colony, so goods put into a "
                    + "chest are seen only once something else changes that colony, and a block change no "
                    + "longer dirties the reachability graph, so it is rebuilt only when a body wanders "
                    + "off it or a passage moves"));
    }
}
