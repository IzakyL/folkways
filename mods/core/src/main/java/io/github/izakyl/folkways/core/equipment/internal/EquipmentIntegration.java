package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.colony.api.PackKeeps;
import io.github.izakyl.folkways.core.combat.api.VehicleProfiles;
import io.github.izakyl.folkways.core.combat.internal.VehicleProfilePage;
import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.equipment.api.EquipmentSuppliers;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.ui.Bay;
import io.github.izakyl.folkways.core.front.api.ui.Editor;
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
            event.enrollment(VehicleProfiles.ID, new Enrollment(List.of(), Schema.none()));
            event.enrollment(EquipmentProfiles.ID, new Enrollment(List.of(), Schema.none()));
        });
        bus.addListener(RegisteringUi.class, event -> {
            event.inlay(Bay.RESIDENT, EquipmentProfiles.ID, EquipmentInlay::new);
            event.editor(ColonyPanel.Page.RESIDENTS, new Editor(EquipmentProfiles.ID, "folkways.equipment.title",
                ResourceLocation.withDefaultNamespace("armor_stand"), 560, 330, EquipmentPage::new));
            event.editor(ColonyPanel.Page.RESIDENTS, new Editor(VehicleProfiles.ID, "combat.vehicle.profile",
                ResourceLocation.withDefaultNamespace("minecart"), 420, 300, VehicleProfilePage::new));
        });
    }

}
