package com.receptor.video;

import com.receptor.Receptor;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class VideoHudOverlay {

    private VideoHudOverlay() {
    }

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Receptor.MOD_ID, "video_overlay"), VideoHudOverlay::render);
    }

    private static void render(GuiGraphics drawContext, DeltaTracker tickCounter) {
        VideoPlaybackManager manager = VideoPlaybackManager.getInstance();

        if (manager.isRunning()) {
            manager.render(drawContext);
        }

        // Only errors are shown on screen; loading/transfer/decoding progress stays hidden.
        String status = manager.getStatusText();
        if (status.startsWith("Error")) {
            var font = Minecraft.getInstance().font;
            int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();

            String display = "[Receptor] " + status;
            int panelWidth = font.width(display) + 24;
            int panelX = (screenW - panelWidth) / 2;
            int panelY = screenH / 2 - 20;

            drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + 22, 0xCC000000);
            drawContext.drawString(font, Component.literal(display),
                    (screenW - font.width(display)) / 2, panelY + 4, 0xFFFF5555, true);
        }
    }
}
