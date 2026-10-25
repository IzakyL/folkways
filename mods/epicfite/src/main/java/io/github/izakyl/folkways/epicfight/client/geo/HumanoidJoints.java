/*
 * Derived from YSM GEO Compat by HSZK2017 (MIT), commit
 * 478032816561111206f883ef783bb011db7bfb7a.
 * See META-INF/licenses/YSM-GEO-Compat-MIT.txt in this mod.
 */
package io.github.izakyl.folkways.epicfight.client.geo;

import java.util.HashMap;
import java.util.Map;

/**
 * The Epic Fight reference biped skeleton: the single source of truth for the 20
 * joint names and their ids.
 *
 * The ids follow the order of the "joints" array in Epic Fight's
 * {@code assets/epicfight/animmodels/entity/biped.json}:
 * Root, Thigh_R, Leg_R, Knee_R, Thigh_L, Leg_L, Knee_L, Torso, Chest, Head,
 * Shoulder_R, Arm_R, Hand_R, Tool_R, Elbow_R, Shoulder_L, Arm_L, Hand_L, Tool_L,
 * Elbow_L.
 *
 * This table used to exist twice - as a name→id map in {@link GeoJointMapper} and
 * as 20 named int constants in the bind armature - so a renumbered Epic Fight
 * biped would have silently bound geometry to the wrong joints in whichever copy
 * was missed.
 */
public final class HumanoidJoints {

    /** Number of joints in the reference biped armature. */
    public static final int COUNT = 20;

    public static final String ROOT = "Root";
    public static final String THIGH_R = "Thigh_R";
    public static final String LEG_R = "Leg_R";
    public static final String KNEE_R = "Knee_R";
    public static final String THIGH_L = "Thigh_L";
    public static final String LEG_L = "Leg_L";
    public static final String KNEE_L = "Knee_L";
    public static final String TORSO = "Torso";
    public static final String CHEST = "Chest";
    public static final String HEAD = "Head";
    public static final String SHOULDER_R = "Shoulder_R";
    public static final String ARM_R = "Arm_R";
    public static final String HAND_R = "Hand_R";
    public static final String TOOL_R = "Tool_R";
    public static final String ELBOW_R = "Elbow_R";
    public static final String SHOULDER_L = "Shoulder_L";
    public static final String ARM_L = "Arm_L";
    public static final String HAND_L = "Hand_L";
    public static final String TOOL_L = "Tool_L";
    public static final String ELBOW_L = "Elbow_L";

    /** Joint names indexed by joint id. */
    public static final String[] NAMES = {
            ROOT, THIGH_R, LEG_R, KNEE_R, THIGH_L, LEG_L, KNEE_L, TORSO, CHEST, HEAD,
            SHOULDER_R, ARM_R, HAND_R, TOOL_R, ELBOW_R,
            SHOULDER_L, ARM_L, HAND_L, TOOL_L, ELBOW_L,
    };

    /** Parent joint of each entry in {@link #NAMES} (the reference biped hierarchy), -1 for the root. */
    public static final int[] PARENTS = {
            -1,          // Root
            0,           // Thigh_R     -> Root
            1,           // Leg_R       -> Thigh_R
            2,           // Knee_R      -> Leg_R
            0,           // Thigh_L     -> Root
            4,           // Leg_L       -> Thigh_L
            5,           // Knee_L      -> Leg_L
            0,           // Torso       -> Root
            7,           // Chest       -> Torso
            8,           // Head        -> Chest
            8,           // Shoulder_R  -> Chest
            10,          // Arm_R       -> Shoulder_R
            11,          // Hand_R      -> Arm_R
            12,          // Tool_R      -> Hand_R
            11,          // Elbow_R     -> Arm_R
            8,           // Shoulder_L  -> Chest
            15,          // Arm_L       -> Shoulder_L
            16,          // Hand_L      -> Arm_L
            17,          // Tool_L      -> Hand_L
            16,          // Elbow_L     -> Arm_L
    };

    /** Index of each joint name in {@link #NAMES}. */
    public static final int ID_ROOT = 0;
    public static final int ID_THIGH_R = 1;
    public static final int ID_LEG_R = 2;
    public static final int ID_KNEE_R = 3;
    public static final int ID_THIGH_L = 4;
    public static final int ID_LEG_L = 5;
    public static final int ID_KNEE_L = 6;
    public static final int ID_TORSO = 7;
    public static final int ID_CHEST = 8;
    public static final int ID_HEAD = 9;
    public static final int ID_SHOULDER_R = 10;
    public static final int ID_ARM_R = 11;
    public static final int ID_HAND_R = 12;
    public static final int ID_TOOL_R = 13;
    public static final int ID_ELBOW_R = 14;
    public static final int ID_SHOULDER_L = 15;
    public static final int ID_ARM_L = 16;
    public static final int ID_HAND_L = 17;
    public static final int ID_TOOL_L = 18;
    public static final int ID_ELBOW_L = 19;

    private static final Map<String, Integer> BY_NAME = createIndex();

    private HumanoidJoints() {}

    private static Map<String, Integer> createIndex() {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < NAMES.length; i++) {
            map.put(NAMES[i], i);
        }
        return map;
    }

    /** The joint id of an Epic Fight joint name, or -1 when the name is unknown. */
    public static int idOf(String jointName) {
        Integer id = BY_NAME.get(jointName);
        return id != null ? id : -1;
    }

    /** The joint name of an id ("Joint<n>" for an out-of-range id). */
    public static String nameOf(int joint) {
        return joint >= 0 && joint < NAMES.length ? NAMES[joint] : "Joint" + joint;
    }
}
