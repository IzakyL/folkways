package io.github.izakyl.folkways.tacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysTaczMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class TaczReloadGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(TaczReloadGameTests.class); }

    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void m870SingleShellRemainsOneRoundAfterReloadTimeJumps(GameTestHelper helper) { laggedReload(helper, "m870", 1); }
    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void m870TwoShellsRemainTwoRoundsAfterReloadTimeJumps(GameTestHelper helper) { laggedReload(helper, "m870", 2); }
    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void m1014SingleShellRemainsOneRoundAfterReloadTimeJumps(GameTestHelper helper) { laggedReload(helper, "m1014", 1); }
    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void spas12SingleShellRemainsOneRoundAfterReloadTimeJumps(GameTestHelper helper) { laggedReload(helper, "spas_12", 1); }
    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void kar98SingleRoundRemainsOneRoundAfterReloadTimeJumps(GameTestHelper helper) { laggedReload(helper, "kar98", 1); }

    private static void laggedReload(GameTestHelper helper, String gun, int rounds) {
        var scene = new TaczWeaponGameTests.Fixture(helper, gun, FireMode.SEMI, 0);
        String ammo = TimelessAPI.getCommonGunIndex(TaczWeaponGameTests.id(gun)).orElseThrow().getGunData().getAmmoId().getPath();
        scene.body.pack().insert(TaczWeaponGameTests.ammo(ammo, rounds));
        scene.open();
        var data = IGunOperator.fromLivingEntity(scene.body).getDataHolder();
        data.drawTimestamp = System.currentTimeMillis() - 10_000;
        data.baseTimestamp = System.currentTimeMillis() - 60_000;
        helper.assertTrue(scene.session.reload(), "native reload starts with finite physical stock");
        AbstractGunItem actual = (AbstractGunItem) scene.actual().getItem();
        // Invoke the pinned gun's original Lua with a sparse clock; no game ticks run between these samples.
        for (int elapsed : new int[] {1300, 3100, 3100, 15_000}) {
            data.reloadTimestamp = System.currentTimeMillis() - elapsed;
            actual.tickReload(data, scene.actual(), scene.body);
            helper.assertValueEqual(scene.total(ammo), rounds, "loose, magazine and chamber conserve stock at " + elapsed + " ms");
        }
        if (gun.equals("m870") && rounds == 1) {
            helper.assertTrue(((IGun) scene.actual().getItem()).hasBulletInBarrel(scene.actual()), "the actual one shell reaches the chamber");
            helper.assertValueEqual(((IGun) scene.actual().getItem()).getCurrentAmmoCount(scene.actual()), 0, "lag creates no magazine shells");
        }
        scene.session.close();
        helper.assertValueEqual(scene.total(ammo), rounds, "cancelling reload cannot duplicate the credited rounds");
        helper.assertValueEqual(scene.shots.get(), 0, "reload never discharges a round");
        helper.succeed();
    }

}
