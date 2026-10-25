package io.github.izakyl.folkways.plugins.person.look;

import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Items;

// GeckoLib's actor is the shared Crew, not the resident. Use private variables rather than replacing its
// global query bindings (which other mods also use), and refresh them for each resident before animation.
public enum ResidentMolang {
    HEALTH(20, "query.health"),
    MAX_HEALTH(20, "query.max_health"),
    FOOD_LEVEL(20, "ysm.food_level"),
    HURT_TIME(0, "query.hurt_time", "ysm.hurt_time"),
    SLEEPING(0, "query.is_sleeping", "ysm.is_sleep"),
    CLOSE_EYES(0, "ysm.is_close_eyes"),
    // TLM's QueryBinding calls netHeadYaw head_x_rotation and headPitch head_y_rotation.
    HEAD_PITCH(0, "ysm.head_pitch", "query.head_y_rotation"),
    HEAD_YAW(0, "ysm.head_yaw", "query.head_x_rotation"),
    BODY_YAW(0, "query.body_y_rotation"),
    GROUND_SPEED(0, "query.ground_speed"),
    VERTICAL_SPEED(0, "query.vertical_speed"),
    YAW_SPEED(0, "query.yaw_speed"),
    JUMPING(0, "query.is_jumping"),
    SNEAKING(0, "query.is_sneaking"),
    IN_WATER(0, "query.is_in_water"),
    IN_WATER_OR_RAIN(0, "query.is_in_water_or_rain"),
    INPUT_VERTICAL(0, "ysm.input_vertical"),
    INPUT_HORIZONTAL(0, "ysm.input_horizontal"),
    HAS_HELMET(0, "ysm.has_helmet"),
    HAS_MAINHAND(0, "ysm.has_mainhand"),
    HAS_OFFHAND(0, "ysm.has_offhand"),
    HAS_ELYTRA(0, "ysm.has_elytra"),
    MAINHAND_BOW(0, "query.is_item_name_any('mainhand', 'bow')"),
    POSITION_X(0, "query.position(0)"),
    POSITION_Y(0, "query.position(1)"),
    POSITION_Z(0, "query.position(2)"),
    POSITION_DELTA_X(0, "query.position_delta(0)"),
    POSITION_DELTA_Y(0, "query.position_delta(1)"),
    POSITION_DELTA_Z(0, "query.position_delta(2)"),
    SITTING(0, "tlm.is_sitting"),
    // Residents have an internal inventory, but wear no TLM backpack and never hide for a first-person mod.
    HAS_BACKPACK(0, "tlm.has_backpack"),
    FIRST_PERSON_MOD_HIDE(0, "ysm.first_person_mod_hide");

    private static final Map<String, BoneRotation> BONE_ROTATIONS = new ConcurrentHashMap<>();

    public record BoneRotation(String bone, String axis) { }

    static String boneRotation(String bone, String axis) {
        String variable = "variable.folkways_bone_rot_"
            + HexFormat.of().formatHex(bone.getBytes(StandardCharsets.UTF_8)) + "_" + axis;
        BONE_ROTATIONS.putIfAbsent(variable, new BoneRotation(bone, axis));
        return variable;
    }

    public static Map<String, BoneRotation> boneRotations() {
        return Map.copyOf(BONE_ROTATIONS);
    }

    private final double resting;
    private final List<String> names;

    ResidentMolang(double resting, String... names) {
        this.resting = resting;
        this.names = List.of(names);
    }

    static Optional<ResidentMolang> named(String name) {
        String canonical = name.startsWith("q.") ? "query." + name.substring(2) : name;
        return Arrays.stream(values()).filter(query -> query.names.contains(canonical)).findFirst();
    }

    double resting() {
        return resting;
    }

    public String variable() {
        return "variable.folkways_" + name().toLowerCase(java.util.Locale.ROOT);
    }

    public double value(LivingEntity resident, float headPitch, float headYaw, double animationTick) {
        return value(resident, headPitch, headYaw, animationTick, 1);
    }

