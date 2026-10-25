package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WeaponSelectionGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(WeaponSelectionGameTests.class); }

    @GameTest(template = "empty_large")
    public static void anUnarmedPlanningReadCannotHideARealWeaponSuppliedLaterInTheSameTick(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            helper.assertFalse(Weapons.armed(s.worker), "the actual initially empty pack is unarmed");
            // No world tick, fake pack, personal flag or explicit acquisition between these reads.
            ItemStack actual = s.put(0, Items.IRON_SWORD, "same tick self-defence supply", 0, null);
            helper.assertTrue(Weapons.armed(s.worker), "a negative planning hint cannot conceal a newly supplied real weapon");
            try (WeaponSession ignored = s.open()) {
                helper.assertTrue(s.body.getMainHandItem() == actual, "self-defence may immediately borrow the supplied actual stack");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void aLaterHighDpsAxeBeatsAnEarlierWeakSwordAndReturnsItsActualComponents(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            s.put(0, Items.WOODEN_SWORD, "weak first sword", 2, "spare");
            ItemStack axe = s.put(1, Items.DIAMOND_AXE, "real later axe", 17, "spare");
            try (WeaponSession ignored = s.open()) {
                helper.assertTrue(s.body.getMainHandItem() == axe, "selection borrows the high-DPS axe's actual reference, not the first sword");
                helper.assertTrue(s.body.pack().getItem(1).isEmpty(), "the borrowed axe has no pack duplicate");
                helper.assertValueEqual(s.body.getMainHandItem().getDamageValue(), 17, "the source's wear is unchanged on acquisition");
            }
            helper.assertValueEqual(s.body.pack().count(Items.DIAMOND_AXE), 1, "close returns one physical axe");
            helper.assertTrue(s.body.pack().contents().stream().anyMatch(item -> item.getHoverName().getString().equals("real later axe") && item.getDamageValue() == 17), "the same named components return");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void swordAttackSpeedCanBeatTheHigherPerHitDamageOfAnIronAxe(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            s.put(0, Items.IRON_AXE, "slow large hit", 0, "spare");
            ItemStack sword = s.put(1, Items.IRON_SWORD, "faster sustained hits", 0, "spare");
            try (WeaponSession ignored = s.open()) {
                helper.assertTrue(s.body.getMainHandItem() == sword, "damage times actual speed, rather than raw hit damage or pack order, selects the sword");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void sharpnessComponentsCanMakeAWoodenSwordBeatAnUnenchantedIronSword(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            s.put(0, Items.IRON_SWORD, "plain iron", 0, "spare");
            ItemStack enchanted = s.put(1, Items.WOODEN_SWORD, "sharp actual wood", 3, "spare");
            enchanted.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 5);
            var components = enchanted.getComponents();
            try (WeaponSession ignored = s.open()) {
                helper.assertTrue(s.body.getMainHandItem() == enchanted, "target-independent enchantment damage changes the sustained ranking");
                helper.assertValueEqual(s.body.getMainHandItem().getComponents(), components, "scoring and borrowing do not strip or rewrite enchantments");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void anUnlockedAssignmentDoesNotSuppressAUsableRangedSpare(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            ItemStack assigned = s.put(0, Items.DIAMOND_SWORD, "default assigned sword", 5, "mainhand");
            ItemStack bow = s.put(1, Items.BOW, "usable ranged spare", 8, "spare");
            s.arrows(2, 2);
            try (WeaponSession ignored = s.open()) {
                helper.assertTrue(s.body.getMainHandItem() == bow, "an actually usable ranged item outranks an unlocked assigned melee item");
                helper.assertTrue(ResidentEquipment.assigned(s.body) == assigned, "automatic acquisition does not rewrite the keeper's assignment");
            }
            helper.assertValueEqual(s.body.pack().count(Items.BOW), 1, "the spare bow returns once");
            helper.assertValueEqual(s.body.pack().count(Items.ARROW), 2, "selection itself neither consumes nor creates ammunition");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void anEmptyAssignedBowFallsBackToAnActualAxeButFreshAmmoIsSeenOnTheNextAcquisition(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            s.put(0, Items.BOW, "empty assigned bow", 0, "mainhand");
            ItemStack axe = s.put(1, Items.IRON_AXE, "real fallback", 0, "spare");
            helper.assertFalse(Weapons.ranged(s.worker), "planning does not prefer an empty bow");
            try (WeaponSession ignored = s.open()) { helper.assertTrue(s.body.getMainHandItem() == axe, "empty ammunition capacity cannot suppress a usable axe"); }
            s.arrows(2, 1);
            try (WeaponSession ignored = s.open()) { helper.assertTrue(s.body.getMainHandItem().is(Items.BOW), "opening a new session rescans fresh ammunition, even within the planning-cache window"); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void aRealLoadedCrossbowCanWinWithoutInventingReserveAmmunition(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            s.put(0, Items.NETHERITE_AXE, "melee spare", 0, "spare");
            ItemStack crossbow = s.put(1, Items.CROSSBOW, "real loaded crossbow", 6, "spare");
            ItemStack arrow = new ItemStack(Items.ARROW); arrow.set(DataComponents.CUSTOM_NAME, Component.literal("paid loaded arrow"));
            crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(arrow));
            try (WeaponSession ignored = s.open()) {
                helper.assertTrue(s.body.getMainHandItem() == crossbow, "the paid loaded projectile supplies real ranged readiness");
                helper.assertValueEqual(s.body.pack().count(Items.ARROW), 0, "selection creates no fictitious reserve arrows");
            }
            helper.assertValueEqual(crossbow.get(DataComponents.CHARGED_PROJECTILES).getItems().getFirst().getHoverName(), arrow.getHoverName(), "close preserves the actual loaded components");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void powerAndQuickChargeRankActualRangedCopiesByTheirNativeCycles(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            s.put(0, Items.BOW, "plain first bow", 0, "spare");
            ItemStack power = s.put(1, Items.BOW, "powered later bow", 9, "spare");
            power.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.POWER), 3);
            s.arrows(2, 2);
            try (WeaponSession ignored = s.open()) { helper.assertTrue(s.body.getMainHandItem() == power, "Power increases a real full-draw bow's estimated shot damage"); }
            s.body.pack().clearContent();
            s.put(0, Items.CROSSBOW, "ordinary charge", 0, "spare");
            ItemStack quick = s.put(1, Items.CROSSBOW, "actual quicker charge", 4, "spare");
            quick.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.QUICK_CHARGE), 3);
            s.arrows(2, 2);
            try (WeaponSession ignored = s.open()) { helper.assertTrue(s.body.getMainHandItem() == quick, "the actual native enchanted charge duration improves sustained DPS"); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void manualLockBeatsEveryBetterCandidateAndAnEmptyLockForbidsFallback(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            ItemStack locked = s.put(0, Items.WOODEN_SWORD, "keeper's exact choice", 1, "mainhand");
            s.put(1, Items.NETHERITE_AXE, "higher melee", 0, "spare");
            s.put(2, Items.BOW, "usable ranged", 0, "spare"); s.arrows(3, 2);
            Equipment.lock(s.body, EquipmentSlot.MAINHAND, true);
            try (WeaponSession ignored = s.open()) { helper.assertTrue(s.body.getMainHandItem() == locked, "a manual lock outranks both ranged readiness and damage"); }
            for (int cell = 0; cell < s.body.pack().getContainerSize(); cell++) {
                if (PersonalItems.purpose(s.body, s.body.pack().getItem(cell)).filter("mainhand"::equals).isPresent()) s.body.pack().removeItemNoUpdate(cell);
            }
            helper.assertTrue(Weapons.open(s.worker).isEmpty(), "an empty locked slot permits no armed or barehanded fallback");
            helper.assertFalse(Weapons.armed(s.worker), "planning also recognizes the intentional empty lock");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void foreignWeaponsBorrowedToolsAndProductionCargoCannotWinAutomaticSelection(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            ItemStack own = s.put(0, Items.WOODEN_SWORD, "own personal fallback", 0, "spare");
            ResidentEntity other = PersonBody.RESIDENT.get().create(helper.getLevel());
            ItemStack foreign = s.put(1, Items.BOW, "foreign bow", 0, "spare"); PersonalItems.mark(other, foreign, "spare");
            ItemStack borrowed = s.put(2, Items.NETHERITE_AXE, "borrowed axe", 0, "spare");
            s.put(3, Items.DIAMOND_SWORD, "unmarked work cargo", 0, null); s.arrows(4, 3);
            UUID task = UUID.randomUUID(); TaskTools.keep(task, s.body.id(), actual -> actual == borrowed);
            s.body.setDoing(Doing.open(ResourceLocation.fromNamespaceAndPath("folkways", "hauling")));
            try (WeaponSession ignored = s.open()) {
                helper.assertTrue(s.body.getMainHandItem() == own, "ordinary production can defend only with its safe personal weapon");
                helper.assertTrue(s.body.pack().getItem(1) == foreign && s.body.pack().getItem(2) == borrowed, "foreign property and actual task loan stay untouched");
            } finally { TaskTools.release(task); s.body.clearDoing(); other.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void foreignReserveOrForeignLoadedArrowsCannotMakeAnEmptyRangedWeaponWin(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            ItemStack axe = s.put(0, Items.IRON_AXE, "safe melee", 0, "spare");
            s.put(1, Items.BOW, "bow with foreign reserve", 0, "spare");
            ItemStack crossbow = s.put(2, Items.CROSSBOW, "crossbow with foreign loaded unit", 0, "spare");
            ResidentEntity other = PersonBody.RESIDENT.get().create(helper.getLevel());
            ItemStack foreign = new ItemStack(Items.ARROW, 3); PersonalItems.mark(other, foreign, "ammo");
            s.body.pack().setItem(3, foreign); crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(foreign.copyWithCount(1)));
            try (WeaponSession ignored = s.open()) { helper.assertTrue(s.body.getMainHandItem() == axe, "only real usable owned ammunition can outrank melee"); }
            finally { other.discard(); }
            helper.assertValueEqual(foreign.getCount(), 3, "selection never spends the foreign reserve");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 60)
    public static void theChosenSlowAxeUsesItsActualAttackPeriodAndOneNativeDurabilityHit(GameTestHelper helper) {
        Scene s = new Scene(helper);
        s.put(0, Items.WOODEN_SWORD, "weaker sword", 0, "spare");
        ItemStack axe = s.put(1, Items.DIAMOND_AXE, "chosen physical axe", 12, "spare");
        var target = EntityType.ZOMBIE.create(helper.getLevel()); target.setNoAi(true);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        target.getAttribute(Attributes.ARMOR).setBaseValue(0);
        target.moveTo(s.body.getX() + 1.5, s.body.getY(), s.body.getZ()); helper.getLevel().addFreshEntity(target);
        WeaponSession session = s.open(); float health = target.getHealth();
        helper.assertTrue(s.body.getMainHandItem() == axe && session.fire(target), "the automatically selected actual axe starts a real native swing");
        helper.onEachTick(session::tick);
        helper.runAfterDelay(12, () -> helper.assertValueEqual(target.getHealth(), health, "a slow axe cannot hit on the old fixed ten-tick sword clock"));
        helper.runAfterDelay(30, () -> {
            try {
                helper.assertTrue(target.getHealth() < health, "the real hit eventually lands after the item's attack period");
                session.close(); session.close();
                helper.assertValueEqual(s.body.pack().count(Items.DIAMOND_AXE), 1, "one real axe returns once after real combat");
                helper.assertTrue(s.body.pack().contents().stream().anyMatch(actual -> actual.getHoverName().getString().equals("chosen physical axe") && actual.getDamageValue() > 12), "native durability is written onto the original named item");
            } finally { target.discard(); s.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large")
    public static void closingAndSavingAnAutomaticallySelectedBowCannotDuplicateOrRetargetItsLoan(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            s.put(0, Items.IRON_SWORD, "unlocked default", 2, "mainhand");
            s.put(1, Items.BOW, "actual selected bow", 9, "spare"); s.arrows(2, 3);
            WeaponSession first = s.open();
            helper.assertTrue(Weapons.open(s.worker).isEmpty(), "a new acquisition cannot steal the active actual hand");
            CompoundTag saved = s.body.saveWithoutId(new CompoundTag());
            helper.assertValueEqual(first.state(), WeaponSession.State.CLOSED, "save revokes the borrowed weapon before serializing the pack");
            s.body.load(saved); first.close(); first.tick();
            helper.assertValueEqual(s.body.pack().count(Items.BOW), 1, "saving and closing the old callback leave one real bow");
            helper.assertValueEqual(s.body.pack().count(Items.IRON_SWORD), 1, "the unselected default was not consumed");
            try (WeaponSession second = s.open()) {
                helper.assertTrue(s.body.getMainHandItem().is(Items.BOW) && s.body.getMainHandItem().getDamageValue() == 9, "the new command acquires the saved actual bow with its exact wear");
                first.close(); helper.assertTrue(s.body.hasWeaponSession(), "a stale close cannot revoke the replacement lease");
            }
            helper.assertValueEqual(s.body.pack().count(Items.ARROW), 3, "acquisition and save do not fire or invent ammunition");
        }
        helper.succeed();
    }

    private static final class Scene implements AutoCloseable {
        final ResidentEntity body;
        final Worker worker;
        Scene(GameTestHelper helper) {
            for (int x = 0; x < 8; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            body = PersonBody.RESIDENT.get().create(helper.getLevel()); body.setNoAi(true);
            BlockPos feet = helper.absolutePos(new BlockPos(2, 1, 2)); body.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
            helper.getLevel().addFreshEntity(body);
            worker = new Worker() {
                public Resident resident() { return body.resident(); }
                public Mob body() { return body; }
                public Container pack() { return body.pack(); }
                public List<Store> within() { return List.of(); }
                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                public void spill(ItemStack stack) { body.spawnAtLocation(stack); }
                public Effects effects() { return body.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }
        ItemStack put(int cell, Item item, String name, int wear, String purpose) {
            ItemStack actual = new ItemStack(item); actual.set(DataComponents.CUSTOM_NAME, Component.literal(name)); actual.setDamageValue(wear);
            if (purpose != null) PersonalItems.mark(body, actual, purpose);
            body.pack().setItem(cell, actual); return body.pack().getItem(cell);
        }
        void arrows(int cell, int count) { ItemStack actual = new ItemStack(Items.ARROW, count); PersonalItems.mark(body, actual, "ammo"); body.pack().setItem(cell, actual); }
        WeaponSession open() { return Weapons.open(worker).orElseThrow(() -> new IllegalStateException("the real fixture must have a usable safe weapon")); }
        @Override public void close() { body.stopWeaponSession(); body.discard(); TaskTools.forget(body.id()); }
    }
}
