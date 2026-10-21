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
        String leisure = model.leisureAnimations().get(resident.leisureMotion());
        if (leisure != null) { return leisure; }
        return seated(resident) ? model.seatedAnimation().orElse(model.idleAnimation())
            : moving ? model.walkAnimation() : model.idleAnimation();
    }
}
