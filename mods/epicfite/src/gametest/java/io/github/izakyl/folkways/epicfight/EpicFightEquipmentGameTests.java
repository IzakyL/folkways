package io.github.izakyl.folkways.epicfight;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.registry.entries.EpicFightItems;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.item.CapabilityItem.WeaponCategories;

@EventBusSubscriber(modid = FolkwaysEpicFight.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EpicFightEquipmentGameTests {
    @GameTest(template = "empty_large")
    public static void epicFightBorrowsTheAutomaticallyChosenActualAxeButStillHonorsAManualSwordLock(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            ItemStack sword = new ItemStack(Items.WOODEN_SWORD);
            sword.set(DataComponents.CUSTOM_NAME, Component.literal("unlocked default sword"));
            PersonalItems.mark(s.resident, sword, "mainhand"); s.resident.pack().setItem(0, sword);
            ItemStack axe = new ItemStack(Items.DIAMOND_AXE); axe.setDamageValue(17);
            axe.set(DataComponents.CUSTOM_NAME, Component.literal("automatic exact EF axe"));
            PersonalItems.mark(s.resident, axe, "spare"); s.resident.pack().setItem(1, axe);
            Equipment.lock(s.resident, EquipmentSlot.MAINHAND, false);
            var selected = Weapons.open(s.worker);
            helper.assertTrue(selected.isPresent(), "EF accepts a safe automatically chosen physical spare");
            try (WeaponSession session = selected.get()) {
                helper.assertTrue(session instanceof EpicFightWeaponSession && s.resident.getMainHandItem() == axe,
                    "the EF adapter borrows the precise higher-DPS actual axe instead of rescanning the first sword");
                helper.assertTrue(ResidentEquipment.assigned(s.resident) == sword, "automatic use does not rewrite the original assignment");
            }
            Equipment.lock(s.resident, EquipmentSlot.MAINHAND, true);
            var locked = Weapons.open(s.worker);
            helper.assertTrue(locked.isPresent(), "the original manual designation is still available");
            try (WeaponSession session = locked.get()) {
                helper.assertTrue(s.resident.getMainHandItem() == sword, "the manual lock overrides EF's automatic damage ranking");
            }
            helper.assertValueEqual(s.resident.pack().count(Items.DIAMOND_AXE), 1, "both EF acquisitions return only one actual axe");
            helper.assertValueEqual(axe.getDamageValue(), 17, "selection and cancellation spend no fake attack durability");
        }
        helper.succeed();
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(EpicFightEquipmentGameTests.class); }

    @GameTest(template = "empty_large")
    public static void assigningTheSecondIdenticalWeaponLoansOnlyThatActualStack(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            s.resident.pack().setItem(0, sword("first carried sword", 3));
            s.resident.pack().setItem(1, sword("second assigned sword", 11)); s.assign(1);
            ItemStack selected = ResidentEquipment.assigned(s.resident);
            WeaponSession session = Weapons.open(s.worker).orElseThrow();
            helper.assertTrue(session instanceof EpicFightWeaponSession, "exact dispatch uses Epic Fight for the designated supported weapon");
            helper.assertTrue(s.resident.getMainHandItem() == selected, "the actual latter stack, not a copy or earlier sword, is borrowed");
            helper.assertValueEqual(s.resident.pack().getItem(0).get(DataComponents.CUSTOM_NAME), Component.literal("first carried sword"), "the earlier sword is untouched");
            helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "only the designated stack leaves the pack");
            session.close(); session.close();
            helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 2, "idempotent close returns one designated weapon");
            helper.assertValueEqual(ResidentEquipment.assigned(s.resident).getDamageValue(), 11, "components survive return");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void exactOpenRejectsCopiesBusyHandsBorrowedItemsAndAReboundWorker(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            s.resident.pack().setItem(0, sword("strict actual sword", 4)); s.assign(0);
            ItemStack selected = ResidentEquipment.assigned(s.resident);
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.openAssigned(s.worker, selected.copy()).isEmpty(), "identical NBT is not an inventory reference");
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.openAssigned(worker(s.resident, new SimpleContainer(18), s.resident.resident()), selected).isEmpty(), "a separate inventory cannot stand in for the pack");
            Resident forged = new Resident(UUID.randomUUID(), s.resident.resident().kind());
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.openAssigned(worker(s.resident, s.resident.pack(), forged), selected).isEmpty(), "a worker with a different resident ID cannot borrow the assignment");
            UUID task = UUID.randomUUID(); TaskTools.keep(task, s.resident.id(), stack -> stack == selected);
            try { helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.openAssigned(s.worker, selected).isEmpty(), "a task tool loan takes precedence"); }
            finally { TaskTools.release(task); }
            WeaponSession active = EpicFightWeaponAdapter.INSTANCE.openAssigned(s.worker, selected).orElseThrow();
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.openAssigned(s.worker, selected).isEmpty(), "a second exact open cannot replace the hand owner");
            helper.assertTrue(s.resident.getMainHandItem() == selected, "busy refusal leaves the actual hand untouched");
            active.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void foreignAssignmentsAndAnEmptyLockedSlotNeverFallBackToAnotherSword(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            ResidentEntity other = PersonBody.RESIDENT.get().create(s.level);
            try {
                ItemStack foreign = sword("another resident's weapon", 6);
                PersonalItems.mark(other, foreign, "mainhand"); other.pack().setItem(0, foreign);
                helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.openAssigned(s.worker, foreign).isEmpty(), "another resident's actual inventory stack is not this assignment");
                other.pack().setItem(0, ItemStack.EMPTY); s.resident.pack().setItem(0, foreign);
                helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.open(s.worker).isEmpty(), "ordinary selection cannot appropriate a foreign personal weapon");
                s.resident.pack().setItem(0, sword("unassigned carried sword", 2)); Equipment.lock(s.resident, EquipmentSlot.MAINHAND, true);
                helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.open(s.worker).isEmpty(), "an empty locked main slot prevents automatic fallback");
                helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "refusing fallback leaves stock intact");
            } finally { other.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void stopAndSaveCloseTheExactAssignmentOnceAndPreserveItsDesignation(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            s.resident.pack().setItem(0, sword("saved exact sword", 12)); s.assign(0);
            ItemStack selected = ResidentEquipment.assigned(s.resident); WeaponSession session = Weapons.open(s.worker).orElseThrow();
            helper.assertTrue(session.fire(s.zombie), "the exact weapon starts its hostile animation"); session.stop();
            helper.assertTrue(s.resident.getMainHandItem() == selected, "stop retains the one loan until closure");
            CompoundTag saved = s.resident.saveWithoutId(new CompoundTag());
            helper.assertValueEqual(session.state(), WeaponSession.State.CLOSED, "saving closes the hand before serializing inventory");
            session.close(); s.resident.load(saved);
            helper.assertTrue(s.resident.getMainHandItem().isEmpty() && !s.resident.hasWeaponSession(), "loading restores no hidden lease");
            helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "stop, save, repeated close and load retain one weapon");
            helper.assertValueEqual(ResidentEquipment.assigned(s.resident).getDamageValue(), 12, "save/load preserves durability");
            helper.assertValueEqual(ResidentEquipment.assigned(s.resident).get(DataComponents.CUSTOM_NAME), Component.literal("saved exact sword"), "designation stays on the named physical item");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 240)
    public static void theExactAssignedSwordHitsAHostileOnlyOnItsRealEpicFightCollisionFrame(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            s.resident.pack().setItem(0, sword("unused earlier sword", 2));
            s.resident.pack().setItem(1, sword("actual hitting sword", 9)); s.assign(1);
            WeaponSession session = Weapons.open(s.worker).orElseThrow();
            ItemStack selected = s.resident.getMainHandItem(); float health = s.zombie.getHealth();
            helper.assertFalse(session.fire(s.cow), "normal hostility cannot attack a neutral animal");
            helper.assertTrue(session.fire(s.zombie), "the assigned actual weapon starts its hostile strike");
            helper.assertValueEqual(s.zombie.getHealth(), health, "starting the attack causes no immediate scripted damage");
            helper.onEachTick(session::tick);
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(s.zombie.getHealth() < health, "EF's collision phase hits the hostile"))
                .thenExecute(() -> {
                    helper.assertTrue(s.zombie.getLastDamageSource().getEntity() == s.resident, "the resident owns the real damage source");
                    helper.assertTrue(selected.getDamageValue() > 9, "the actual selected sword pays native hit durability");
                    helper.assertValueEqual(s.resident.pack().getItem(0).getDamageValue(), 2, "the earlier sword pays no durability");
                    session.close(); s.close();
                }).thenSucceed();
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large", timeoutTicks = 240)
    public static void theExactNativeIronSpearHitsOnCollisionAndReturnsOnceWithoutDelayedDamage(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            helper.assertValueEqual(ModList.get().getModContainerById("epicfight").orElseThrow()
                .getModInfo().getVersion().toString(), "21.17.3.1", "this native contract is verified only on the pinned EF release");
            ItemStack earlier = new ItemStack(EpicFightItems.IRON_SPEAR.get());
            earlier.setDamageValue(3); earlier.set(DataComponents.CUSTOM_NAME, Component.literal("earlier real spear"));
            ItemStack chosen = new ItemStack(EpicFightItems.IRON_SPEAR.get());
            chosen.setDamageValue(9); chosen.set(DataComponents.CUSTOM_NAME, Component.literal("exact collision spear"));
            chosen.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            ItemStack expected = chosen.copy(); PersonalItems.mark(s.resident, expected, "mainhand");
            ItemStack expectedEarlier = earlier.copy();
            s.resident.pack().setItem(0, earlier); s.resident.pack().setItem(1, chosen); s.assign(1);
            // Manual equipment commits a copied transaction. Only post-commit references are actual inventory items.
            ItemStack selected = ResidentEquipment.assigned(s.resident);
            ItemStack actualEarlier = s.resident.pack().getItem(0);
            helper.assertTrue(ItemStack.matches(selected, expected), "the committed selected spear preserves every original component and gains only its personal designation");
            helper.assertTrue(ItemStack.matches(actualEarlier, expectedEarlier), "the other committed spear preserves its original components");
            helper.assertTrue(EpicFightCapabilities.getItemStackCapability(selected).isWeaponCategory(WeaponCategories.SPEAR),
                "the selected actual registered item has its native EF spear capability");
            WeaponSession session = Weapons.open(s.worker).orElseThrow();
            helper.assertTrue(session instanceof EpicFightWeaponSession && s.resident.getMainHandItem() == selected,
                "the EF session borrows the designated spear reference, not the earlier identical item");
            helper.assertValueEqual(s.resident.pack().count(EpicFightItems.IRON_SPEAR.get()), 1, "only one actual spear leaves its pack");
            float health = s.zombie.getHealth();
            helper.assertTrue(session.fire(s.zombie), "the native spear starts an authorized strike inside the existing combat range");
            helper.assertValueEqual(s.zombie.getHealth(), health, "starting the spear animation causes no scripted instant hit");
            float[] stoppedHealth = {health}; int[] stoppedWear = {9};
            ItemStack[] returned = {ItemStack.EMPTY}; ItemStack[] returnedSnapshot = {ItemStack.EMPTY};
            helper.onEachTick(session::tick);
            helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(s.zombie.getHealth() < health, "the native spear collision really damages the hostile"))
                .thenExecute(() -> {
                    helper.assertTrue(s.zombie.getLastDamageSource() != null && s.zombie.getLastDamageSource().getEntity() == s.resident,
                        "the resident owns the native collision damage source");
                    helper.assertTrue(selected.getDamageValue() > 9, "only the actual hitting spear pays native durability");
                    helper.assertTrue(s.resident.pack().getItem(0) == actualEarlier, "combat leaves the other canonical inventory reference in place");
                    helper.assertValueEqual(actualEarlier.getDamageValue(), 3, "the earlier actual spear never pays the selected spear's durability");
                    stoppedHealth[0] = s.zombie.getHealth(); stoppedWear[0] = selected.getDamageValue();
                    returnedSnapshot[0] = selected.copy();
                    session.stop(); session.close(); session.close();
                    helper.assertTrue(!s.resident.hasWeaponSession() && s.resident.getMainHandItem().isEmpty(), "close revokes both the strike and the hand lease");
                    // Pack insertion splits a copy; physical identity across return is the complete unique item state.
                    returned[0] = ResidentEquipment.assigned(s.resident);
                    helper.assertTrue(ItemStack.isSameItemSameComponents(returned[0], returnedSnapshot[0]), "the returned spear preserves every component including its actual hit wear and designation");
                    helper.assertValueEqual(returned[0].getCount(), 1, "exactly one designated spear returns");
                    helper.assertTrue(java.util.stream.IntStream.range(0, s.resident.pack().getContainerSize())
                        .anyMatch(slot -> s.resident.pack().getItem(slot) == returned[0]), "the observed returned spear is an actual pack reference");
                    helper.assertValueEqual(s.resident.pack().countMatching(stack -> ItemStack.isSameItemSameComponents(stack, returnedSnapshot[0])), 1,
                        "the unique selected component state exists exactly once in the real pack");
                    helper.assertTrue(s.resident.pack().getItem(0) == actualEarlier && ItemStack.matches(actualEarlier, expectedEarlier),
                        "returning the selected spear leaves the other canonical spear and all its components untouched");
                    helper.assertValueEqual(s.resident.pack().count(EpicFightItems.IRON_SPEAR.get()), 2, "repeated close retains exactly the original two physical spears");
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertValueEqual(s.zombie.getHealth(), stoppedHealth[0], "a stopped spear has no delayed collision damage");
                    helper.assertTrue(ResidentEquipment.assigned(s.resident) == returned[0], "the returned actual inventory reference remains assigned during animation cleanup");
                    helper.assertValueEqual(returned[0].getDamageValue(), stoppedWear[0], "canceled animation frames spend no further durability on the returned actual item");
                    helper.assertTrue(ItemStack.matches(returned[0], returnedSnapshot[0]), "the returned spear retains all components and its count after canceled frames");
                    helper.assertTrue(s.resident.pack().getItem(0) == actualEarlier && ItemStack.matches(actualEarlier, expectedEarlier),
                        "the other canonical spear remains unchanged after animation cleanup");
                    helper.assertValueEqual(s.resident.pack().countMatching(stack -> ItemStack.isSameItemSameComponents(stack, returnedSnapshot[0])), 1,
                        "animation cleanup cannot create another copy of the selected unique spear state");
                    helper.assertValueEqual(s.resident.pack().count(EpicFightItems.IRON_SPEAR.get()), 2, "the two original physical spears remain unique after animation cleanup");
                    helper.assertTrue(!s.resident.hasWeaponSession() && s.resident.getMainHandItem().isEmpty(), "animation cleanup cannot reacquire the released hand");
                    helper.assertTrue(s.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        s.resident.getBoundingBox().inflate(8)).stream().noneMatch(drop ->
                            ItemStack.isSameItemSameComponents(drop.getItem(), returnedSnapshot[0])
                                || ItemStack.isSameItemSameComponents(drop.getItem(), expectedEarlier)), "neither original spear is duplicated into a dropped item");
                    s.close();
                }).thenSucceed();
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aNewMoveRevokesAnAutonomousGuardStrikeBeforeTheNativeAnimationFrame(GameTestHelper helper) {
        replacedCommandRevokesNativeStrike(helper, true);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aNewRetreatRevokesUncommandedSelfDefenseBeforeTheNativeAnimationFrame(GameTestHelper helper) {
        replacedCommandRevokesNativeStrike(helper, false);
    }

    private static void replacedCommandRevokesNativeStrike(GameTestHelper helper, boolean guarded) {
        Scene s = new Scene(helper);
        try {
            ResidentCommands commands = ResidentCommands.of(s.colony);
            ResidentCommands.Order original = guarded
                ? commands.guard(s.resident, WorldSpaces.at(s.resident), new Vec3(1, 0, 0)) : null;
            helper.assertTrue(commands.current(s.resident.id()).orElse(null) == original,
                "the original command identity, including no command, exists before the combat lease opens");
            s.resident.pack().setItem(0, sword(guarded ? "guard command lease" : "self-defense null lease", 11)); s.assign(0);
            ItemStack selected = ResidentEquipment.assigned(s.resident);
            ItemStack expected = selected.copy(); float health = s.zombie.getHealth();
            WeaponSession session = Weapons.open(s.worker).orElseThrow();
            helper.assertTrue(session instanceof EpicFightWeaponSession && s.resident.getMainHandItem() == selected,
                "the autonomous native session borrows the actual designated sword");
            helper.assertTrue(session.fire(s.zombie), "normal hostility starts a real strike without an explicit-target permission callback");
            ResidentEpicFightPatch patch = ResidentEpicFightPatch.of(s.resident);
            var player = patch.getAnimator().getPlayerFor(null);
            var animation = player.getRealAnimation().get();
            helper.assertTrue(animation instanceof AttackAnimation, "the native EF attack animation really started");
            helper.assertTrue(player.getElapsedTime() < ((AttackAnimation) animation).getPhaseByTime(player.getElapsedTime()).preDelay,
                "the replacement happens in the native windup before its first contact window");
            helper.assertTrue(patch.hasStrike() && ((EpicFightWeaponSession) session).authorized(),
                "the original autonomous strike has a live native collision lease before replacement");
            WorldPos destination = WorldPos.of(s.level, s.resident.blockPosition().offset(5, 0, 0));
            ResidentCommands.Order replacement = guarded ? commands.move(s.resident, destination) : commands.retreat(s.resident, destination);
            helper.assertTrue(session.state() == WeaponSession.State.FIRING && s.resident.getMainHandItem() == selected,
                "issuing the new command has not used BodyRunner or test cleanup to close the old native session");
            // No LaborFixture/session.tick/manual stop runs first: exercise the real EF pre-animation entry directly.
            patch.preTick();
            helper.assertValueEqual(session.state(), WeaponSession.State.CLOSED, "the native frame itself revokes and closes the replaced command's lease");
            helper.assertFalse(patch.hasStrike(), "native animation advancement has no old strike owner");
            helper.assertValueEqual(s.zombie.getHealth(), health, "the native frame cannot deliver the old windup's collision");
            helper.assertTrue(commands.current(s.resident.id()).orElse(null) == replacement
                && replacement.status() == ResidentCommands.Status.MOVING, "old-session cleanup leaves the new MOVE or RETREAT current and usable");
            if (original != null) helper.assertValueEqual(original.status(), ResidentCommands.Status.RELEASED, "the old guard was really replaced");
            ItemStack returned = ResidentEquipment.assigned(s.resident);
            helper.assertTrue(ItemStack.matches(returned, expected), "native revocation returns all actual sword components without spending hit durability");
            helper.assertValueEqual(s.resident.pack().countMatching(stack -> ItemStack.isSameItemSameComponents(stack, expected)), 1,
                "the revoked native loan returns exactly one matching sword to the actual pack");
            session.close(); session.close();
            helper.runAfterDelay(40, () -> {
                helper.assertValueEqual(s.zombie.getHealth(), health, "later native frames never revive the replaced strike");
                helper.assertTrue(!s.resident.hasWeaponSession() && s.resident.getMainHandItem().isEmpty(), "the old native hand stays released");
                helper.assertTrue(ResidentEquipment.assigned(s.resident) == returned && ItemStack.matches(returned, expected),
                    "the actual returned sword and every component remain unchanged");
                helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "native cleanup never duplicates the sword");
                helper.assertValueEqual(s.resident.pack().countMatching(stack -> ItemStack.isSameItemSameComponents(stack, expected)), 1,
                    "the unique returned sword still exists once in the actual pack");
                helper.assertTrue(s.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    s.resident.getBoundingBox().inflate(8)).stream().noneMatch(drop -> ItemStack.isSameItemSameComponents(drop.getItem(), expected)),
                    "the revoked physical sword is not also dropped into the world");
                helper.assertTrue(commands.current(s.resident.id()).orElse(null) == replacement
                    && replacement.status() != ResidentCommands.Status.FAILED && replacement.status() != ResidentCommands.Status.RELEASED,
                    "the old session never releases or fails the replacement command");
                s.close(); helper.succeed();
            });
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large", timeoutTicks = 240)
    public static void aLiveExplicitOrderHitsItsNeutralTargetThroughTheRealCollisionPath(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            s.zombie.moveTo(s.zombie.getX(), s.zombie.getY(), s.zombie.getZ() + 5);
            s.cow.moveTo(s.resident.getX() + 1.2, s.resident.getY(), s.resident.getZ());
            s.resident.pack().setItem(0, sword("explicit real sword", 5)); s.assign(0);
            WeaponSession session = Weapons.open(s.worker).orElseThrow();
            AtomicBoolean authorized = new AtomicBoolean(true); float health = s.cow.getHealth();
            helper.assertTrue(session.explicitTarget(s.cow, authorized::get), "the session accepts the command's live explicit target lease");
            helper.assertTrue(session.fire(s.cow), "a live order permits this neutral target through EF animation");
            helper.assertValueEqual(s.cow.getHealth(), health, "explicit attack has no immediate scripted damage");
            helper.onEachTick(session::tick);
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(s.cow.getHealth() < health, "an actual EF collision hits the explicitly authorized neutral mob"))
                .thenExecute(() -> {
                    helper.assertTrue(s.cow.getLastDamageSource().getEntity() == s.resident, "the command's resident owns the damage");
                    authorized.set(false); session.stop();
                    helper.assertTrue(ResidentEpicFightPatch.of(s.resident).isTargetInvulnerable(s.cow), "revoking the command immediately closes collision authorization");
                    session.close(); s.close();
                }).thenSucceed();
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large")
    public static void explicitPermissionsCannotAttackPlayersOrOtherResidentBodies(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            s.resident.pack().setItem(0, sword("protected target test", 5)); s.assign(0);
            WeaponSession session = Weapons.open(s.worker).orElseThrow();
            helper.assertFalse(session.explicitTarget(s.player, () -> true), "an explicit command does not introduce PvP targeting");
            helper.assertFalse(session.explicitTarget(s.zombie, () -> false), "a revoked order cannot bind an explicit target");
            ResidentEntity other = PersonBody.RESIDENT.get().create(s.level);
            try {
                other.moveTo(s.resident.getX() + 1, s.resident.getY(), s.resident.getZ()); s.level.addFreshEntity(other);
                float health = other.getHealth();
                session.explicitTarget(other, () -> true);
                helper.assertFalse(session.fire(other), "even a true callback cannot override protection for another resident body");
                helper.assertValueEqual(other.getHealth(), health, "the protected resident takes no damage");
            } finally { other.discard(); session.close(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void revokingAnExplicitLeaseBeforeCollisionPreventsEveryDelayedHit(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            s.resident.pack().setItem(0, sword("revoked explicit sword", 7)); s.assign(0);
            WeaponSession session = Weapons.open(s.worker).orElseThrow();
            AtomicBoolean authorized = new AtomicBoolean(true); float health = s.zombie.getHealth();
            helper.assertTrue(session.explicitTarget(s.zombie, authorized::get) && session.fire(s.zombie), "a live explicit strike starts before the first server animation tick");
            authorized.set(false);
            helper.assertTrue(ResidentEpicFightPatch.of(s.resident).isTargetInvulnerable(s.zombie), "permission loss revokes collision admission before damage can occur");
            helper.onEachTick(session::tick);
            helper.runAfterDelay(40, () -> {
                try {
                    helper.assertValueEqual(s.zombie.getHealth(), health, "no later collision hits after revocation");
                    helper.assertValueEqual(s.resident.getMainHandItem().getDamageValue(), 7, "revoked collisions spend no durability");
                    helper.assertValueEqual(session.state(), WeaponSession.State.STOPPING, "the unauthorized attack is terminal until closure");
                    session.close();
                } finally { s.close(); }
                helper.succeed();
            });
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large", timeoutTicks = 180)
    public static void shieldDefenseQueuesAStrikeWithoutAuthorizingCollisionsDuringDefense(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            s.resident.pack().setItem(0, sword("shielded exact sword", 6)); s.assign(0);
            s.resident.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            WeaponSession session = Weapons.open(s.worker).orElseThrow(); float health = s.zombie.getHealth();
            helper.assertTrue(session.fire(s.zombie), "defense queues an intent instead of rejecting and ending the work node");
            helper.assertValueEqual(session.state(), WeaponSession.State.FIRING, "the queued intent keeps work active");
            helper.assertTrue(s.resident.isUsingItem(), "the actual offhand shield starts native use");
            helper.assertTrue(ResidentEpicFightPatch.of(s.resident).isTargetInvulnerable(s.zombie), "the defense window grants no collision permission");
            helper.assertValueEqual(s.zombie.getHealth(), health, "queuing a shielded strike causes no damage");
            helper.onEachTick(session::tick);
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(s.zombie.getHealth() < health, "after lowering the shield a normal EF collision hits"))
                .thenExecute(() -> {
                    helper.assertFalse(s.resident.isUsingItem(), "the actual attack does not block itself with its shield");
                    helper.assertTrue(s.resident.getMainHandItem().getDamageValue() > 6, "the real shielded strike wears its designated weapon");
                    session.close();
                    helper.assertFalse(s.resident.isUsingItem(), "close lowers native shield use");
                    s.close();
                }).thenSucceed();
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void cancellingAShieldDefenseWindowNeverStartsItsDeferredAttack(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            s.resident.pack().setItem(0, sword("cancelled shield intent", 8)); s.assign(0);
            s.resident.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            WeaponSession session = Weapons.open(s.worker).orElseThrow(); float health = s.zombie.getHealth();
            helper.assertTrue(session.fire(s.zombie), "the shielded melee intent is accepted"); session.stop();
            helper.assertFalse(s.resident.isUsingItem(), "stop immediately lowers defense");
            helper.onEachTick(session::tick);
            helper.runAfterDelay(40, () -> {
                try {
                    helper.assertValueEqual(s.zombie.getHealth(), health, "a cancelled queue never starts a delayed EF strike");
                    helper.assertValueEqual(s.resident.getMainHandItem().getDamageValue(), 8, "a cancelled intent spends no durability");
                    session.close();
                } finally { s.close(); }
                helper.succeed();
            });
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void losingExplicitPermissionDuringShieldPreparationCannotStartALaterStrike(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            s.resident.pack().setItem(0, sword("revoked shield preparation", 9)); s.assign(0);
            s.resident.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            WeaponSession session = Weapons.open(s.worker).orElseThrow();
            AtomicBoolean authorized = new AtomicBoolean(true);
            float health = s.zombie.getHealth();
            helper.assertTrue(session.explicitTarget(s.zombie, authorized::get) && session.fire(s.zombie),
                "the real shield prepares an accepted explicit attack");
            helper.assertTrue(s.resident.isUsingItem(), "the native offhand is in use before permission is lost");
            authorized.set(false);
            helper.onEachTick(session::tick);
            helper.runAfterDelay(40, () -> {
                try {
                    helper.assertValueEqual(s.zombie.getHealth(), health, "revoked preparation never starts a damaging collision");
                    helper.assertFalse(s.resident.isUsingItem(), "revocation lowers the real shield");
                    helper.assertValueEqual(session.state(), WeaponSession.State.STOPPING, "the revoked queue is terminal");
                    session.close(); session.close();
                    helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "the exact weapon returns only once");
                    helper.assertValueEqual(s.resident.pack().getItem(0).getDamageValue(), 9, "no cancelled collision spends durability");
                } finally { s.close(); }
                helper.succeed();
            });
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    private static ItemStack sword(String name, int damage) {
        ItemStack stack = new ItemStack(Items.IRON_SWORD); stack.setDamageValue(damage);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name)); return stack;
    }

    private static Worker worker(ResidentEntity body, Container pack, Resident resident) {
        return new Worker() {
            public Resident resident() { return resident; }
            public Mob body() { return body; }
            public Container pack() { return pack; }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { body.spawnAtLocation(stack); }
            public Effects effects() { return body.effects(); }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }

    private static final class Scene implements AutoCloseable, GameTestListener {
        final ServerLevel level;
        final Colony colony;
        final ServerPlayer player;
        final ResidentEntity resident;
        final Zombie zombie;
        final Cow cow;
        final Worker worker;
        private boolean closed;
        Scene(GameTestHelper helper) {
            level = helper.getLevel();
            for (int x = 0; x < 8; x++) for (int z = 0; z < 6; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
            resident = PersonBody.RESIDENT.get().create(level); resident.setNoAi(true);
            BlockPos origin = helper.absolutePos(new BlockPos(2, 1, 2)); resident.moveTo(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
            helper.assertTrue(level.addFreshEntity(resident), "the resident is actually loaded before colony admission");
            colony = Colonies.mint(level.getServer()); colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow(); resident.joinColony(colony.id());
            player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "epf_equipment_keeper"));
            player.moveTo(resident.getX(), resident.getY(), resident.getZ() - 1);
            Charters.keep(colony, Charter.founded("Epic Fight exact equipment", player.getUUID()));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            zombie = EntityType.ZOMBIE.create(level); zombie.setNoAi(true); zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 12000));
            zombie.moveTo(resident.getX() + 1.2, resident.getY(), resident.getZ());
            cow = EntityType.COW.create(level); cow.setNoAi(true); cow.moveTo(resident.getX(), resident.getY(), resident.getZ() + 2);
            level.addFreshEntity(zombie); level.addFreshEntity(cow);
            worker = worker(resident, resident.pack(), resident.resident());
            helper.testInfo.addListener(this);
        }
        void assign(int index) {
            CompoundTag snapshot = ResidentEquipment.read(player, resident);
            CompoundTag action = new CompoundTag(); action.put("resident", snapshot.get("resident").copy()); action.put("ticket", snapshot.get("ticket").copy());
            action.putString("kind", "equip"); action.putString("slot", "mainhand"); action.putString("source", "resident"); action.putInt("index", index);
            ResidentEquipment.act(player, resident, action);
            if (ResidentEquipment.assigned(resident).isEmpty()) { throw new AssertionError("fixture assignment failed: " + ResidentEquipment.read(player, resident).getString("result")); }
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
        @Override public void close() {
            if (closed) { return; } closed = true;
            resident.stopWeaponSession(); resident.discard(); zombie.discard(); cow.discard(); colony.raze();
        }
    }
}
