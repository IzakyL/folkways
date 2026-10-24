package io.github.izakyl.folkways.tacz;

import com.tacz.guns.api.GunProperties;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.init.ModItems;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.resource.pojo.data.gun.ExplosionData;
import com.tacz.guns.resource.pojo.data.gun.Ignite;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysTaczMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class TaczAttachmentGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(TaczAttachmentGameTests.class); }

    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void highExplosiveAttachmentCannotEquipAnOrdinaryGun(GameTestHelper helper) { rejectsAttachment(helper, "ammo_mod_he"); }

    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void incendiaryAttachmentCannotEquipAnOrdinaryGun(GameTestHelper helper) { rejectsAttachment(helper, "ammo_mod_i"); }

    private static void rejectsAttachment(GameTestHelper helper, String attachment) {
        String gun = attachment.equals("ammo_mod_he") ? "m870" : "m16a4";
        var scene = new TaczWeaponGameTests.Fixture(helper, gun, FireMode.SEMI, 6);
        ItemStack actual = scene.body.pack().getItem(0);
        helper.assertTrue(TaczGuns.supported(actual), "ordinary gun is a positive control");
        attach(actual, attachment, helper);
        helper.assertFalse(TaczGuns.supported(actual), "actual attachment effects reject this physical gun");
        helper.assertTrue(Weapons.open(TaczWeaponGameTests.worker(scene.body)).isEmpty(), "unsafe gun never claims the hand");
        String ammo = attachment.equals("ammo_mod_he") ? "12g" : "556x45";
        helper.assertValueEqual(scene.total(ammo), 6, "refusal spends no rounds");
        helper.succeed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void pendingBurstRechecksChangedAttachmentsWithoutWaitingForSessionTick(GameTestHelper helper) {
        var scene = new TaczWeaponGameTests.Fixture(helper, "m16a4", FireMode.BURST, 6);
        scene.firstShot = () -> {
            attach(scene.actual(), "ammo_mod_i", helper);
            AttachmentPropertyManager.postChangeEvent(scene.body, scene.actual());
            scene.advance = false;
        };
        scene.open(); scene.session.fire(scene.target);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.shots.get() == 1, "first ordinary round fires"))
            .thenExecute(() -> scene.waitUntil = System.currentTimeMillis() + 600)
            .thenWaitUntil(() -> helper.assertTrue(System.currentTimeMillis() >= scene.waitUntil, "allow delayed burst tasks to run"))
            .thenExecute(() -> {
                helper.assertValueEqual(scene.shots.get(), 1, "no delayed explosive round escapes the discharge guard");
                helper.assertValueEqual(scene.total("556x45"), 5, "blocked burst rounds remain in the gun");
            }).thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void runtimeIgnitionCacheCannotBypassDischargeCheck(GameTestHelper helper) {
        var scene = new TaczWeaponGameTests.Fixture(helper, "m1911", FireMode.SEMI, 6);
        scene.open();
        var operator = IGunOperator.fromLivingEntity(scene.body);
        operator.getDataHolder().drawTimestamp = System.currentTimeMillis() - 10_000;
        operator.getDataHolder().baseTimestamp = System.currentTimeMillis() - 60_000;
        operator.getCacheProperty().setCache(GunProperties.IGNITE, new Ignite(true, true));
        helper.assertTrue(TaczGuns.supported(scene.actual()), "the physical gun itself remains conventional");
        helper.assertFalse(scene.session.fire(scene.target), "final runtime ignition prevents firing");
        helper.assertValueEqual(scene.shots.get(), 0, "no projectile is created");
        helper.assertValueEqual(scene.total("45acp"), 6, "runtime rejection spends nothing");
        helper.succeed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void finalExplosivePayloadIsRejectedAfterLateFireListenerChanges(GameTestHelper helper) {
        var scene = new TaczWeaponGameTests.Fixture(helper, "m1911", FireMode.SEMI, 6);
        scene.open();
        var operator = IGunOperator.fromLivingEntity(scene.body);
        operator.getDataHolder().drawTimestamp = System.currentTimeMillis() - 10_000;
        operator.getDataHolder().baseTimestamp = System.currentTimeMillis() - 60_000;
        AtomicInteger changed = new AtomicInteger();
        Consumer<GunFireEvent> listener = event -> {
            if (event.getShooter() == scene.body) {
                changed.incrementAndGet();
                operator.getCacheProperty().setCache(GunProperties.EXPLOSION, new ExplosionData(true, 8, 100, true, 0, true));
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, listener);
        try {
            helper.assertTrue(scene.session.fire(scene.target), "an ordinary shot is initially permitted");
            scene.session.tick();
            helper.assertValueEqual(changed.get(), 1, "late listener changes the bullet's final payload");
            helper.assertValueEqual(scene.total("45acp"), 5, "native discharge really attempted to spawn its round");
            helper.assertValueEqual(scene.shots.get(), 0, "the explosive entity never enters the world");
            helper.assertValueEqual(scene.target.getHealth(), 1000F, "no direct or explosion damage reaches the target");
        } finally { NeoForge.EVENT_BUS.unregister(listener); }
        helper.succeed();
    }

    static void attach(ItemStack gun, String attachment, GameTestHelper helper) {
        ItemStack actual = new ItemStack(ModItems.ATTACHMENT.get());
        ((IAttachment) actual.getItem()).setAttachmentId(actual, TaczWeaponGameTests.id(attachment));
        IGun operator = (IGun) gun.getItem();
        helper.assertTrue(operator.allowAttachment(gun, actual), "the pinned attachment is valid on the ordinary gun");
        operator.installAttachment(helper.getLevel().registryAccess(), gun, actual);
    }
}
