package io.github.izakyl.folkways.core.card.client;

import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import io.github.izakyl.folkways.core.card.api.Card;
import java.util.function.Predicate;
import org.lwjgl.glfw.GLFW;

final class NumberInput {
    private final TextField field = new TextField();
    private final Card.Element.Number number;
    private Predicate<String> commit;
    private String initial;
    private String latest;
    private boolean pending;

    NumberInput(Card.Element.Number number, String amount, Predicate<String> commit) {
        this.number = number;
        this.commit = commit;
        initial = latest = amount;
        field.addEventListener(UIEvents.KEY_DOWN, event -> {
            if (event.keyCode == GLFW.GLFW_KEY_ENTER || event.keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                submit();
            }
        });
        field.addEventListener(UIEvents.BLUR, event -> {
            submit();
            if (!pending) {
                field.setText(latest, false);
                initial = latest;
            }
        });
    }

    TextField field() {
        return field;
    }

    Card.Element.Number number() {
        return number;
    }

    void refreshFrom(NumberInput fresh) {
        commit = fresh.commit;
        latest = fresh.latest;
        pending = false;
    }

    private void submit() {
        String input = field.getText();
        if (!input.equals(initial) && commit.test(input)) {
            initial = input;
            pending = true;
        }
    }
}
