package io.github.izakyl.folkways.core.shell.accessories;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.KitChecks;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class AccessoriesGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        io.wispforest.accessories.api.AccessoriesAPI.registerAccessory(Items.DIAMOND,
            new io.wispforest.accessories.api.Accessory() {
                @Override
                public boolean canEquip(ItemStack stack, io.wispforest.accessories.api.slot.SlotReference reference) {
                    return !reference.entity().getPersistentData().getBoolean("deny_test_accessory");
                }

                @Override
                public void tick(ItemStack stack, io.wispforest.accessories.api.slot.SlotReference reference) {
                    reference.entity().addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 40));
                }
            });
        io.github.izakyl.folkways.core.testing.SelectedTests.register(event, AccessoriesGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void razingColonyDropsWornBackpackWithItsContents(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.moveTo(helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1)).getBottomCenter());
        helper.getLevel().addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        var gear = GearSlots.slot(ResourceLocation.parse("accessories:back/0")).orElseThrow();
        ItemStack bag = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.parse("sophisticatedbackpacks:backpack")));
        var inventory = io.github.izakyl.folkways.core.resident.api.KitProviders.provider(bag).orElseThrow()
            .inventory(bag).orElseThrow();
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 7));
        gear.equip(body, bag);
        colony.raze();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(body.isRemoved(), "resident leaves razed colony"))
            .thenExecute(() -> {
                var drops = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    body.getBoundingBox().inflate(4)).stream().map(entity -> entity.getItem())
                    .filter(stack -> stack.is(bag.getItem())).toList();
                helper.assertTrue(drops.size() == 1, "one physical backpack drops");
                var contents = io.github.izakyl.folkways.core.resident.api.KitProviders.provider(drops.getFirst())
                    .orElseThrow().inventory(drops.getFirst()).orElseThrow();
                helper.assertTrue(contents.getStackInSlot(0).getCount() == 7,
                    "native backpack contents remain inside the dropped root");
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void deathDropsNativeAccessoryExactlyOnce(GameTestHelper helper) {
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.moveTo(helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1)).getBottomCenter());
        helper.getLevel().addFreshEntity(body);
        var gear = GearSlots.slot(ResourceLocation.parse("accessories:folkways_test/0")).orElseThrow();
        gear.equip(body, new ItemStack(Items.DIAMOND));
        body.hurt(body.damageSources().genericKill(), Float.MAX_VALUE);
        helper.assertTrue(gear.worn(body).isEmpty(), "death drains the real accessory before native drop events");
        long count = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            body.getBoundingBox().inflate(4)).stream().map(entity -> entity.getItem())
            .filter(stack -> stack.is(Items.DIAMOND)).mapToLong(ItemStack::getCount).sum();
        helper.assertTrue(count == 1, "native death handling cannot double the drained item");
        body.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "slot_reload", timeoutTicks = 600)
    public static void dataPackReloadResizesAlreadyLoadedResidents(GameTestHelper helper) {
        io.github.izakyl.folkways.core.testing.AccessoryReloadChecks.check(helper, "accessories");
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 80)
    public static void datapackCountsValidationAndRealResidentEffects(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.moveTo(helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1)).getBottomCenter());
        helper.getLevel().addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        Cleanup.on(helper, () -> {
            body.discard();
            colony.raze();
        });
        var profiles = EquipmentProfiles.of(colony);
        var profile = profiles.create("Framework rules");
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
        var id = ResourceLocation.parse("accessories:folkways_test/2");
        var gear = GearSlots.slot(id).orElseThrow();
        helper.assertTrue(GearSlots.slot(ResourceLocation.parse("accessories:folkways_test/0")).isPresent()
            && GearSlots.slot(ResourceLocation.parse("accessories:folkways_test/3")).isEmpty(),
            "data-pack count three generates exactly three indexed slots");
        helper.assertTrue(gear.fits(new ItemStack(Items.DIAMOND)) && !gear.fits(new ItemStack(Items.DIRT)),
            "entity-free picker uses the native slot tag");
        boolean rejected = false;
        try {
            profiles.put(profile.id(), id, ItemSpec.of(Items.DIRT));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "saving illegal goods is rejected on the server");
        body.getPersistentData().putBoolean("deny_test_accessory", true);
        rejected = false;
        try {
            profiles.put(profile.id(), id, ItemSpec.of(Items.DIAMOND));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "saving also calls the loaded resident's canEquip rule");
        body.getPersistentData().remove("deny_test_accessory");
        profiles.put(profile.id(), id, ItemSpec.of(Items.DIAMOND));
            if (net.neoforged.fml.ModList.get().isLoaded("cclayer")) {
                helper.assertTrue(GearSlots.all().stream().noneMatch(slot -> slot.id().getNamespace().equals("curios")),
                    "the compatibility layer presents exactly one physical framework");
            }
        gear.equip(body, new ItemStack(Items.DIAMOND));
        helper.assertTrue(gear.worn(body).is(Items.DIAMOND), "the real capability owns the accessory");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            body.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED),
                "the framework ticks resident effects"))
            .thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void backpackIsFetchedIntoNativeBackSlotAndReturnsChildren(GameTestHelper helper) {
        ItemStack bag = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:backpack")));
        ItemStack upgrade = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.parse("sophisticatedbackpacks:jukebox_upgrade")));
        KitChecks.residentFetchesAndReturns(helper, bag, upgrade, false, new ItemStack(Items.MUSIC_DISC_13),
            body -> helper.assertTrue(body.pack().getContainerSize() > 18, "a genuinely worn back-slot bag adds cells"),
            ResourceLocation.parse("accessories:back/0"));
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 200)
    public static void severalBackpacksMatchNativePlayerPriority(GameTestHelper helper) {
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(helper.getLevel().getGameTime() > 100,
            "native identifier cache is ready"))
            .thenExecute(() -> io.github.izakyl.folkways.core.testing.BackpackRulesChecks.check(helper,
                ResourceLocation.parse("accessories:back/0")))
            .thenSucceed();
    }
}
