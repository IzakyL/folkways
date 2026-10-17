package io.github.izakyl.folkways.create.build;

import io.github.izakyl.folkways.plugins.build.Fidelity;
import io.github.izakyl.folkways.plugins.build.Joints;
import com.simibubi.create.content.trains.track.TrackBlock;

final class CreateTrack {

    private CreateTrack() {
    }

    static void install() {
        Fidelity.register((block, property) -> block instanceof TrackBlock && property == TrackBlock.SHAPE);
        Joints.install(new CreateJoiner());
    }
}
