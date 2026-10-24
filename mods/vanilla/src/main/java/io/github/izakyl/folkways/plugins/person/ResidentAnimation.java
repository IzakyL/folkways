package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.plugins.person.look.ResidentLook;

// Passenger movement comes from the vehicle. Only vehicles that actually seat their riders
// should bend the resident's legs; standing passengers keep their ordinary pose.
final class ResidentAnimation {
    private ResidentAnimation() {
    }

    static boolean seated(ResidentEntity resident) {
        return resident.isPassenger() && resident.getVehicle() != null && resident.getVehicle().shouldRiderSit();
    }

    static String loop(ResidentEntity resident, ResidentLook.Model model, boolean moving) {
        String motion = resident.leisureMotion();
        String leisure = seated(resident)
            ? model.leisureAnimations().getOrDefault(motion + "_seated", model.leisureAnimations().get(motion))
            : model.leisureAnimations().get(motion);
        if (leisure != null) { return leisure; }
        // Remote entities may move by interpolation while their local velocity is zero. Vanilla
        // still updates the stride from displacement, so do not let GeckoLib keep them in idle.
        return seated(resident) ? model.seatedAnimation().orElse(model.idleAnimation())
            : (moving || resident.walkAnimation.speed() > 0.01F) ? model.walkAnimation() : model.idleAnimation();
    }
}
