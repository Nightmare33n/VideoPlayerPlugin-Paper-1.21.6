package com.receptor;

import com.receptor.network.PlayVideoPayload;
import com.receptor.network.SetVolumePayload;
import com.receptor.network.StopVideoPayload;
import com.receptor.video.VideoHudOverlay;
import com.receptor.video.VideoPlaybackManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class ReceptorClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		VideoHudOverlay.register();
		PayloadTypeRegistry.playS2C().register(PlayVideoPayload.TYPE, PlayVideoPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(StopVideoPayload.TYPE, StopVideoPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(SetVolumePayload.TYPE, SetVolumePayload.CODEC);

		ClientPlayNetworking.registerGlobalReceiver(PlayVideoPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> VideoPlaybackManager.getInstance().play(payload.id(), payload.source()));
		});

		ClientPlayNetworking.registerGlobalReceiver(StopVideoPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> VideoPlaybackManager.getInstance().stop());
		});

		ClientPlayNetworking.registerGlobalReceiver(SetVolumePayload.TYPE, (payload, context) -> {
			context.client().execute(() -> VideoPlaybackManager.getInstance().setVolume(payload.volume()));
		});

		Receptor.LOGGER.info("Receptor client initialized: listening on {} {} {}",
				VideoChannels.PLAY_VIDEO,
				VideoChannels.STOP_VIDEO,
				VideoChannels.SET_VOLUME);
	}
}