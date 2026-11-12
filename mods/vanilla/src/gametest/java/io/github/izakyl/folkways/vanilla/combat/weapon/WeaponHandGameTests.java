package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.support.Tools;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.vanilla.patrol.PatrolContent;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class WeaponHandGameTests {
    private static boolean previousNoSave;
    private static final TicketType<UUID> TICKING =
        TicketType.create("folkways_weapon_test", Comparator.<UUID>naturalOrder());

    @BeforeBatch(batch = "vanilla_weapons")
    public static void beforeWeapons(ServerLevel level) {
        previousNoSave = level.noSave;
        level.noSave = true;
    }

    @AfterBatch(batch = "vanilla_weapons")
    public static void afterWeapons(ServerLevel level) {
        level.noSave = previousNoSave;
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WeaponHandGameTests.class);
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core")
    public static void temporaryKitchenHandPreservesPresentationWithoutDuplicatingItems(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 7);
        ResidentEntity body = fixture.body();
        ItemStack shown = find(body, Items.IRON_SWORD).copy();
        body.presentHeldItem(shown);
        ItemStack tool = new ItemStack(Items.STICK);
        body.pack().insert(tool);
        ItemStack previous = body.getMainHandItem();
        body.setItemInHand(InteractionHand.MAIN_HAND, tool);
        try {
            helper.assertTrue(body.getMainHandItem() == tool, "the kitchen action receives its temporary tool");
        } finally {
            body.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
        helper.runAfterDelay(1, () -> {
            try {
                helper.assertTrue(body.getMainHandItem() == shown, "the presentation survives the idle tick");
                helper.assertFalse(body.weaponDrawn(), "the restored copy keeps its presentation flag");
                helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), 1, "no presentation copy enters the pack");
                helper.assertValueEqual(body.pack().count(Items.STICK), 1, "the temporary tool is not duplicated");
                body.clearHandPresentation();
                helper.assertTrue(body.getMainHandItem().isEmpty(), "presentation cleanup still clears the copy");
            } finally {
                fixture.close();
            }
            helper.succeed();
        });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core")
    public static void temporaryKitchenHandRestoresEmptyAndUnownedDrawnHands(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 7);
        ResidentEntity body = fixture.body();
        try {
            for (boolean drawn : new boolean[] {false, true}) {
                if (drawn) {
                    helper.assertTrue(body.drawWeapon(0), "the real weapon is drawn without starting an attack");
                }
                ItemStack previous = body.getMainHandItem();
                ItemStack tool = new ItemStack(Items.STICK);
                body.setItemInHand(InteractionHand.MAIN_HAND, tool);
                try {
                    helper.assertTrue(body.getMainHandItem() == tool, "the temporary tool is installed");
                } finally {
                    body.setItemInHand(InteractionHand.MAIN_HAND, previous);
                }
                helper.assertTrue(body.getMainHandItem() == previous, "the previous native slot is restored");
                helper.assertValueEqual(body.pack().count(Items.STICK), 0, "no temporary tool is holstered");
                helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), drawn ? 0 : 1,
                    "the temporary swap never duplicates a real weapon");
            }
            body.tick();
            helper.assertTrue(body.getMainHandItem().isEmpty(), "an unowned restored weapon is holstered next tick");
            helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), 1, "exactly one real weapon returns");
        } finally {
            fixture.close();
        }
        helper.succeed();
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core")
    public static void idleRealHandReturnsToThePackWithinOneTick(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 7);
        ResidentEntity body = fixture.body();
        ItemStack external = new ItemStack(Items.APPLE, 3);
        body.setItemSlot(EquipmentSlot.MAINHAND, external);
        helper.assertTrue(body.weaponDrawn() && !body.hasActiveAttack(), "the external write has no active attack");
        helper.runAfterDelay(1, () -> {
            try {
                helper.assertTrue(body.getMainHandItem().isEmpty(), "one server tick holsters the idle real hand");
                helper.assertValueEqual(body.pack().count(Items.APPLE), 3, "all external items return exactly once");
                body.presentHeldItem(new ItemStack(Items.STICK));
                helper.assertFalse(body.weaponDrawn(), "the idle hand can present work tools again");
            } finally {
                fixture.close();
            }
            helper.succeed();
        });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core")
    public static void idleRealHandOverflowDropsExactlyOnce(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 7);
        ResidentEntity body = fixture.body();
        for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
            body.pack().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        body.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE, 3));
        helper.runAfterDelay(1, () -> {
            try {
                helper.assertTrue(body.getMainHandItem().isEmpty(), "a full pack still clears the idle real hand");
                List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    body.getBoundingBox().inflate(2), item -> item.getItem().is(Items.APPLE));
                helper.assertValueEqual(drops.stream().mapToInt(item -> item.getItem().getCount()).sum(), 3,
                    "only the actual overflow is dropped");
                drops.forEach(Entity::discard);
            } finally {
                fixture.close();
            }
            helper.succeed();
        });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core")
    public static void continuousEngagementKeepsWeaponDrawnBetweenAttacks(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 7);
        ResidentEntity body = fixture.body();
        Worker worker = worker(body);
        fixture.target().moveTo(body.getX() + 2, body.getY(), body.getZ());
        WeaponAttack attack = Weapons.open(worker).orElseThrow();
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(body.isActiveAttack(attack) && body.weaponDrawn(),
                "opening registers the attack before the first idle-hand sweep");
            helper.assertTrue(attack.fire(fixture.target()), "the first strike starts after drawing");
        });
        helper.onEachTick(attack::tick);
        helper.runAfterDelay(13, () -> {
            helper.assertValueEqual(attack.state(), WeaponAttack.State.READY, "the first strike has finished");
            helper.assertValueEqual(body.getMainHandItem().getDamageValue(), 8, "the first strike wears the sword");
        });
        helper.runAfterDelay(16, () -> {
            helper.assertTrue(body.isActiveAttack(attack) && body.weaponDrawn(),
                "the active pointer covers idle ticks between strikes of the same engagement");
            helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), 0, "the weapon stays out of the pack");
            helper.assertTrue(Weapons.select(worker, fixture.target(), attack) == attack,
                "the engagement reuses its ready attack");
            helper.assertTrue(attack.fire(fixture.target()), "the same drawn weapon starts another strike");
        });
        helper.runAfterDelay(29, () -> {
            try {
                helper.assertValueEqual(attack.state(), WeaponAttack.State.READY, "the second strike has finished");
                helper.assertValueEqual(body.getMainHandItem().getDamageValue(), 9, "both strikes wear one sword");
                helper.assertTrue(body.isActiveAttack(attack) && body.weaponDrawn(),
                    "the weapon remains drawn until the engagement ends");
                attack.stop();
            } finally {
                fixture.close();
            }
            helper.succeed();
        });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core")
    public static void splitHandStackKeepsCurrentWearWithoutAnItemLedger(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 7);
        ResidentEntity body = fixture.body();
        try {
            WeaponAttack attack = Weapons.open(worker(body)).orElseThrow();
            body.getMainHandItem().setDamageValue(12);
            ItemStack moved = body.getMainHandItem().split(1);
            body.setItemSlot(EquipmentSlot.MAINHAND, moved);
            helper.assertFalse(attack.fire(fixture.target()), "replacement revokes the old attack");
            helper.assertTrue(body.weaponDrawn(), "the moved physical stack remains drawn");
            helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), 0, "no old reference becomes a duplicate");
            body.getMainHandItem().setDamageValue(13);
            body.holsterWeapon();
            body.holsterWeapon();
            helper.assertTrue(body.getMainHandItem().isEmpty(), "holstering empties the native slot");
            helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), 1, "exactly one physical sword survives");
            helper.assertValueEqual(find(body, Items.IRON_SWORD).getDamageValue(), 13, "wear never rolls back");
            helper.assertValueEqual(find(body, Items.IRON_SWORD).get(DataComponents.CUSTOM_NAME),
                Component.literal("actual weapon"), "components survive splitting and holstering");
        } finally {
            fixture.close();
        }
        helper.succeed();
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void bowUsesActualArrowsAndNativeDurabilityAndHurtsItsTarget(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 7);
        ResidentEntity body = fixture.body();
        ItemStack ammo = new ItemStack(Items.ARROW, 3);
        ammo.set(DataComponents.CUSTOM_NAME, Component.literal("actual bow ammunition"));
        body.pack().insert(ammo);
        WeaponAttack attack = Weapons.open(worker(body)).orElseThrow();
        helper.assertValueEqual(body.pack().count(Items.BOW), 0, "the pack does not retain a duplicate bow");
        helper.assertTrue(body.getMainHandItem().is(Items.BOW), "the actual bow is equipped");
        helper.assertFalse(Weapons.open(worker(body)).isPresent(), "a second attack cannot claim the hand");
        helper.assertTrue(attack.fire(fixture.target()), "a real arrow permits drawing");
        AtomicInteger seen = ticking(helper, fixture, attack);
        helper.runAfterDelay(
            45,
            () -> {
                try {
                    helper.assertTrue(seen.get() > 0, "native shooting creates an arrow owned by the resident");
                    helper.assertTrue(
                        fixture.target().getHealth() < fixture.target().getMaxHealth(),
                        "the native arrow hits its target");
                    helper.assertValueEqual(body.pack().count(Items.ARROW), 2, "one actual arrow is consumed");
                    attack.stop();
                    fixture.body().holsterWeapon();
                    attack.stop();
                    fixture.body().holsterWeapon();
                    ItemStack returned = find(body, Items.BOW);
                    helper.assertValueEqual(
                        returned.getDamageValue(), 8, "native shooting wears the actual bow once");
                    helper.assertValueEqual(
                        returned.get(DataComponents.CUSTOM_NAME),
                        Component.literal("actual weapon"),
                        "the actual weapon's components survive the loan");
                    helper.assertValueEqual(body.pack().count(Items.BOW), 1, "closing twice returns only one bow");
                } finally {
                    fixture.close();
                }
                helper.succeed();
            });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void crossbowNativeReloadPersistsARealChargedProjectileAcrossSave(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.CROSSBOW, 4);
        ResidentEntity body = fixture.body();
        ItemStack arrow = new ItemStack(Items.TIPPED_ARROW, 3);
        arrow.set(DataComponents.CUSTOM_NAME, Component.literal("loaded arrow component"));
        body.pack().insert(arrow);
        WeaponAttack attack = Weapons.open(worker(body)).orElseThrow();
        helper.assertTrue(attack.reload(), "an actual crossbow begins native reload");
        AtomicInteger seen = ticking(helper, fixture, attack);
        helper.runAfterDelay(
            30,
            () -> {
                helper.assertTrue(CrossbowItem.isCharged(body.getMainHandItem()) && !body.isUsingItem(), "reload finishes without firing");
                helper.assertValueEqual(
                    body.pack().count(Items.TIPPED_ARROW), 2, "native reload consumes one real arrow");
                helper.assertTrue(
                    CrossbowItem.isCharged(body.getMainHandItem()), "the native charged component is present");
                helper.assertValueEqual(seen.get(), 0, "reloading does not shoot");
                CompoundTag saved = body.saveWithoutId(new CompoundTag());
                helper.assertTrue(CrossbowItem.isCharged(body.getMainHandItem()) && !body.isUsingItem(),
                    "saving preserves the live attack and its charged weapon");
                body.load(saved);
                helper.assertFalse(attack.fire(fixture.target()), "loading ends the old live attack");
                ItemStack actual = find(body, Items.CROSSBOW);
                helper.assertTrue(
                    CrossbowItem.isCharged(actual), "the saved pack retains the native loaded projectile");
                helper.assertValueEqual(
                    actual.get(DataComponents.CHARGED_PROJECTILES)
                    .getItems()
                    .getFirst()
                    .get(DataComponents.CUSTOM_NAME),
                    Component.literal("loaded arrow component"),
                    "ammunition components survive native reload and saving");
                WeaponAttack loaded = Weapons.open(worker(body)).orElseThrow();
                helper.assertTrue(
                    loaded.fire(fixture.target()),
                    "the saved charged crossbow fires without fresh ammo consumption");
                helper.onEachTick(loaded::tick);
                helper.runAfterDelay(
                    12,
                    () -> {
                        try {
                            helper.assertTrue(
                                fixture.target().getHealth() < fixture.target().getMaxHealth(),
                                "the native crossbow projectile hits");
                            loaded.stop();
                            fixture.body().holsterWeapon();
                            ItemStack returned = find(body, Items.CROSSBOW);
                            helper.assertFalse(
                                CrossbowItem.isCharged(returned),
                                "native shooting clears the charged component");
                            helper.assertValueEqual(
                                returned.getDamageValue(),
                                5,
                                "native firing wears the same actual crossbow");
                            helper.assertValueEqual(
                                body.pack().count(Items.TIPPED_ARROW),
                                2,
                                "firing consumes the loaded arrow only");
                            helper.assertValueEqual(
                                body.pack().count(Items.CROSSBOW),
                                1,
                                "save and shooting retain one crossbow");
                        } finally {
                            fixture.close();
                        }
                        helper.succeed();
                    });
            });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void stoppingABowRejectsFurtherFireAndCreatesNoProjectile(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 11);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 2));
        WeaponAttack attack = Weapons.open(worker(fixture.body())).orElseThrow();
        helper.assertTrue(attack.fire(fixture.target()), "draw starts with actual ammunition");
        AtomicInteger seen = ticking(helper, fixture, attack);
        helper.runAfterDelay(
            10,
            () -> {
                attack.stop();
                helper.assertTrue(fixture.body().weaponDrawn(), "stopping behavior leaves the physical weapon drawn");
                helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 0, "stop never moves the weapon");
                helper.assertFalse(attack.fire(fixture.target()), "stopped attacks reject new fire intents");
            });
        helper.runAfterDelay(
            40,
            () -> {
                try {
                    helper.assertValueEqual(seen.get(), 0, "stopping never invokes bow release");
                    helper.assertValueEqual(
                        fixture.body().pack().count(Items.ARROW), 2, "cancelled draw spends no arrow");
                    attack.stop();
                    fixture.body().holsterWeapon();
                    attack.tick();
                    attack.stop();
                    fixture.body().holsterWeapon();
                    helper.assertValueEqual(
                        find(fixture.body(), Items.BOW).getDamageValue(), 11, "cancelled draw wears nothing");
                    helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 1, "stopped bow returns once");
                } finally {
                    fixture.close();
                }
                helper.succeed();
            });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void loadingDuringBowDrawKeepsOneActualWeaponAndCancelsFire(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 9);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 2));
        WeaponAttack attack = Weapons.open(worker(fixture.body())).orElseThrow();
        attack.fire(fixture.target());
        AtomicInteger seen = ticking(helper, fixture, attack);
        helper.runAfterDelay(
            10,
            () -> {
                CompoundTag saved = fixture.body().saveWithoutId(new CompoundTag());
                helper.assertTrue(fixture.body().weaponDrawn() && fixture.body().isUsingItem(), "saving preserves the live draw");
                fixture.body().load(saved);
                helper.assertFalse(attack.fire(fixture.target()), "loading cancels the old draw");
                helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 1, "the snapshot retains one bow");
                helper.assertTrue(fixture.body().getMainHandItem().isEmpty(), "there is no saved hand duplicate");
                helper.assertValueEqual(
                    find(fixture.body(), Items.BOW).getDamageValue(), 9, "saved durability is exact");
            });
        helper.runAfterDelay(
            40,
            () -> {
                try {
                    helper.assertValueEqual(seen.get(), 0, "the pre-load callback cannot fire afterwards");
                    helper.assertValueEqual(
                        fixture.body().pack().count(Items.ARROW), 2, "saving during draw spends no arrow");
                } finally {
                    fixture.close();
                }
                helper.succeed();
            });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void targetBecomingAlliedDuringDrawPreventsDischarge(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 0);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 1));
        WeaponAttack attack = Weapons.open(worker(fixture.body())).orElseThrow();
        attack.fire(fixture.target());
        AtomicInteger seen = ticking(helper, fixture, attack);
        helper.runAfterDelay(
            10,
            () -> {
                var board = helper.getLevel().getScoreboard();
                var team = board.addPlayerTeam("weapon_" + fixture.body().getUUID().toString().substring(0, 8));
                board.addPlayerToTeam(fixture.body().getScoreboardName(), team);
                board.addPlayerToTeam(fixture.target().getScoreboardName(), team);
            });
        helper.runAfterDelay(
            35,
            () -> {
                try {
                    helper.assertValueEqual(seen.get(), 0, "hostility is checked during charging and at discharge");
                    helper.assertValueEqual(
                        fixture.body().pack().count(Items.ARROW),
                        1,
                        "revoked permission consumes no ammunition");
                    attack.stop();
                    fixture.body().holsterWeapon();
                } finally {
                    var team = fixture.body().getTeam();
                    if (team != null) {
                        helper.getLevel().getScoreboard().removePlayerTeam((PlayerTeam) team);
                    }
                    fixture.close();
                }
                helper.succeed();
            });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void explicitNeutralTargetUsesTheActualNativeWeapon(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 5);
        var cow = EntityType.COW.create(helper.getLevel());
        cow.setNoAi(true);
        cow.moveTo(fixture.target().getX(), fixture.target().getY(), fixture.target().getZ());
        fixture.target().discard();
        helper.getLevel().addFreshEntity(cow);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 2));
        WeaponAttack attack = Weapons.open(worker(fixture.body())).orElseThrow();
        helper.assertFalse(attack.fire(cow), "autonomous fire still rejects neutral mobs");
        helper.assertTrue(attack.explicitTarget(cow, () -> true), "the explicit order binds one live target");
        helper.assertTrue(attack.fire(cow), "an explicitly ordered neutral target starts a native shot");
        AtomicInteger seen = ticking(helper, fixture, attack);
        helper.runAfterDelay(
            45,
            () -> {
                try {
                    helper.assertTrue(
                        seen.get() > 0 && cow.getHealth() < cow.getMaxHealth(),
                        "the explicit target takes real native projectile damage");
                    helper.assertValueEqual(
                        fixture.body().pack().count(Items.ARROW), 1, "one actual arrow is consumed");
                    attack.stop();
                    fixture.body().holsterWeapon();
                    helper.assertValueEqual(
                        find(fixture.body(), Items.BOW).getDamageValue(), 6, "native durability is retained");
                    helper.assertValueEqual(
                        fixture.body().pack().count(Items.BOW), 1, "the real weapon returns once");
                } finally {
                    cow.discard();
                    fixture.close();
                }
                helper.succeed();
            });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void explicitPermissionIsTargetBoundAndRevocationCancelsTheDraw(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 5);
        var cow = EntityType.COW.create(helper.getLevel());
        cow.setNoAi(true);
        cow.moveTo(fixture.target().getX(), fixture.target().getY(), fixture.target().getZ());
        helper.getLevel().addFreshEntity(cow);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 1));
        AtomicBoolean permitted = new AtomicBoolean(true);
        WeaponAttack attack = Weapons.open(worker(fixture.body())).orElseThrow();
        helper.assertTrue(attack.explicitTarget(cow, permitted::get), "a live order lease is accepted");
        helper.assertFalse(
            attack.fire(fixture.target()), "the explicit lease cannot fire at a different hostile target");
        helper.assertTrue(attack.fire(cow), "the bound target begins drawing");
        AtomicInteger seen = ticking(helper, fixture, attack);
        helper.runAfterDelay(10, () -> permitted.set(false));
        helper.runAfterDelay(
            35,
            () -> {
                try {
                    helper.assertValueEqual(
                        seen.get(), 0, "revocation cancels charging before projectile discharge");
                    helper.assertValueEqual(
                        cow.getHealth(), cow.getMaxHealth(), "the revoked neutral target is unharmed");
                    helper.assertValueEqual(
                        fixture.body().pack().count(Items.ARROW), 1, "revocation spends no arrow");
                    helper.assertFalse(attack.fire(fixture.target()), "a revoked attack is terminal");
                    attack.stop();
                    fixture.body().holsterWeapon();
                    attack.stop();
                    fixture.body().holsterWeapon();
                    helper.assertValueEqual(
                        fixture.body().pack().count(Items.BOW), 1, "revocation returns the hand loan once");
                } finally {
                    cow.discard();
                    fixture.close();
                }
                helper.succeed();
            });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core")
    public static void aFullPackSpillsTheActualWeaponOnlyOnceOnHolster(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 17);
        WeaponAttack attack = Weapons.open(worker(fixture.body())).orElseThrow();
        for (int slot = 0; slot < fixture.body().pack().getContainerSize(); slot++) {
            fixture.body().pack().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        try {
            attack.stop();
            fixture.body().holsterWeapon();
            attack.stop();
            fixture.body().holsterWeapon();
            List<ItemEntity> swords =
                helper.getLevel()
                .getEntitiesOfClass(
                ItemEntity.class,
                fixture.body().getBoundingBox().inflate(2),
                item -> item.getItem().is(Items.IRON_SWORD));
            helper.assertValueEqual(
                swords.stream().mapToInt(item -> item.getItem().getCount()).sum(),
                1,
                "overflow returns exactly one real sword as a drop");
            helper.assertValueEqual(swords.getFirst().getItem().getDamageValue(), 17, "drop keeps durability");
            helper.assertValueEqual(
                swords.getFirst().getItem().get(DataComponents.CUSTOM_NAME),
                Component.literal("actual weapon"),
                "drop keeps components");
            swords.forEach(Entity::discard);
        } finally {
            fixture.close();
        }
        helper.succeed();
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void deathDuringCrossbowReloadDropsInventoryAndWeaponOnce(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.CROSSBOW, 6);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 3));
        WeaponAttack attack = Weapons.open(worker(fixture.body())).orElseThrow();
        attack.fire(fixture.target());
        AtomicInteger seen = ticking(helper, fixture, attack);
        helper.runAfterDelay(10, () -> fixture.body().hurt(helper.getLevel().damageSources().genericKill(), 1000));
        helper.runAfterDelay(
            40,
            () -> {
                try {
                    attack.stop();
                    fixture.body().holsterWeapon();
                    attack.tick();
                    helper.assertFalse(attack.fire(fixture.target()), "death terminates reload");
                    helper.assertValueEqual(seen.get(), 0, "a dead resident cannot discharge a pending crossbow");
                    List<ItemEntity> drops =
                        helper.getLevel()
                        .getEntitiesOfClass(
                        ItemEntity.class, fixture.target().getBoundingBox().inflate(12));
                    helper.assertValueEqual(
                        drops.stream()
                        .filter(item -> item.getItem().is(Items.CROSSBOW))
                        .mapToInt(item -> item.getItem().getCount())
                        .sum(),
                        1,
                        "death drops one actual crossbow");
                    helper.assertValueEqual(
                        drops.stream()
                        .filter(item -> item.getItem().is(Items.ARROW))
                        .mapToInt(item -> item.getItem().getCount())
                        .sum(),
                        3,
                        "cancelled reload drops all unspent ammunition");
                } finally {
                    fixture.close();
                }
                helper.succeed();
            });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core")
    public static void unloadAndDismissalStopAndHolsterAndNoAmmoCannotFire(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 0);
        try {
            helper.assertTrue(Weapons.armed(worker(fixture.body())),
                "an unprofiled resident attempts combat at execution");
            helper.assertTrue(Weapons.open(worker(fixture.body())).isEmpty(), "automatic selection skips an empty bow");
            fixture.body().pack().insert(new ItemStack(Items.IRON_SWORD));
            helper.assertFalse(
                Weapons.ranged(worker(fixture.body())), "an empty bow falls back to the real carried sword");
            WeaponAttack sword = Weapons.open(worker(fixture.body())).orElseThrow();
            helper.assertTrue(
                fixture.body().getMainHandItem().is(Items.IRON_SWORD), "the selected fallback is the actual sword");
            sword.stop();
            fixture.body().holsterWeapon();
            helper.assertTrue(Weapons.BOW.open(worker(fixture.body())).isEmpty(),
                "direct attack opening also rejects a bow without arrows");
            fixture.body().pack().insert(new ItemStack(Items.ARROW));
            WeaponAttack attack = Weapons.BOW.open(worker(fixture.body())).orElseThrow();
            fixture.body().leaveColony();
            helper.assertFalse(attack.fire(fixture.target()), "dismissal closes the loan immediately");
            helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 1, "dismissal returns one bow");
            WeaponAttack next = Weapons.BOW.open(worker(fixture.body())).orElseThrow();
            fixture.body().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            next.tick();
            next.stop();
            fixture.body().holsterWeapon();
            helper.assertFalse(next.fire(fixture.target()), "the unload lifecycle closes the next loan");
            helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 1, "unload returns one bow");
        } finally {
            fixture.close();
        }
        helper.succeed();
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core")
    public static void aRazedColonyRevokesPendingFireBeforeTheResidentsPeriodicCleanup(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 0);
        var colony = Colonies.mint(helper.getLevel().getServer());
        fixture.body().joinColony(colony.id());
        ColonyFront.of(colony)
            .setSetting(
            PatrolContent.ID,
            "no_profile",
            new ColonySettings.Value.Choice(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "engage")));
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 2));
        WeaponAttack attack = Weapons.open(worker(fixture.body())).orElseThrow();
        helper.assertTrue(attack.fire(fixture.target()), "the colony resident can begin an attack");
        try {
            colony.raze();
            attack.tick();
            helper.assertFalse(attack.fire(fixture.target()), "raze invalidates pending fire immediately");
            helper.assertTrue(fixture.body().weaponDrawn(), "attack cancellation leaves physical holstering to the node");
            fixture.body().holsterWeapon();
            helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 1, "raze cleanup returns one actual bow");
            helper.assertValueEqual(fixture.body().pack().count(Items.ARROW), 2, "raze spends no ammunition");
        } finally {
            fixture.close();
        }
        helper.succeed();
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void replacingADrawnBowWithAnotherBowStopsTheOldAttack(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 9);
        ResidentEntity body = fixture.body();
        body.pack().insert(new ItemStack(Items.ARROW, 3));
        WeaponAttack attack = Weapons.open(worker(body)).orElseThrow();
        helper.assertTrue(attack.fire(fixture.target()), "the original real bow begins drawing");
        ItemStack replacement = new ItemStack(Items.BOW);
        replacement.setDamageValue(23);
        replacement.set(DataComponents.CUSTOM_NAME, Component.literal("external replacement"));
        AtomicInteger seen = ticking(helper, fixture, attack);
        helper.runAfterDelay(
            10,
            () -> {
                body.setItemSlot(EquipmentSlot.MAINHAND, replacement);
                helper.assertFalse(attack.fire(fixture.target()),
                    "external replacement closes the old pending attack immediately");
                helper.assertTrue(
                    ItemStack.matches(body.getMainHandItem(), replacement) && !body.hasActiveAttack(),
                    "the replacement is installed without becoming owned by the previous attack");
                helper.assertFalse(
                    attack.fire(fixture.target()),
                    "the old attack cannot adopt the replacement for another shot");
                helper.assertValueEqual(
                    find(body, Items.BOW).getDamageValue(),
                    9,
                    "the original loan returns with its exact durability");
            });
        helper.runAfterDelay(
            40,
            () -> {
                try {
                    attack.stop();
                    fixture.body().holsterWeapon();
                    attack.tick();
                    helper.assertValueEqual(
                        seen.get(), 0, "replacing the hand cancels the original pending projectile");
                    helper.assertValueEqual(body.pack().count(Items.ARROW), 3, "replacement spends no ammunition");
                    var original =
                        body.pack().contents().stream()
                        .filter(
                        stack ->
                            Component.literal("actual weapon")
                                .equals(stack.get(DataComponents.CUSTOM_NAME)))
                        .toList();
                    var external =
                        body.pack().contents().stream()
                        .filter(
                        stack ->
                            Component.literal("external replacement")
                                .equals(stack.get(DataComponents.CUSTOM_NAME)))
                        .toList();
                    helper.assertValueEqual(
                        original.size(), 1, "the original components identify exactly one returned weapon");
                    helper.assertValueEqual(
                        original.getFirst().getDamageValue(), 9, "the original durability remains unchanged");
                    helper.assertValueEqual(
                        external.size(), 1, "normal stray-hand recovery retains the replacement exactly once");
                    helper.assertTrue(
                        external.getFirst().is(Items.BOW), "the replacement keeps its own item identity");
                    helper.assertValueEqual(
                        external.getFirst().getDamageValue(),
                        23,
                        "the replacement keeps its own durability");
                    helper.assertTrue(
                        body.getMainHandItem().isEmpty(), "neither actual item leaves a duplicate in the hand");
                } finally {
                    fixture.close();
                }
                helper.succeed();
            });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void nativeBowBreakageDoesNotReturnAnExhaustedWeapon(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, new ItemStack(Items.BOW).getMaxDamage() - 1);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 1));
        WeaponAttack attack = Weapons.open(worker(fixture.body())).orElseThrow();
        helper.assertTrue(attack.fire(fixture.target()), "the last real arrow can discharge the nearly broken bow");
        AtomicInteger seen = ticking(helper, fixture, attack);
        helper.onEachTick(
            () -> {
                if (!fixture.body().weaponDrawn()) {
                    helper.assertFalse(attack.fire(fixture.target()),
                        "native breakage leaves the old attack terminal in the same tick");
                }
            });
        helper.runAfterDelay(
            40,
            () -> {
                try {
                    attack.stop();
                    fixture.body().holsterWeapon();
                    attack.stop();
                    fixture.body().holsterWeapon();
                    helper.assertTrue(seen.get() > 0, "native shooting occurs before native breakage");
                    helper.assertValueEqual(
                        fixture.body().pack().count(Items.BOW),
                        0,
                        "closing never resurrects the exhausted bow");
                    helper.assertValueEqual(
                        fixture.body().pack().count(Items.ARROW),
                        0,
                        "native shooting spends the last actual arrow");
                    helper.assertTrue(
                        fixture.body().getMainHandItem().isEmpty(), "no exhausted weapon remains in the hand");
                    helper.assertTrue(
                        helper.getLevel()
                        .getEntitiesOfClass(
                        ItemEntity.class,
                        fixture.body().getBoundingBox().inflate(3),
                        item -> item.getItem().is(Items.BOW))
                        .isEmpty(),
                        "native breakage cannot spill a replacement copy");
                } finally {
                    fixture.close();
                }
                helper.succeed();
            });
    }

    @GameTest(batch = "vanilla_weapons", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void changingPreferenceStopsTheOldDrawAndEmptyAssignedBowFails(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 3);
        Colony colony = enroll(helper, fixture.body());
        ResidentEntity body = fixture.body();
        ItemStack bow = new ItemStack(Items.BOW);
        bow.setDamageValue(7);
        bow.set(DataComponents.CUSTOM_NAME, Component.literal("cancelled preferred bow"));
        body.pack().insert(bow);
        body.pack().insert(new ItemStack(Items.ARROW, 2));
        arm(colony, body, "Bow", ItemSpec.of(Items.BOW));
        WeaponAttack attack = Weapons.open(worker(body)).orElseThrow();
        helper.assertTrue(attack.fire(fixture.target()), "the preferred bow begins drawing");
        AtomicInteger seen = ticking(helper, fixture, attack);
        helper.runAfterDelay(
            10,
            () -> {
                arm(colony, body, "Bow", ItemSpec.of(Items.BOW));
                helper.assertTrue(fixture.body().weaponDrawn() && fixture.body().isUsingItem(),
                    "reapplying the same preference does not cancel an active draw");
                arm(colony, body, "Melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS));
                helper.assertFalse(attack.fire(fixture.target()),
                    "an actual preference change closes the old local attack immediately");
                helper.assertTrue(
                    !body.weaponDrawn() && body.getMainHandItem().isEmpty(),
                    "normal closure returns the old actual weapon before reselection");
                helper.assertValueEqual(body.pack().count(Items.BOW), 1, "the cancelled bow returns once");
                WeaponAttack melee = Weapons.open(worker(body)).orElseThrow();
                helper.assertTrue(
                    body.getMainHandItem().is(Items.IRON_SWORD),
                    "the changed preference selects the actual melee weapon");
                melee.stop();
                fixture.body().holsterWeapon();
                for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
                    if (body.pack().getItem(slot).is(Items.ARROW)) {
                        body.pack().removeItemNoUpdate(slot);
                    }
                }
                arm(colony, body, "Bow", ItemSpec.of(Items.BOW));
                helper.assertTrue(Weapons.open(worker(body)).isEmpty(),
                    "the assigned empty bow fails at execution rather than using an unassigned sword");
            });
        helper.runAfterDelay(
            40,
            () -> {
                try {
                    helper.assertValueEqual(
                        seen.get(),
                        0,
                        "the old cancelled draw creates no projectile after the preference changed");
                    helper.assertValueEqual(
                        fixture.target().getHealth(),
                        fixture.target().getMaxHealth(),
                        "preference switching never finishes the pending shot");
                    helper.assertValueEqual(
                        find(body, Items.BOW).getDamageValue(),
                        7,
                        "the cancelled original keeps its durability");
                    helper.assertValueEqual(
                        find(body, Items.BOW).get(DataComponents.CUSTOM_NAME),
                        Component.literal("cancelled preferred bow"),
                        "preference switching preserves the original components");
                    helper.assertValueEqual(
                        body.pack().count(Items.BOW), 1, "multiple preference changes retain one actual bow");
                    helper.assertValueEqual(
                        body.pack().count(Items.IRON_SWORD), 1, "multiple selections retain one actual sword");
                } finally {
                    fixture.close();
                    colony.raze();
                }
                helper.succeed();
            });
    }

    private static Colony enroll(GameTestHelper helper, ResidentEntity body) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        return colony;
    }

    private static AtomicInteger ticking(GameTestHelper helper, Fixture fixture, WeaponAttack attack) {
        AtomicInteger seen = new AtomicInteger();
        helper.onEachTick(
            () -> {
                attack.tick();
                int arrows =
                    helper.getLevel()
                    .getEntitiesOfClass(
                    AbstractArrow.class,
                    fixture.body().getBoundingBox().inflate(20),
                    arrow -> arrow.getOwner() == fixture.body())
                    .size();
                seen.accumulateAndGet(arrows, Math::max);
            });
        return seen;
    }

    private static Fixture fixture(GameTestHelper helper, Item item, int damage) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 4; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        ResidentEntity body = PersonBody.RESIDENT.get().create(helper.getLevel());
        Zombie target = EntityType.ZOMBIE.create(helper.getLevel());
        body.setNoAi(true);
        target.setNoAi(true);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        target.getAttribute(Attributes.ARMOR).setBaseValue(0);
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        body.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        target.moveTo(at.getX() + 6.5, at.getY(), at.getZ() + .5);
        // Keep native entity and projectile ticks running outside spawn in the combined test grid.
        helper.getLevel().getChunkSource().addRegionTicket(TICKING, body.chunkPosition(), 2, body.getUUID());
        helper.getLevel().addFreshEntity(body);
        helper.getLevel().addFreshEntity(target);
        ItemStack actual = new ItemStack(item);
        actual.setDamageValue(damage);
        actual.set(DataComponents.CUSTOM_NAME, Component.literal("actual weapon"));
        body.pack().insert(actual);
        Fixture fixture = new Fixture(body, target);
        Cleanup.on(helper, fixture::close);
        return fixture;
    }

    private record Fixture(ResidentEntity body, Zombie target) {
        void close() {
            ((ServerLevel) body.level())
                .getChunkSource()
                .removeRegionTicket(TICKING, body.chunkPosition(), 2, body.getUUID());
            body.discard();
            target.discard();
        }
    }

    private static ItemStack find(ResidentEntity body, Item item) {
        return body.pack().contents().stream().filter(stack -> stack.is(item)).findFirst().orElse(ItemStack.EMPTY);
    }

    static Worker worker(ResidentEntity body) {
        return TestWorker.of(body)
            .held(need -> body.pack().contents().stream().filter(stack -> Tools.answers(stack, need)).findFirst()
                .orElse(ItemStack.EMPTY))
            .spill(body::spawnAtLocation);
    }

    private static void arm(Colony colony, Body body, String name, ItemSpec weapon) {
        LoadoutProfiles profiles = LoadoutProfiles.of(colony);
        LoadoutProfile profile =
            profiles.all().stream()
            .filter(one -> one.name().equals(name))
            .findFirst()
            .orElseGet(() -> profiles.add(profiles.create(name).id(), LoadoutProfile.Entry.weapon(weapon)));
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
    }

    private static void assignLoadout(
        PatrolSupplies supplies,
        Body body, String name) {
        var profiles = supplies.profiles();
        var profile = profiles.all().stream().filter(value -> value.name().equals(name)).findFirst()
            .orElseGet(() -> {
                var created = profiles.create(name);
                var weapon = switch (name) {
                    case "melee" -> ItemSpec.of(
                        Tags.Items.MELEE_WEAPON_TOOLS);
                    case "bow" -> ItemSpec.of(Items.BOW);
                    case "crossbow" -> ItemSpec.of(Items.CROSSBOW);
                    default -> throw new IllegalArgumentException(name);
                };
                var entries = new ArrayList<LoadoutProfile.Entry>();
                entries.add(LoadoutProfile.Entry.weapon(weapon));
                if (!name.equals("melee")) {
                    entries.add(LoadoutProfile.Entry.supply(
                        ItemSpec.of(ItemTags.ARROWS), 8, 8));
                }
                profiles.replace(new LoadoutProfile(
                    created.id(), created.name(), entries));
                return profiles.get(created.id()).orElseThrow();
            });
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
    }
}
