package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.resident.internal.BodyState;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class KitGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(KitGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void personalDeliveriesStayStableAndCountLoadedAmmunition(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        helper.getLevel().addFreshEntity(body);
        colony.hold(PersonContent.ID,
            Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        try {
            var profiles = (ProfileLedger) EquipmentProfiles.of(colony);
            var profile = profiles.create("Personal stock");
            profiles.add(profile.id(), EquipmentProfile.Entry.carried(
                ItemSpec.of(Items.ARROW), 8, 4));
            profiles.add(profile.id(), EquipmentProfile.Entry.carried(ItemSpec.of(Items.CROSSBOW), 1, 1));
            profiles.put(profile.id(), GearSlots.HEAD,
                ItemSpec.of(Items.IRON_HELMET));
            profiles.assign(List.of(body.id()), Optional.of(profile.id()));
            var carried = profiles.carried();
            var view = colony.view(helper.getLevel());
            carried.reconcile(body);
            var first = carried.goals(view).stream().flatMap(goal -> goal.nodes().stream()).toList();
            helper.assertValueEqual(first.size(), 2, "ammunition waits for the missing weapon");
            carried.reconcile(body);
            var next = carried.goals(view).stream().flatMap(goal -> goal.nodes().stream()).toList();
            helper.assertTrue(first.equals(next),
                "unchanged shortages keep their exact personal delivery identities");
            helper.assertTrue(profiles.supplying(body.id(), first.stream().map(node -> node.id()).toList()),
                "patrol can recognize the same completions after delivery refinement");
            var crossbow = new ItemStack(Items.CROSSBOW);
            crossbow.set(DataComponents.CHARGED_PROJECTILES,
                ChargedProjectiles.of(new ItemStack(Items.ARROW)));
            body.pack().insert(crossbow);
            body.pack().insert(new ItemStack(Items.ARROW, 2));
            carried.reconcile(body);
            var ammo = carried.goals(view).stream().flatMap(goal -> goal.nodes().stream())
                .filter(node -> node instanceof Delivery delivery
                    && delivery.goods().spec().equals(ItemSpec.of(Items.ARROW)))
                .map(Delivery.class::cast).findFirst().orElseThrow();
            helper.assertValueEqual(ammo.goods().count(), 5L, "two loose and one loaded arrow leave five missing");
            profiles.assign(List.of(body.id()), Optional.empty());
            helper.assertTrue(carried.goals(view).isEmpty(), "unassignment withdraws every personal demand");
        } finally {
            body.discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void oldDevelopmentKitReturnsEveryStackAndDropsOverflow(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.moveTo(helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1)).getBottomCenter());
        helper.getLevel().addFreshEntity(body);
        try {
            BodyState state = new BodyState();
            for (int slot = 0; slot < 18; slot++) {
                state.pack().setItem(slot, new ItemStack(Items.STONE, 64));
            }
            CompoundTag saved = state.serializeNBT(registries);
            CompoundTag kit = new CompoundTag();
            var items = new net.minecraft.nbt.ListTag();
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            sword.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Recovered"));
            items.add(sword.save(registries));
            kit.put("items", items);
            saved.put("Kit", kit);
            state.deserializeNBT(registries, saved);
            state.pack().bind(body);
            helper.assertFalse(state.serializeNBT(registries).contains("Kit"), "the old field is never written");
            var drops = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                body.getBoundingBox().inflate(1), item -> ItemStack.matches(item.getItem(), sword));
            helper.assertValueEqual(drops.size(), 1, "a full pack drops exactly the original named sword");
            drops.forEach(net.minecraft.world.entity.Entity::discard);
            state.pack().clearContent();
            state.deserializeNBT(registries, saved);
            state.pack().clearContent();
            state.pack().bind(body);
            helper.assertValueEqual(state.pack().count(Items.IRON_SWORD), 1, "available space receives the old item");
        } finally {
            body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void inventoryEntriesStayPersonalAndAreExcludedFromCargo(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        helper.getLevel().addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        try {
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.create("Personal bread");
            profiles.add(profile.id(), EquipmentProfile.Entry.carried(ItemSpec.of(Items.BREAD), 4, 2));
            profiles.assign(List.of(body.id()), Optional.of(profile.id()));
            body.pack().insert(new ItemStack(Items.BREAD, 4));
            body.pack().insert(new ItemStack(Items.STONE, 3));
            colony.inventory().ensureObservedResident(body.id());
            var kit = io.github.izakyl.folkways.core.work.internal.Kit.of(colony, body);
            helper.assertTrue(kit.cargoIn(body).stream().noneMatch(stack -> stack.is(Items.BREAD)),
                "personal bread is neither spendable stock nor unloadable cargo");
            var space = io.github.izakyl.folkways.core.plan.api.PackSpace.of(body.pack(), kit.cargoIn(body));
            helper.assertValueEqual(space.size(), 17, "the personal cell is never promised to cargo");
            helper.assertValueEqual(body.pack().count(Items.BREAD), 4, "personal items remain in ordinary inventory");
            body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            helper.assertTrue(((ProfileLedger) profiles).carried().lookLines(body.id(), colony.view(helper.getLevel()))
                .isEmpty(), "equipment and inventory supplies never add lines to resident information cards");
        } finally {
            body.discard();
            colony.raze();
        }
        helper.succeed();
    }
}
