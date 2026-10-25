package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentShieldGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentShieldGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void realShieldBlocksFrontMeleeButNotRearOrBypassDamage(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 3);
        ResidentEntity body = fixture.body();
        ItemStack shield = body.getOffhandItem();
        helper.assertTrue(ResidentShields.guard(body, fixture.target()), "the physical offhand begins use");
        helper.assertFalse(body.isBlocking(), "the native five tick raise delay is preserved");
        helper.runAfterDelay(8, () -> {
            try {
                ResidentShields.guard(body, fixture.target());
                helper.assertTrue(body.isBlocking(), "the native shield is blocking after raising");
                body.hurt(helper.getLevel().damageSources().mobAttack(fixture.target()), 6);
                helper.assertValueEqual(body.getHealth(), body.getMaxHealth(), "a frontal melee hit is blocked by vanilla");
                helper.assertValueEqual(shield.getDamageValue(), 7, "the exact shield receives 1 + floor(damage) wear");
                helper.assertTrue(body.getOffhandItem() == shield, "blocking retains the original physical slot stack");

                // Keep the actual facing from the frontal guard while moving the attacker behind it.
                fixture.target().setPos(body.getX() - 3, body.getY(), body.getZ());
                body.invulnerableTime = 0;
                body.hurt(helper.getLevel().damageSources().mobAttack(fixture.target()), 4);
                helper.assertValueEqual(body.getHealth(), body.getMaxHealth() - 4, "rear damage passes the native direction test");
                helper.assertValueEqual(shield.getDamageValue(), 7, "rear damage does not wear a shield that did not block");
                body.invulnerableTime = 0;
                body.hurt(helper.getLevel().damageSources().magic(), 3);
                helper.assertValueEqual(body.getHealth(), body.getMaxHealth() - 7, "shield bypass damage still hurts a blocking resident");
                helper.assertValueEqual(shield.getDamageValue(), 7, "bypass damage does not wear the shield");
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void nativeFrontalArrowCollisionBlocksAndWearsTheActualShield(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 6);
        ResidentEntity body = fixture.body();
        ResidentShields.guard(body, fixture.target());
        helper.onEachTick(() -> ResidentShields.guard(body, fixture.target()));
        helper.runAfterDelay(8, () -> {
            Arrow arrow = new Arrow(helper.getLevel(), fixture.target(), new ItemStack(Items.ARROW), null);
            arrow.setPos(body.getX() + 4, body.getY() + 1, body.getZ());
            arrow.setNoGravity(true);
            arrow.shoot(-1, 0, 0, 1.5F, 0);
            helper.getLevel().addFreshEntity(arrow);
        });
        helper.runAfterDelay(20, () -> {
            try {
                helper.assertValueEqual(body.getHealth(), body.getMaxHealth(), "an actual incoming arrow collision is blocked");
                helper.assertTrue(body.getOffhandItem().getDamageValue() > 0, "a real arrow hit wears the original shield");
                helper.assertValueEqual(body.pack().count(Items.SHIELD), 0, "there is no duplicate shield in the pack");
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void shieldBreakageClearsRealUseAndNeverResurrectsTheItem(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 3);
        ResidentEntity body = fixture.body();
        body.getOffhandItem().setDamageValue(body.getOffhandItem().getMaxDamage() - 1);
        ResidentShields.guard(body, fixture.target());
        helper.runAfterDelay(8, () -> {
            try {
                ResidentShields.guard(body, fixture.target());
                body.hurt(helper.getLevel().damageSources().mobAttack(fixture.target()), 6);
                helper.assertValueEqual(body.getHealth(), body.getMaxHealth(), "the breaking shield still blocks its last frontal hit");
                helper.assertTrue(body.getOffhandItem().isEmpty(), "native durability consumes the exhausted shield");
                helper.assertFalse(body.isUsingItem(), "breakage clears native offhand use immediately");
                helper.assertFalse(ResidentShields.guard(body, fixture.target()), "an exhausted shield cannot be raised again");
                body.saveWithoutId(new CompoundTag());
                helper.assertTrue(body.getOffhandItem().isEmpty(), "saving cannot restore the broken shield");
                helper.assertValueEqual(body.pack().count(Items.SHIELD), 0, "no exhausted copy returns to the pack");
                helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    body.getBoundingBox().inflate(4), item -> item.getItem().is(Items.SHIELD)).isEmpty(),
                    "breaking a shield does not spill a replacement copy");
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 160)
    public static void nativeAxeDisablesDefenseForOneHundredTicksAcrossSaving(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 3);
        ResidentEntity body = fixture.body();
        fixture.target().setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        ResidentShields.guard(body, fixture.target());
        helper.runAfterDelay(8, () -> {
            ResidentShields.guard(body, fixture.target());
            body.hurt(helper.getLevel().damageSources().mobAttack(fixture.target()), 6);
            helper.assertValueEqual(body.shieldCooldownTicks(), 100, "native axe shield disabling starts a five second cooldown");
            helper.assertFalse(body.isBlocking(), "the disabling hit lowers the shield immediately");
            helper.assertFalse(ResidentShields.guard(body, fixture.target()), "the cooldown rejects an immediate raise");
            CompoundTag saved = body.saveWithoutId(new CompoundTag());
            body.load(saved);
            helper.assertValueEqual(body.shieldCooldownTicks(), 100, "saving and reloading cannot clear shield disable");
            helper.assertFalse(ResidentShields.guard(body, fixture.target()), "the reloaded cooldown still rejects defense");
        });
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(body.shieldCooldownTicks() > 0, "the disabling hit remains active after fifty ticks");
            helper.assertFalse(ResidentShields.guard(body, fixture.target()), "the shield stays unavailable for the full window");
        });
        helper.runAfterDelay(112, () -> {
            try {
                helper.assertValueEqual(body.shieldCooldownTicks(), 0, "the five second cooldown expires normally");
                helper.assertTrue(ResidentShields.guard(body, fixture.target()), "the same actual shield can raise after expiry");
                helper.assertValueEqual(body.getOffhandItem().getDamageValue(), 7, "disable and saving preserve actual shield wear");
                helper.assertValueEqual(body.pack().count(Items.SHIELD), 0, "disable never creates a pack copy");
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void meleeWindupDefendsThenLowersForAnActualHitAndRestoresDefense(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 2);
        ResidentEntity body = fixture.body();
        WeaponSession session = Weapons.open(worker(body)).orElseThrow();
        helper.assertTrue(session.fire(fixture.target()), "the actual sword starts its melee windup");
        helper.onEachTick(session::tick);
        helper.runAfterDelay(9, () -> {
            helper.assertTrue(body.isBlocking(), "early melee windup allows the real shield to raise");
            helper.assertValueEqual(fixture.target().getHealth(), fixture.target().getMaxHealth(), "defense does not grant a premature damage frame");
        });
        helper.runAfterDelay(17, () -> helper.assertFalse(body.isBlocking(), "the shield lowers before the physical attack frame"));
        helper.runAfterDelay(30, () -> {
            try {
                helper.assertTrue(fixture.target().getHealth() < fixture.target().getMaxHealth(), "the attack still deals real native melee damage");
                helper.assertValueEqual(body.getMainHandItem().getDamageValue(), 1, "native melee wears the physical sword once");
                helper.assertTrue(body.isBlocking(), "a completed attack resumes defense against its live target");
                session.close();
                helper.assertFalse(body.isUsingItem(), "session closure releases defense");
                helper.assertTrue(body.getOffhandItem().is(Items.SHIELD), "closing leaves the actual shield in its equipment slot");
                helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), 1, "the sword loan returns once");
                helper.assertValueEqual(body.pack().count(Items.SHIELD), 0, "closing does not duplicate the offhand shield");
            } finally { session.close(); fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void anUnshieldedMeleeAttackUsesItsActualItemSpeedWithoutAddingShieldPreparation(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 2);
        fixture.body().setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        WeaponSession session = Weapons.open(worker(fixture.body())).orElseThrow();
        helper.assertTrue(session.fire(fixture.target()), "an unshielded melee attack starts normally");
        helper.onEachTick(session::tick);
        helper.runAfterDelay(11, () -> helper.assertValueEqual(fixture.target().getHealth(), fixture.target().getMaxHealth(),
            "the actual iron sword's attack speed prevents the old fixed ten-tick hit"));
        helper.runAfterDelay(15, () -> {
            try {
                helper.assertTrue(fixture.target().getHealth() < fixture.target().getMaxHealth(),
                    "the sword's own cycle lands before the twenty-tick shield preparation window");
                helper.assertFalse(fixture.body().isBlocking(), "an absent shield does not create defense");
            } finally { session.close(); fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void breakingAShieldDuringWindupCannotAdvanceTheQueuedMeleeHit(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 2);
        ResidentEntity body = fixture.body();
        body.getOffhandItem().setDamageValue(body.getOffhandItem().getMaxDamage() - 1);
        WeaponSession session = Weapons.open(worker(body)).orElseThrow();
        helper.assertTrue(session.fire(fixture.target()), "the queued shield attack begins a twenty tick cycle");
        helper.onEachTick(session::tick);
        helper.runAfterDelay(8, () -> {
            body.hurt(helper.getLevel().damageSources().mobAttack(fixture.target()), 6);
            helper.assertTrue(body.getOffhandItem().isEmpty(), "the physical shield breaks during the original windup");
        });
        helper.runAfterDelay(17, () -> helper.assertValueEqual(fixture.target().getHealth(), fixture.target().getMaxHealth(),
            "losing the shield cannot shorten an already queued twenty tick attack"));
        helper.runAfterDelay(25, () -> {
            try {
                helper.assertTrue(fixture.target().getHealth() < fixture.target().getMaxHealth(),
                    "the queued attack still lands on its original twenty tick frame");
                helper.assertFalse(body.isBlocking(), "a broken shield grants no phantom defense after the hit");
            } finally { session.close(); fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void defensiveShieldCannotInterruptOrAccidentallyReleaseANativeBow(GameTestHelper helper) {
        rangedExclusivity(helper, Items.BOW);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void defensiveShieldCannotInterruptANativeCrossbowReloadOrShot(GameTestHelper helper) {
        rangedExclusivity(helper, Items.CROSSBOW);
    }

    private static void rangedExclusivity(GameTestHelper helper, Item weapon) {
        Fixture fixture = fixture(helper, weapon, 6);
        ResidentEntity body = fixture.body();
        body.pack().insert(new ItemStack(Items.ARROW, 3));
        WeaponSession session = Weapons.open(worker(body)).orElseThrow();
        AtomicInteger seen = new AtomicInteger();
        ResidentShields.guard(body, fixture.target());
        helper.assertTrue(session.fire(fixture.target()), "the ranged session lowers the shield and owns native mainhand use");
        helper.assertTrue(body.isUsingItem() && body.getUsedItemHand() == InteractionHand.MAIN_HAND,
            "bow draw and crossbow loading begin with exclusive mainhand use");
        helper.onEachTick(() -> {
            if (body.isUsingItem() && body.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                helper.assertFalse(ResidentShields.guard(body, fixture.target()), "defense cannot steal an active ranged use");
                ResidentShields.lower(body);
                helper.assertTrue(body.isUsingItem() && body.getUsedItemHand() == InteractionHand.MAIN_HAND,
                    "lowering a shield cannot cancel or release a drawn ranged weapon");
            }
            session.tick();
            seen.accumulateAndGet(helper.getLevel().getEntitiesOfClass(AbstractArrow.class,
                body.getBoundingBox().inflate(15), arrow -> arrow.getOwner() == body).size(), Math::max);
        });
        helper.runAfterDelay(45, () -> {
            try {
                helper.assertTrue(seen.get() > 0, "native shooting creates a real resident-owned arrow");
                helper.assertTrue(fixture.target().getHealth() < fixture.target().getMaxHealth(), "the actual ranged projectile hits its target");
                helper.assertValueEqual(body.pack().count(Items.ARROW), 2, "one real arrow is consumed exactly once");
                helper.assertValueEqual(body.getMainHandItem().getDamageValue(), 1, "one native shot wears the actual ranged weapon");
                if (weapon == Items.CROSSBOW) helper.assertFalse(CrossbowItem.isCharged(body.getMainHandItem()), "shooting clears the actual loaded component");
                session.close();
                helper.assertFalse(body.isUsingItem(), "closing clears only the session's remaining defense");
                helper.assertTrue(body.getOffhandItem().is(Items.SHIELD), "the shield remains the one original offhand item");
            } finally { session.close(); fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void replacingSavingAndUnloadingDefenseRetainOneActualShield(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 3);
        ResidentEntity body = fixture.body();
        ItemStack original = body.getOffhandItem();
        ResidentShields.guard(body, fixture.target());
        helper.runAfterDelay(8, () -> {
            try {
                ItemStack replacement = new ItemStack(Items.SHIELD);
                body.setItemSlot(EquipmentSlot.OFFHAND, replacement);
                helper.assertFalse(body.isUsingItem(), "replacing the actual offhand cancels the old shield use");
                helper.assertTrue(body.getOffhandItem() == replacement, "the replacement remains the actual native slot stack");
                body.setItemSlot(EquipmentSlot.OFFHAND, original);
                ResidentShields.guard(body, fixture.target());
                CompoundTag saved = body.saveWithoutId(new CompoundTag());
                helper.assertFalse(body.isUsingItem(), "saving lowers defense before native serialization");
                body.load(saved);
                helper.assertTrue(body.getOffhandItem().is(Items.SHIELD), "the actual offhand survives normal serialization");
                helper.assertValueEqual(body.getOffhandItem().get(DataComponents.CUSTOM_NAME), Component.literal("actual shield"),
                    "saving preserves the original shield components");
                helper.assertValueEqual(body.pack().count(Items.SHIELD), 0, "saving never creates a pack copy");
                ResidentShields.guard(body, fixture.target());
                body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                helper.assertFalse(body.isUsingItem(), "unloading releases native defense");
                helper.assertTrue(body.getOffhandItem().is(Items.SHIELD), "unloading retains the actual equipped shield for serialization");
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void deathDropsOneActuallyWornShieldAndStopsGuarding(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 3);
        ResidentEntity body = fixture.body();
        body.getOffhandItem().setDamageValue(23);
        ResidentShields.guard(body, fixture.target());
        helper.runAfterDelay(8, () -> {
            try {
                body.hurt(helper.getLevel().damageSources().genericKill(), 1000);
                helper.assertFalse(body.isUsingItem(), "death immediately terminates shield use");
                List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    body.getBoundingBox().inflate(4), item -> item.getItem().is(Items.SHIELD));
                helper.assertValueEqual(drops.stream().mapToInt(item -> item.getItem().getCount()).sum(), 1,
                    "death drops exactly one owned physical shield");
                helper.assertValueEqual(drops.getFirst().getItem().getDamageValue(), 23, "the death drop retains actual shield durability");
                helper.assertFalse(PersonalItems.marked(drops.getFirst().getItem()), "the dropped shield releases its personal ownership");
                drops.forEach(Entity::discard);
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    private static Fixture fixture(GameTestHelper helper, Item weapon, int distance) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 4; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
        ResidentEntity body = PersonBody.RESIDENT.get().create(helper.getLevel());
        Zombie target = EntityType.ZOMBIE.create(helper.getLevel());
        body.setNoAi(true); target.setNoAi(true);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        target.getAttribute(Attributes.ARMOR).setBaseValue(0);
        BlockPos at = helper.absolutePos(new BlockPos(3, 1, 1));
        body.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        target.moveTo(at.getX() + .5 + distance, at.getY(), at.getZ() + .5);
        body.setYRot(-90); body.setYHeadRot(-90); body.yBodyRot = -90;
        helper.getLevel().addFreshEntity(body); helper.getLevel().addFreshEntity(target);
        body.pack().insert(new ItemStack(weapon));
        ItemStack shield = new ItemStack(Items.SHIELD);
        shield.set(DataComponents.CUSTOM_NAME, Component.literal("actual shield"));
        PersonalItems.mark(body, shield, EquipmentSlot.OFFHAND.getName());
        body.setItemSlot(EquipmentSlot.OFFHAND, shield);
        return new Fixture(body, target);
    }

    private record Fixture(ResidentEntity body, Zombie target) {
        void close() { body.discard(); target.discard(); }
    }

    private static Worker worker(ResidentEntity body) {
        return new Worker() {
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
}
