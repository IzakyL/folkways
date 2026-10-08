package io.github.izakyl.folkways.front.client.ponder;

import java.util.List;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class LessonScreen extends PonderUI {

    LessonScreen(List<PonderScene> scenes) {
        super(scenes);
    }
}
