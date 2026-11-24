package io.github.izakyl.folkways.core.shell.mixin;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CoreMixinPlugin extends FolkwaysMixinPlugin {

    @Override
    protected boolean enabled(String mixin) {
        switch (mixin) {
            case "ChiseledBlockEntityMixin", "MutableStatisticsMixin" -> {
                return OptionalIntegrations.CHISELS_AND_BITS.enabled();
            }
            case "AnimationManagerEvidenceMixin", "EpicFightNetworkManagerEvidenceMixin",
                 "MinecraftReloadPairingGuardMixin", "AnimationManagerPairingGuardMixin" -> {
                return OptionalIntegrations.EPIC_FIGHT.enabled();
            }
            default -> {
            }
        }
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
            Map.entry("TomsTerminalMenuAccessor", "toms_storage"),
            Map.entry("Ae2CraftingInsertMixin", "ae2"),
            Map.entry("Rs2CraftingInsertMixin", "refinedstorage"),
            Map.entry("Ae2CommissionMixin", "ae2"),
            Map.entry("Ae2TerminalMenuMixin", "ae2"),
            Map.entry("Ae2TerminalScreenMixin", "ae2"),
            Map.entry("Rs2CommissionMixin", "refinedstorage"),
            Map.entry("Rs2TaskSnapshotMixin", "refinedstorage"),
            Map.entry("Rs2TerminalMenuMixin", "refinedstorage"),
            Map.entry("Rs2MenuOpenerMixin", "refinedstorage"),
            Map.entry("Rs2GridDataMixin", "refinedstorage"),
            Map.entry("Rs2RepositoryAccessor", "refinedstorage"),
            Map.entry("Rs2TerminalScreenMixin", "refinedstorage"));
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of();
    }
}