    public double value(LivingEntity resident, float headPitch, float headYaw, double animationTick, float partialTick) {
        return switch (this) {
            case HEALTH -> resident.getHealth();
            case MAX_HEALTH -> resident.getMaxHealth();
            case FOOD_LEVEL -> resident instanceof ResidentEntity person ? person.foodLevel() : resting;
            case HURT_TIME -> resident.hurtTime;
            case SLEEPING -> resident.isSleeping() ? 1 : 0;
            case CLOSE_EYES -> {
                // YSMBinding.getEyeCloseState in TLM: the blink belongs to this resident, including its phase.
                double phase = (animationTick + Math.abs(resident.getUUID().getLeastSignificantBits()) % 10L) % 90;
                yield resident.isSleeping() || (85 < phase && phase < 90) ? 1 : 0;
            }
            case HEAD_PITCH -> headPitch;
            case HEAD_YAW -> headYaw;
            case BODY_YAW -> Mth.wrapDegrees(Mth.lerp(partialTick, resident.yBodyRotO, resident.yBodyRot));
            case GROUND_SPEED -> 20 * resident.getDeltaMovement().horizontalDistance();
            case VERTICAL_SPEED -> 20 * (resident.getY() - resident.yo);
            case YAW_SPEED -> 20 * (resident.getYRot() - resident.yRotO);
            case JUMPING -> !resident.isPassenger() && !resident.onGround() && !resident.isInWater() ? 1 : 0;
            case SNEAKING -> resident.onGround() && resident.getPose() == Pose.CROUCHING ? 1 : 0;
            case IN_WATER -> resident.isInWater() ? 1 : 0;
            case IN_WATER_OR_RAIN -> resident.isInWaterRainOrBubble() ? 1 : 0;
            case INPUT_VERTICAL -> input(resident, partialTick, false);
            case INPUT_HORIZONTAL -> input(resident, partialTick, true);
            case HAS_HELMET -> resident.getItemBySlot(EquipmentSlot.HEAD).isEmpty() ? 0 : 1;
            case HAS_MAINHAND -> resident.getMainHandItem().isEmpty() ? 0 : 1;
            case HAS_OFFHAND -> resident.getOffhandItem().isEmpty() ? 0 : 1;
            case HAS_ELYTRA -> resident.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) ? 1 : 0;
            case MAINHAND_BOW -> resident.getMainHandItem().is(Items.BOW) ? 1 : 0;
            case POSITION_X -> Mth.lerp(partialTick, resident.xo, resident.getX());
            case POSITION_Y -> Mth.lerp(partialTick, resident.yo, resident.getY());
            case POSITION_Z -> Mth.lerp(partialTick, resident.zo, resident.getZ());
            case POSITION_DELTA_X -> partialTick * (resident.getX() - resident.xo);
            case POSITION_DELTA_Y -> partialTick * (resident.getY() - resident.yo);
            case POSITION_DELTA_Z -> partialTick * (resident.getZ() - resident.zo);
            case SITTING -> resident.isPassenger() && resident.getVehicle() != null
                && resident.getVehicle().shouldRiderSit() ? 1 : 0;
            case HAS_BACKPACK, FIRST_PERSON_MOD_HIDE -> resting;
        };
    }

    private static double input(LivingEntity resident, float partialTick, boolean horizontal) {
        double x = partialTick * (resident.getX() - resident.xo);
        double z = partialTick * (resident.getZ() - resident.zo);
        if (Math.sqrt(x * x + z * z) < 1.0E-4) {
            return 0;
        }
        double relative = Mth.wrapDegrees(Math.toDegrees(Mth.atan2(z, x))
            - (90 - Mth.wrapDegrees(-resident.getViewYRot(partialTick))));
        return horizontal ? Math.sin(Math.toRadians(relative)) : Math.cos(Math.toRadians(relative));
    }
}
