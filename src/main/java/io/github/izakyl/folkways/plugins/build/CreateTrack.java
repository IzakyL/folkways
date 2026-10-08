package io.github.izakyl.folkways.plugins.build;

import com.simibubi.create.content.trains.track.TrackBlock;

final class CreateTrack {

    private CreateTrack() {
    }

    static void install() {
        Fidelity.register((block, property) -> block instanceof TrackBlock && property == TrackBlock.SHAPE);
        Joints.install(new CreateJoiner());
    }
}
