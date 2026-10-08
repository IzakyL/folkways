package io.github.izakyl.folkways.front.api.ui;

import net.minecraft.network.chat.Component;

public interface Pane {

    void open();

    void close();

    boolean isOpen();

    void title(Component text);

    void onDismiss(Runnable both);
}
