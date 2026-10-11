package dev.packshelf;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class PackShelfMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof PackScreen) {
                screen.addDrawableChild(ButtonWidget.builder(
                                Text.literal("Download Packs"),
                                button -> client.setScreen(new PackBrowserScreen(screen)))
                        .dimensions(8, scaledHeight - 28, 132, 20)
                        .build());
            }
        });
    }
}
