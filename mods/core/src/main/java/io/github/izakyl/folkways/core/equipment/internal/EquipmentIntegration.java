package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.colony.api.PackKeeps;
import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.equipment.api.EquipmentSuppliers;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.panel.PageKind;
import io.github.izakyl.folkways.core.front.api.ui.Bay;
import io.github.izakyl.folkways.core.front.api.ui.RegisteringUi;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;

/** One equipment profile per resident: slots worn and personal inventory supplied by Core. */
public final class EquipmentIntegration {

    private EquipmentIntegration() {
    }

    public static void install(IEventBus bus) {
        KitProviders.holding(EquipmentKits::held);
        KitProviders.worn(EquipmentKits::worn);
        EquipmentSuppliers.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "personal_equipment"),
            new PersonalEquipmentPreview());
        bus.addListener(Declaring.class, event -> {
            Equipment.register(EquipmentProfiles.ID, CarriedKit::armor);
            PackKeeps.add(CarriedKit::keeps);
            event.colony(EquipmentProfiles.ID, context -> {
                ProfileLedger ledger = new ProfileLedger(context.colony());
                context.share(ledger);
                context.tick(ledger.carried()::tick);
            });
        });
        bus.addListener(Registering.class, event -> {
            event.enrollment(EquipmentProfiles.ID, new Enrollment(List.of(), Schema.none(), List.of(
                PageKind.boarded(EquipmentProfiles.ID, "folkways.equipment.title",
                    ResourceLocation.withDefaultNamespace("armor_stand")).inNavigation())));
            event.facing(EquipmentProfiles.ID, colony -> colony.service(EquipmentProfiles.ID, ProfileLedger.class)
                .map(ProfileLedger::carried));
        });
        bus.addListener(RegisteringUi.class, event -> {
            event.page(EquipmentProfiles.ID, EquipmentPage::new);
            event.inlay(Bay.RESIDENT, EquipmentProfiles.ID, EquipmentInlay::new);
        });
    }

}
