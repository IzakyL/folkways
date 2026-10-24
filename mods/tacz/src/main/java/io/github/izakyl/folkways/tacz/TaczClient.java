package io.github.izakyl.folkways.tacz;

import com.tacz.guns.api.item.IGun;
import io.github.izakyl.folkways.plugins.person.ResidentWeaponPoses;
import java.util.Optional;
import net.minecraft.util.Mth;

final class TaczClient {
    private TaczClient() { }
    static void install() {
        ResidentWeaponPoses.register(body -> {
            if (!(body.getMainHandItem().getItem() instanceof IGun)) { return Optional.empty(); }
            float pitch = body.getXRot() * Mth.DEG_TO_RAD - Mth.HALF_PI;
            return Optional.of(new ResidentWeaponPoses.Pose(new ResidentWeaponPoses.Arm(pitch, -0.1F, 0),
                new ResidentWeaponPoses.Arm(pitch - 0.1F, 0.5F, 0)));
        });
    }
}
