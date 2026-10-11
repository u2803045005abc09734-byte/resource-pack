package dev.packshelf;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PackBrowserScreen extends Screen {
    private static final int ROW_TOP = 68;
    private static final int ROW_HEIGHT = 38;
    private static final int MAX_RESULTS = 8;

    private final Screen parent;
    private final List<ModrinthApi.Pack> packs;
    private String searchQuery;
    private String status;
    private TextFieldWidget searchField;
    private boolean searching;

    public PackBrowserScreen(Screen parent) {
        this(parent, "", List.of(), "Search Modrinth for resource packs.");
    }

    private PackBrowserScreen(Screen parent, String query, List<ModrinthApi.Pack> packs, String status) {
        super(Text.literal("Resource Pack Browser"));
        this.parent = parent;
        this.searchQuery = query;
        this.packs = new ArrayList<>(packs);
        this.status = status;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.max(80, this.width - 126);
        searchField = new TextFieldWidget(textRenderer, 10, 35, fieldWidth, 20, Text.literal("Search"));
        searchField.setMaxLength(100);
        searchField.setText(searchQuery);
        searchField.setPlaceholder(Text.literal("Search resource packs…"));
        addDrawableChild(searchField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Search"), button -> search())
                .dimensions(16 + fieldWidth, 35, 100, 20).build());

        int rows = Math.min(MAX_RESULTS, Math.max(0, (this.height - ROW_TOP - 66) / ROW_HEIGHT));
        for (int i = 0; i < Math.min(rows, packs.size()); i++) {
            ModrinthApi.Pack pack = packs.get(i);
            int y = ROW_TOP + i * ROW_HEIGHT;
            addDrawableChild(ButtonWidget.builder(Text.literal("Download"), button -> download(pack))
                    .dimensions(this.width - 94, y + 2, 84, 20).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> client.setScreen(parent))
                .dimensions(this.width / 2 - 50, this.height - 28, 100, 20).build());
    }

    private void search() {
        if (searching) return;
        searchQuery = searchField.getText().trim();
        searching = true;
        status = "Searching Modrinth…";
        Thread worker = new Thread(() -> {
            try {
                List<ModrinthApi.Pack> found = ModrinthApi.search(searchQuery);
                MinecraftClient.getInstance().execute(() -> {
                    searching = false;
                    if (client.currentScreen == this) {
                        String message = found.isEmpty()
                                ? "No matching resource packs found for Minecraft 1.20.1."
                                : "Found " + found.size() + " resource packs. Select Download to save one.";
                        client.setScreen(new PackBrowserScreen(parent, searchQuery, found, message));
                    }
                });
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> {
                    searching = false;
                    if (client.currentScreen == this) status = "Search failed: " + safeMessage(e);
                });
            }
        }, "packshelf-search");
        worker.setDaemon(true);
        worker.start();
    }

    private void download(ModrinthApi.Pack pack) {
        status = "Downloading " + pack.title() + "…";
        Thread worker = new Thread(() -> {
            try {
                String savedName = ModrinthApi.download(pack.projectId());
                MinecraftClient.getInstance().execute(() -> {
                    if (client.currentScreen == this) {
                        status = "Downloaded " + savedName + ". Return to the list and enable it.";
                    }
                });
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> {
                    if (client.currentScreen == this) status = "Download failed: " + safeMessage(e);
                });
            }
        }, "packshelf-download");
        worker.setDaemon(true);
        worker.start();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() > 90 ? message.substring(0, 87) + "…" : message;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "Resource packs from Modrinth · Minecraft 1.20.1", 10, 23, 0xAAAAAA);

        if (packs.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Search to browse available resource packs."), width / 2, ROW_TOP + 20, 0xBBBBBB);
        }

        int rows = Math.min(MAX_RESULTS, Math.max(0, (this.height - ROW_TOP - 66) / ROW_HEIGHT));
        for (int i = 0; i < Math.min(rows, packs.size()); i++) {
            ModrinthApi.Pack pack = packs.get(i);
            int y = ROW_TOP + i * ROW_HEIGHT;
            String titleText = textRenderer.trimToWidth(pack.title(), Math.max(50, width - 125));
            context.drawTextWithShadow(textRenderer, titleText, 12, y + 1, 0xFFFFFF);
            String metadata = pack.author() + "  ·  " + NumberFormat.getIntegerInstance(Locale.US).format(pack.downloads()) + " downloads";
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(metadata, Math.max(50, width - 125)), 12, y + 15, 0xAAAAAA);
        }

        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(status, Math.max(50, width - 20)), 10, height - 48, 0xFFFF55);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            client.setScreen(parent);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER && searchField != null && searchField.isFocused()) {
            search();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
