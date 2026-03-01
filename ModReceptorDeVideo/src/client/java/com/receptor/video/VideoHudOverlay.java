package com.receptor.video;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class VideoHudOverlay {

    private VideoHudOverlay() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register(VideoHudOverlay::render);
    }

    private static void render(GuiGraphics drawContext, DeltaTracker tickCounter) {
        VideoPlaybackManager manager = VideoPlaybackManager.getInstance();

        if (manager.isRunning()) {
            manager.render(drawContext);
        }

        // Status text only while loading (not during playback)
        String status = manager.getStatusText();
        if (!status.isEmpty() && status.startsWith("Loading")) {
            drawContext.fill(4, 4, 350, 18, 0xAA000000);
            drawContext.drawString(Minecraft.getInstance().font, Component.literal("[Receptor] " + status), 8, 8, 0x00FF00, true);
        }
    }
}
