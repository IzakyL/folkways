package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.List;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class EquipmentKitSourceGameTests {
    private static final ResourceLocation PARENT = ResourceLocation.withDefaultNamespace("flower_pot");
    private static final ResourceLocation CHILD = ResourceLocation.withDefaultNamespace("brick");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(EquipmentKitSourceGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void absentAccessoryBranchIsSavedButNeverSupplied(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.create("Absent accessory");
            var root = EquipmentProfile.Entry.slotted(ResourceLocation.parse("curios:ring/0"),
                ItemSpec.of(Items.DIAMOND));
            profiles.add(profile.id(), root);
            helper.assertFalse(root.available(), "a missing framework never invents a slot");
            helper.assertTrue(CarriedKit.entries(Optional.of(profiles.get(profile.id()).orElseThrow())).isEmpty(),
                "no supply is offered for an absent slot");
            profiles.replace(profiles.get(profile.id()).orElseThrow().riding(true));
            var restored = new ProfileLedger(colony).get(profile.id()).orElseThrow();
            helper.assertValueEqual(restored.entries(), List.of(root), "the absent slot entry survives saving");
            helper.assertTrue(restored.riding(), "riding is a separate optional profile flag");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }
}
