package io.github.izakyl.folkways.firearms.warfare;

import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.person.ResidentWeaponPoses;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

final class WarfareClient {

    private WarfareClient() {
    }

    static void install() {
        ModelPart root = LayerDefinition.create(
            HumanoidModel.createMesh(CubeDeformation.NONE, 0), 64, 64).bakeRoot();
        HumanoidModel<ResidentEntity> model = new HumanoidModel<>(root);
        ResidentWeaponPoses.register(body -> {
            ItemStack stack = body.getMainHandItem();
            if (WarfareWeapons.ALL.stream().noneMatch(weapon -> weapon.accepts(stack))) {
                return Optional.empty();
            }
            HumanoidModel.ArmPose pose = IClientItemExtensions.of(stack)
                .getArmPose(body, InteractionHand.MAIN_HAND, stack);
            if (pose == null) {
                return Optional.empty();
            }
            // SBW exposes its player pose through the item extension. Evaluate it with the
            // native model so two-handed and custom poses keep their own animation logic.
            root.getAllParts().forEach(ModelPart::resetPose);
            HumanoidModel.ArmPose offhand = body.getOffhandItem().isEmpty()
                ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
            model.rightArmPose = body.getMainArm() == HumanoidArm.RIGHT ? pose : offhand;
            model.leftArmPose = body.getMainArm() == HumanoidArm.LEFT ? pose : offhand;
            float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
            float stride = body.walkAnimation.position(partialTick);
            float amount = body.walkAnimation.speed(partialTick);
            float yaw = Mth.rotLerp(partialTick, body.yHeadRotO, body.yHeadRot)
                - Mth.rotLerp(partialTick, body.yBodyRotO, body.yBodyRot);
            float pitch = Mth.lerp(partialTick, body.xRotO, body.getXRot());
            model.attackTime = body.getAttackAnim(partialTick);
            model.riding = body.isPassenger() && body.getVehicle().shouldRiderSit();
            model.crouching = body.isCrouching();
            model.prepareMobModel(body, stride, amount, partialTick);
            model.setupAnim(body, stride, amount, body.tickCount + partialTick, yaw, pitch);
            return Optional.of(new ResidentWeaponPoses.Pose(arm(model.rightArm), arm(model.leftArm)));
        });
    }

    private static ResidentWeaponPoses.Arm arm(ModelPart arm) {
        return new ResidentWeaponPoses.Arm(arm.xRot, arm.yRot, arm.zRot);
    }
}
