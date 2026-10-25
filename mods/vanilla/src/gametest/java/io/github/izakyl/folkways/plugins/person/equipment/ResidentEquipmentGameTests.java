package io.github.izakyl.folkways.plugins.person.equipment;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentEquipmentGameTests {
    private static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    private static final List<Item> ITEMS = List.of(Items.IRON_SWORD, Items.SHIELD,
        Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(ResidentEquipmentGameTests.class); }

    @GameTest(template = "empty_large")
    public static void sixRealSlotsPreserveComponentsAndReturnReplacementsToThePlayer(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            for (int index = 0; index < SLOTS.size(); index++) {
                EquipmentSlot slot = SLOTS.get(index);
                ItemStack original = named(ITEMS.get(index), "first " + slot.getName(), 7);
                f.player.getInventory().setItem(0, original);
                f.equip(slot, "player", 0);
                ItemStack equipped = assigned(f.body, slot);
                helper.assertTrue(equipped.is(ITEMS.get(index)), "the real item reaches " + slot);
                helper.assertValueEqual(equipped.getDamageValue(), 7, "native durability survives equip");
                helper.assertValueEqual(equipped.get(DataComponents.CUSTOM_NAME), original.get(DataComponents.CUSTOM_NAME), "custom names survive equip");
                helper.assertTrue(PersonalItems.purpose(f.body, equipped).filter(slot.getName()::equals).isPresent(), "the real item records its designated slot");
                helper.assertTrue(Equipment.locked(f.body, slot), "a manual assignment defaults to locked");
                helper.assertTrue(f.player.getInventory().getItem(0).isEmpty(), "the player has no duplicate");
                if (slot == EquipmentSlot.MAINHAND) {
                    helper.assertTrue(f.body.getMainHandItem().isEmpty(), "designating a weapon leaves the real hand available to work");
                    helper.assertValueEqual(f.body.pack().count(Items.IRON_SWORD), 1, "one designated weapon lives in the pack");
                }
                f.player.getInventory().setItem(0, named(ITEMS.get(index), "second " + slot.getName(), 13));
                f.equip(slot, "player", 0);
                ItemStack returned = f.player.getInventory().getItem(0);
                helper.assertValueEqual(returned.get(DataComponents.CUSTOM_NAME), original.get(DataComponents.CUSTOM_NAME), "replaced equipment returns to the source player's freed slot");
                helper.assertValueEqual(returned.getDamageValue(), 7, "return does not reset durability");
                helper.assertFalse(PersonalItems.marked(returned), "the returned player item has no resident ownership marker");
                helper.assertValueEqual(assigned(f.body, slot).getDamageValue(), 13, "the replacement is the second physical item");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void removingAResidentSourceFirstMakesRoomForTheOldArmor(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.player.getInventory().setItem(0, named(Items.IRON_HELMET, "old helmet", 5));
            f.equip(EquipmentSlot.HEAD, "player", 0);
            fillPack(f.body);
            ItemStack replacement = named(Items.DIAMOND_HELMET, "pack helmet", 9);
            PersonalItems.mark(f.body, replacement, "spare");
            f.body.pack().setItem(0, replacement);
            f.equip(EquipmentSlot.HEAD, "resident", 0);
            helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "a full pack can swap using the source cell it just freed");
            ItemStack returned = f.body.pack().getItem(0);
            helper.assertTrue(returned.is(Items.IRON_HELMET), "the old real helmet occupies the freed cell");
            helper.assertValueEqual(returned.getDamageValue(), 5, "the returned physical helmet retains damage");
            helper.assertTrue(PersonalItems.purpose(f.body, returned).filter("spare"::equals).isPresent(), "retained old equipment becomes protected spare stock");
            helper.assertValueEqual(f.body.pack().count(Items.COBBLESTONE), (f.body.pack().getContainerSize() - 1) * 64, "unrelated full cells remain untouched");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void aFullDestinationRefusesUnequipWithoutChangingEitherInventory(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.player.getInventory().setItem(0, named(Items.SHIELD, "kept shield", 6));
            f.equip(EquipmentSlot.OFFHAND, "player", 0);
            fillPack(f.body);
            ItemStack before = f.body.getOffhandItem().copy();
            f.act("unequip", EquipmentSlot.OFFHAND);
            helper.assertValueEqual(f.result(), "folkways.equipment.result.no_room", "lack of return space is observable");
            helper.assertTrue(ItemStack.matches(before, f.body.getOffhandItem()), "a failed transaction leaves native equipment unchanged");
            helper.assertValueEqual(f.body.pack().count(Items.COBBLESTONE), f.body.pack().getContainerSize() * 64, "a failed transaction never deletes cargo");
            helper.assertValueEqual(f.player.getInventory().countItem(Items.SHIELD), 0, "refusal does not refund an extra shield to the player");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, f.body.getBoundingBox().inflate(3)).isEmpty(), "refusal spills no item into the world");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void remotePersonalEquipmentWorksButPlayerTransfersAndNonMainPlayerSlotsAreRejected(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ItemStack helmet = named(Items.IRON_HELMET, "personal remote helmet", 2);
            PersonalItems.mark(f.body, helmet, "spare"); f.body.pack().setItem(0, helmet);
            f.player.moveTo(f.body.getX() + 12, f.body.getY(), f.body.getZ());
            helper.assertFalse(f.read().getBoolean("near"), "a remote reader cannot claim proximity");
            f.equip(EquipmentSlot.HEAD, "resident", 0);
            helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "protected personal equipment can be managed remotely");
            f.player.getInventory().setItem(0, new ItemStack(Items.SHIELD));
            f.equip(EquipmentSlot.OFFHAND, "player", 0);
            helper.assertValueEqual(f.result(), "folkways.equipment.result.too_far", "remote source-player transfers are rejected");
            helper.assertTrue(f.body.getOffhandItem().isEmpty() && f.player.getInventory().getItem(0).is(Items.SHIELD), "distance refusal moves nothing");
            f.body.pack().setItem(0, new ItemStack(Items.IRON_CHESTPLATE));
            f.equip(EquipmentSlot.CHEST, "resident", 0);
            helper.assertValueEqual(f.result(), "folkways.equipment.result.too_far", "first claiming unmarked carried goods also requires proximity");
            helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                && !f.read().getList("pack", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).getBoolean("usable"),
                "the remote read exposes the same refusal as the server action");
            f.near();
            f.player.getInventory().setItem(36, new ItemStack(Items.DIAMOND_BOOTS));
            f.equip(EquipmentSlot.FEET, "player", 36);
            helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.FEET).isEmpty() && f.player.getInventory().getItem(36).is(Items.DIAMOND_BOOTS), "the 36 main inventory slots exclude worn player armor");
            f.body.pack().setItem(0, new ItemStack(Items.IRON_CHESTPLATE));
            f.body.setDoing(Doing.open(ResourceLocation.fromNamespaceAndPath("folkways", "equipment_cargo_test")));
            f.equip(EquipmentSlot.CHEST, "resident", 0);
            helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "an active task's unmarked cargo is not personal equipment");
            f.body.clearDoing();
            UUID loan = UUID.randomUUID();
            TaskTools.keep(loan, f.body.id(), stack -> stack.is(Items.IRON_CHESTPLATE));
            try {
                f.equip(EquipmentSlot.CHEST, "resident", 0);
                helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "a borrowed task item stays unavailable even between named actions");
            } finally { TaskTools.release(loan); }
            f.equip(EquipmentSlot.CHEST, "resident", 0);
            helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "idle unmarked belongings become equipment only after the loan has ended");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void currentColonyAttachmentCannotForgeAuthorityAndTicketsCannotChangeResident(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.player.getInventory().setItem(0, new ItemStack(Items.SHIELD));
            CompoundTag action = f.action("equip", EquipmentSlot.OFFHAND);
            action.putString("source", "player"); action.putInt("index", 0);
            ServerPlayer intruder = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "equipment_intruder"));
            intruder.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(f.colony.id()));
            intruder.moveTo(f.player.getX(), f.player.getY(), f.player.getZ());
            intruder.getInventory().setItem(0, new ItemStack(Items.SHIELD));
            ResidentEquipment.act(intruder, f.body, action);
            helper.assertTrue(f.body.getOffhandItem().isEmpty() && intruder.getInventory().getItem(0).is(Items.SHIELD), "a copied keeper ticket cannot confer keeper authority");
            action.putUUID("resident", UUID.randomUUID());
            ResidentEquipment.act(f.player, f.body, action);
            helper.assertTrue(f.body.getOffhandItem().isEmpty() && f.player.getInventory().getItem(0).is(Items.SHIELD), "an old widget cannot direct its ticket at another resident");
            action = f.action("equip", EquipmentSlot.OFFHAND);
            action.putString("source", "player"); action.putInt("index", 0);
            Charters.keep(f.colony, Charter.founded("revoked equipment", UUID.randomUUID()));
            ResidentEquipment.act(f.player, f.body, action);
            helper.assertTrue(f.body.getOffhandItem().isEmpty(), "authority is checked again after a previously valid keeper loses the charter");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void busyMainHandRefusesReplacementWithoutCancellingTheWeaponSessionOrTask(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.player.getInventory().setItem(0, named(Items.IRON_SWORD, "active weapon", 4));
            f.equip(EquipmentSlot.MAINHAND, "player", 0);
            WeaponSession session = Weapons.open(worker(f.body)).orElseThrow();
            try {
                Doing doing = Doing.open(ResourceLocation.fromNamespaceAndPath("folkways", "equipment_test_work"));
                f.body.setDoing(doing);
                f.player.getInventory().setItem(0, named(Items.DIAMOND_SWORD, "refused replacement", 1));
                ItemStack active = f.body.getMainHandItem();
                f.equip(EquipmentSlot.MAINHAND, "player", 0);
                helper.assertValueEqual(f.result(), "folkways.equipment.result.busy", "the current weapon loan is an observable busy refusal");
                helper.assertTrue(f.body.hasWeaponSession() && f.body.getMainHandItem() == active, "refusal does not stop or substitute the running weapon session");
                helper.assertValueEqual(session.state(), WeaponSession.State.READY, "the session remains ready");
                helper.assertValueEqual(f.body.doing().orElseThrow(), doing, "an equipment click does not cancel the current task");
                helper.assertTrue(f.player.getInventory().getItem(0).is(Items.DIAMOND_SWORD), "the rejected source item stays with its owner");
                ItemStack helmet = named(Items.IRON_HELMET, "safe while fighting", 2);
                PersonalItems.mark(f.body, helmet, "spare"); f.body.pack().setItem(2, helmet);
                f.act("fill", EquipmentSlot.HEAD);
                helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "fill may equip free armor without borrowing the busy main hand");
                f.player.getInventory().setItem(1, new ItemStack(Items.ARROW, 2));
                f.act("ammo_fill", EquipmentSlot.MAINHAND);
                helper.assertValueEqual(f.body.pack().count(Items.ARROW), 2, "a nearby keeper may replenish real ammunition during a weapon session");
                helper.assertTrue(f.body.hasWeaponSession() && f.body.getMainHandItem() == active, "armor fill and ammunition preserve the original hand owner");
            } finally { f.body.clearDoing(); session.close(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void aSuccessfulTicketCannotBeReplayed(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.player.getInventory().setItem(0, named(Items.SHIELD, "one shield", 3));
            CompoundTag action = f.action("equip", EquipmentSlot.OFFHAND);
            action.putString("source", "player"); action.putInt("index", 0);
            ResidentEquipment.act(f.player, f.body, action);
            ItemStack actual = f.body.getOffhandItem().copy();
            ResidentEquipment.act(f.player, f.body, action);
            helper.assertValueEqual(f.result(), "folkways.equipment.result.stale", "a second use of a consumed ticket is rejected");
            helper.assertTrue(ItemStack.matches(actual, f.body.getOffhandItem()), "replay does not equip or unequip the physical item twice");
            helper.assertValueEqual(f.body.pack().count(Items.SHIELD) + f.player.getInventory().countItem(Items.SHIELD), 0, "the equipped shield has no inventory duplicate");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void changingThePackAfterReadingInvalidatesTheWholeTransaction(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            CompoundTag action = f.action("equip", EquipmentSlot.MAINHAND);
            action.putString("source", "player"); action.putInt("index", 0);
            f.body.pack().setItem(0, new ItemStack(Items.COBBLESTONE));
            ResidentEquipment.act(f.player, f.body, action);
            helper.assertValueEqual(f.result(), "folkways.equipment.result.stale", "a changed source/destination snapshot requires another explicit click");
            helper.assertTrue(f.player.getInventory().getItem(0).is(Items.IRON_SWORD), "a stale request cannot remove its formerly visible source");
            helper.assertValueEqual(f.body.pack().count(Items.COBBLESTONE), 1, "new cargo remains intact");
            helper.assertTrue(assigned(f.body, EquipmentSlot.MAINHAND).isEmpty(), "stale data does not create a designation");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void fillingEmptySlotsDoesNotGuessBetweenMultiplePersonalCandidates(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            for (int index = 0; index < 2; index++) {
                ItemStack helmet = named(Items.IRON_HELMET, "choice " + index, index + 1);
                PersonalItems.mark(f.body, helmet, "spare"); f.body.pack().setItem(index, helmet);
            }
            f.act("fill", EquipmentSlot.HEAD);
            helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "automatic fill cannot silently choose between named items");
            helper.assertValueEqual(f.body.pack().count(Items.IRON_HELMET), 2, "both real candidates stay available for an explicit choice");
            helper.assertValueEqual(f.result(), "folkways.equipment.result.ambiguous", "ambiguity is explained to the keeper");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void changingArrowTargetCreatesNothingAndFaceToFaceTransferUsesOnlyFiniteRealStock(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            CompoundTag target = f.action("ammo_target", EquipmentSlot.MAINHAND);
            target.putInt("value", 16); ResidentEquipment.act(f.player, f.body, target);
            helper.assertValueEqual(f.read().getInt("ammoTarget"), 16, "the configured target is observable");
            helper.assertValueEqual(f.body.pack().count(Items.ARROW), 0, "a target change does not create ammunition");
            ItemStack arrows = new ItemStack(Items.ARROW, 3);
            arrows.set(DataComponents.CUSTOM_NAME, Component.literal("finite donor arrows"));
            f.player.getInventory().setItem(0, arrows);
            f.act("ammo_fill", EquipmentSlot.MAINHAND);
            helper.assertValueEqual(f.body.pack().count(Items.ARROW), 3, "the available partial batch moves exactly once");
            helper.assertValueEqual(f.player.getInventory().countItem(Items.ARROW), 0, "all donated arrows leave the source inventory");
            helper.assertValueEqual(f.result(), "folkways.equipment.result.partial", "a real partial transfer reports the remaining shortage");
            ItemStack received = f.body.pack().contents().stream().filter(stack -> stack.is(Items.ARROW)).findFirst().orElseThrow();
            helper.assertValueEqual(received.get(DataComponents.CUSTOM_NAME), arrows.get(DataComponents.CUSTOM_NAME), "finite transferred arrows retain item components");
            f.act("ammo_fill", EquipmentSlot.MAINHAND);
            helper.assertValueEqual(f.body.pack().count(Items.ARROW), 3, "retrying an empty donor cannot refill by spawning items");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void fillingReportsCompletedSlotsAndAmbiguitiesTogether(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            for (int cell = 0; cell < 2; cell++) {
                ItemStack helmet = named(Items.IRON_HELMET, "different candidate " + cell, cell + 1);
                PersonalItems.mark(f.body, helmet, "spare"); f.body.pack().setItem(cell, helmet);
            }
            ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
            PersonalItems.mark(f.body, chest, "spare"); f.body.pack().setItem(2, chest);
            f.act("fill", EquipmentSlot.HEAD);
            CompoundTag result = f.read();
            helper.assertValueEqual(result.getString("result"), "folkways.equipment.result.fill_summary", "mixed completion has an explicit summary");
            helper.assertTrue(java.util.Arrays.equals(result.getIntArray("resultArgs"), new int[]{1, 1, 0}),
                "feedback counts one successful slot, one ambiguous slot and no capacity failure");
            helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)
                && f.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && f.body.pack().count(Items.IRON_HELMET) == 2,
                "the feedback reflects real partial completion without picking either helmet");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void lockingAnEmptyMainHandPreventsFallbackToOtherCarriedWeapons(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.body.pack().setItem(0, new ItemStack(Items.IRON_SWORD));
            CompoundTag lock = f.action("lock", EquipmentSlot.MAINHAND);
            lock.putBoolean("value", true); ResidentEquipment.act(f.player, f.body, lock);
            helper.assertTrue(Equipment.locked(f.body, EquipmentSlot.MAINHAND), "an intentionally empty main slot can be locked");
            Optional<WeaponSession> session = Weapons.open(worker(f.body));
            try {
                helper.assertFalse(f.body.getMainHandItem().is(Items.IRON_SWORD), "automatic fallback does not override an empty locked designation");
                helper.assertValueEqual(f.body.pack().count(Items.IRON_SWORD), 1, "the unrelated carried sword stays in the pack");
            } finally { session.ifPresent(WeaponSession::close); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void saveLoadKeepsAllSixRealItemsAndNativeArmorAndOffhandDropExactlyOnce(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            for (int index = 0; index < SLOTS.size(); index++) {
                f.player.getInventory().setItem(0, named(ITEMS.get(index), "persistent " + index, index + 2));
                f.equip(SLOTS.get(index), "player", 0);
            }
            CompoundTag saved = f.body.saveWithoutId(new CompoundTag());
            f.body.load(saved);
            for (int index = 0; index < SLOTS.size(); index++) {
                EquipmentSlot slot = SLOTS.get(index);
                ItemStack actual = assigned(f.body, slot);
                helper.assertTrue(actual.is(ITEMS.get(index)), "save/load retains the real item in " + slot);
                helper.assertValueEqual(actual.getDamageValue(), index + 2, "save/load retains durability");
                helper.assertTrue(Equipment.locked(f.body, slot), "the manual slot lock persists");
                helper.assertTrue(PersonalItems.purpose(f.body, actual).filter(slot.getName()::equals).isPresent(), "ownership and designation persist on the real stack");
            }
            f.body.hurt(helper.getLevel().damageSources().genericKill(), 1000);
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, f.body.getBoundingBox().inflate(3));
            for (int index = 0; index < ITEMS.size(); index++) {
                Item item = ITEMS.get(index);
                List<ItemStack> actual = drops.stream().map(ItemEntity::getItem).filter(stack -> stack.is(item)).toList();
                helper.assertValueEqual(actual.stream().mapToInt(ItemStack::getCount).sum(), 1, "native death and pack death each drop their own item exactly once: " + item);
                helper.assertValueEqual(actual.getFirst().get(DataComponents.CUSTOM_NAME), Component.literal("persistent " + index), "death retains the item's identity components");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void borrowedNativeArmorRejectsEquipUnequipAndLockWithoutTouchingTheLoan(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ItemStack borrowed = named(Items.IRON_HELMET, "task-owned real helmet", 17);
            f.body.setItemSlot(EquipmentSlot.HEAD, borrowed);
            UUID task = UUID.randomUUID();
            TaskTools.keep(task, f.body.id(), stack -> stack == borrowed);
            try {
                f.player.getInventory().setItem(0, named(Items.DIAMOND_HELMET, "untouched player helmet", 3));
                var slots = f.read().getList("slots", Tag.TAG_COMPOUND);
                helper.assertTrue(java.util.stream.IntStream.range(0, slots.size()).mapToObj(slots::getCompound)
                    .anyMatch(row -> row.getString("slot").equals("head") && row.getBoolean("busy")),
                    "the snapshot exposes the borrowed native head slot as busy");
                f.equip(EquipmentSlot.HEAD, "player", 0);
                helper.assertValueEqual(f.result(), "folkways.equipment.result.busy", "equip cannot confiscate a current clothing loan");
                f.act("unequip", EquipmentSlot.HEAD);
                helper.assertValueEqual(f.result(), "folkways.equipment.result.busy", "unequip cannot confiscate a current clothing loan");
                CompoundTag lock = f.action("lock", EquipmentSlot.HEAD);
                lock.putBoolean("value", true); ResidentEquipment.act(f.player, f.body, lock);
                helper.assertValueEqual(f.result(), "folkways.equipment.result.busy", "locking cannot convert a borrowed native item into a personal assignment");
                helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.HEAD) == borrowed, "the task retains the same physical borrowed stack");
                helper.assertValueEqual(borrowed.getDamageValue(), 17, "the loan's components remain unchanged");
                helper.assertFalse(Equipment.locked(f.body, EquipmentSlot.HEAD), "the refused lock does not alter slot policy");
                helper.assertFalse(PersonalItems.marked(borrowed), "the task item does not acquire a private ownership marker");
                helper.assertTrue(f.player.getInventory().getItem(0).is(Items.DIAMOND_HELMET), "none of the rejected transactions debits the player");
            } finally { TaskTools.release(task); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void ammunitionAndUnequipCannotConsumeTheOnlyCellReservedForAnActiveWeaponReturn(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.player.getInventory().setItem(0, named(Items.IRON_SWORD, "must return without dropping", 8));
            f.equip(EquipmentSlot.MAINHAND, "player", 0);
            f.player.getInventory().setItem(0, named(Items.IRON_HELMET, "stays worn while full", 6));
            f.equip(EquipmentSlot.HEAD, "player", 0);
            for (int cell = 1; cell < f.body.pack().getContainerSize(); cell++) {
                f.body.pack().setItem(cell, new ItemStack(Items.COBBLESTONE, 64));
            }
            WeaponSession session = Weapons.open(worker(f.body)).orElseThrow();
            ItemStack weapon = f.body.getMainHandItem();
            ItemStack helmet = f.body.getItemBySlot(EquipmentSlot.HEAD);
            try {
                helper.assertTrue(f.body.pack().getItem(0).isEmpty(), "the session's real weapon loan created the only empty cell");
                f.player.getInventory().setItem(0, new ItemStack(Items.ARROW, 3));
                f.act("ammo_fill", EquipmentSlot.MAINHAND);
                helper.assertValueEqual(f.result(), "folkways.equipment.result.no_room", "ammunition must leave space for the outstanding real hand item");
                helper.assertValueEqual(f.player.getInventory().countItem(Items.ARROW), 3, "rejected replenishment leaves every donor arrow intact");
                helper.assertValueEqual(f.body.pack().count(Items.ARROW), 0, "no partial ammunition batch occupies the return cell");
                f.act("unequip", EquipmentSlot.HEAD);
                helper.assertValueEqual(f.result(), "folkways.equipment.result.no_room", "armor cannot occupy the same reserved return cell");
                helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.HEAD) == helmet, "the real armor remains worn after capacity refusal");
                helper.assertTrue(f.body.getMainHandItem() == weapon && f.body.pack().getItem(0).isEmpty(), "both refusals preserve the original hand loan and its return cell");
            } finally { session.close(); }
            helper.assertTrue(ItemStack.matches(f.body.pack().getItem(0), weapon), "normal session closure returns the weapon with all components to the reserved cell");
            helper.assertValueEqual(f.body.pack().count(Items.IRON_SWORD), 1, "normal return leaves exactly one actual weapon in the pack");
            helper.assertFalse(f.body.hasWeaponSession(), "the session closes normally");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, f.body.getBoundingBox().inflate(3)).isEmpty(), "no weapon or armor is dropped to make room");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void aChargedCrossbowCountsOnePaidArrowAndDoesNotOverfillItsReserveWithMultishotCopies(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ItemStack crossbow = named(Items.CROSSBOW, "loaded crossbow reserve", 4);
            crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(List.of(new ItemStack(Items.ARROW))));
            f.player.getInventory().setItem(0, crossbow); f.equip(EquipmentSlot.MAINHAND, "player", 0);
            ItemStack reserve = new ItemStack(Items.ARROW, 7); PersonalItems.mark(f.body, reserve, "ammo");
            f.body.pack().setItem(1, reserve);
            f.player.getInventory().setItem(0, new ItemStack(Items.ARROW, 12));
            CompoundTag snapshot = f.read();
            helper.assertValueEqual(snapshot.getInt("ammoTarget"), 8, "the default reserve target is eight physical arrows");
            helper.assertValueEqual(snapshot.getInt("ammoCount"), 7, "the inventory count excludes the loaded projectile");
            helper.assertValueEqual(snapshot.getInt("loadedAmmo"), 1, "one paid arrow is displayed separately inside the crossbow");
            f.act("ammo_fill", EquipmentSlot.MAINHAND);
            helper.assertValueEqual(f.result(), "folkways.equipment.result.no_change", "one loaded plus seven spare arrows already meet the target");
            helper.assertValueEqual(f.player.getInventory().countItem(Items.ARROW), 12, "no unnecessary eighth spare is taken from the keeper");
            ResidentEquipment.assigned(f.body).set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(List.of(
                new ItemStack(Items.ARROW), new ItemStack(Items.ARROW), new ItemStack(Items.ARROW))));
            helper.assertValueEqual(f.read().getInt("loadedAmmo"), 1, "native Multishot's three projectile copies represent one paid arrow");
            f.act("ammo_fill", EquipmentSlot.MAINHAND);
            helper.assertValueEqual(f.player.getInventory().countItem(Items.ARROW), 12, "Multishot copies do not trigger an extra inventory transfer");
            helper.assertValueEqual(f.body.pack().count(Items.ARROW), 7, "the real reserve stays at seven");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void anotherResidentsPersonalArrowsCannotEnableOrFeedThisResidentsBow(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.player.getInventory().setItem(0, new ItemStack(Items.BOW)); f.equip(EquipmentSlot.MAINHAND, "player", 0);
            ResidentEntity other = PersonBody.RESIDENT.get().create(helper.getLevel());
            ItemStack foreign = new ItemStack(Items.ARROW, 3); PersonalItems.mark(other, foreign, "ammo");
            f.body.pack().setItem(1, foreign);
            helper.assertFalse(Weapons.BOW.usable(worker(f.body), ResidentEquipment.assigned(f.body)), "another resident's protected arrows do not make a bow usable");
            WeaponSession session = Weapons.BOW.open(worker(f.body)).orElseThrow();
            try {
                helper.assertTrue(f.body.getProjectile(f.body.getMainHandItem()).isEmpty(), "native projectile lookup also rejects the foreign physical arrows");
                ItemStack own = new ItemStack(Items.ARROW, 2); PersonalItems.mark(f.body, own, "ammo");
                f.body.pack().setItem(2, own);
                helper.assertTrue(Weapons.BOW.usable(worker(f.body), f.body.getMainHandItem()), "the resident's own real arrows enable its bow");
                helper.assertTrue(f.body.getProjectile(f.body.getMainHandItem()) == own, "native projectile lookup returns the actual eligible own stack");
                helper.assertTrue(f.body.pack().getItem(1) == foreign && foreign.getCount() == 3, "the protected foreign stack is never consumed or rewritten");
            } finally { session.close(); other.discard(); }
        }
        helper.succeed();
    }

    private static ItemStack named(Item item, String name, int damage) {
        ItemStack stack = new ItemStack(item); stack.setDamageValue(damage);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name)); return stack;
    }

    private static ItemStack assigned(ResidentEntity body, EquipmentSlot slot) {
        if (slot != EquipmentSlot.MAINHAND) { return body.getItemBySlot(slot); }
        if (body.hasWeaponSession()) { return body.getMainHandItem(); }
        return body.pack().contents().stream().filter(stack -> PersonalItems.purpose(body, stack)
            .filter(EquipmentSlot.MAINHAND.getName()::equals).isPresent()).findFirst().orElse(ItemStack.EMPTY);
    }

    private static void fillPack(ResidentEntity body) {
        for (int slot = 0; slot < body.pack().getContainerSize(); slot++) { body.pack().setItem(slot, new ItemStack(Items.COBBLESTONE, 64)); }
    }

    private static Worker worker(ResidentEntity body) {
        return new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body; }
            public Container pack() { return body.pack(); }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return body.pack().contents().stream().filter(stack -> Tools.answers(stack, need)).findFirst().orElse(ItemStack.EMPTY); }
            public void spill(ItemStack stack) { body.spawnAtLocation(stack); }
            public Effects effects() { return body.effects(); }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }

    private static final class Fixture implements AutoCloseable {
        final Colony colony;
        final ServerPlayer player;
        final ResidentEntity body;
        Fixture(GameTestHelper helper) {
            var level = helper.getLevel();
            for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
            player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "equipment_keeper"));
            player.getInventory().clearContent();
            colony = Colonies.mint(level.getServer()); Charters.keep(colony, Charter.founded("equipment test", player.getUUID()));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            body = PersonBody.RESIDENT.get().create(level); body.setNoAi(true);
            BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2)); body.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            level.addFreshEntity(body);
            colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow(); body.joinColony(colony.id());
            near();
        }
        void near() { player.moveTo(body.getX() + 1, body.getY(), body.getZ()); }
        CompoundTag read() { return ResidentEquipment.read(player, body); }
        String result() { return read().getString("result"); }
        CompoundTag action(String kind, EquipmentSlot slot) {
            CompoundTag snapshot = read(); CompoundTag action = new CompoundTag();
            for (String key : List.of("resident", "ticket")) {
                if (snapshot.contains(key)) { action.put(key, snapshot.get(key).copy()); }
            }
            action.putString("kind", kind); action.putString("slot", slot.getName()); return action;
        }
        void act(String kind, EquipmentSlot slot) { ResidentEquipment.act(player, body, action(kind, slot)); }
        void equip(EquipmentSlot slot, String source, int index) {
            CompoundTag action = action("equip", slot); action.putString("source", source); action.putInt("index", index);
            ResidentEquipment.act(player, body, action);
        }
        @Override public void close() { body.stopWeaponSession(); body.discard(); colony.raze(); player.getInventory().clearContent(); }
    }
}
