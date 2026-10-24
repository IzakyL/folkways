package io.github.izakyl.folkways.plugins.person;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Optional weapon poses shared by the skin and model renderers. Angles use the humanoid model's axes. */
public final class ResidentWeaponPoses {
    public record Arm(float pitch, float yaw, float roll) { }
    public record Pose(Arm right, Arm left) { }
    private static volatile List<Function<ResidentEntity, Optional<Pose>>> poses = List.of();
    private ResidentWeaponPoses() { }
    public static synchronized void register(Function<ResidentEntity, Optional<Pose>> pose) {
        List<Function<ResidentEntity, Optional<Pose>>> next = new ArrayList<>(poses);
        next.add(java.util.Objects.requireNonNull(pose)); poses = List.copyOf(next);
    }
    public static Optional<Pose> of(ResidentEntity body) {
        return poses.stream().map(pose -> pose.apply(body)).flatMap(Optional::stream).findFirst();
    }
}
