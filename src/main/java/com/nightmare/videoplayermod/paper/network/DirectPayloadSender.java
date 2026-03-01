package com.nightmare.videoplayermod.paper.network;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends plugin-channel messages to players, bypassing the Bukkit channel-registration
 * check that silently drops messages when the client hasn't sent a {@code minecraft:register}
 * packet for the channel.
 * <p>
 * Internally, this temporarily adds the channel to the player's registered-channel set,
 * invokes the normal {@link Player#sendPluginMessage} (which builds the correct NMS packet),
 * and then removes the channel again so no side-effects remain.
 */
public final class DirectPayloadSender {

    private static Field channelsField;
    private static boolean reflectionAvailable;

    static {
        try {
            // CraftPlayer stores client-registered channels in a private Set<String> field called "channels"
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            channelsField = craftPlayerClass.getDeclaredField("channels");
            channelsField.setAccessible(true);
            reflectionAvailable = true;
        } catch (Exception e) {
            reflectionAvailable = false;
        }
    }

    private DirectPayloadSender() {}

    /**
     * Sends a plugin message to the player. If the player already has the channel registered,
     * uses the normal API. Otherwise, force-injects the channel temporarily so the message
     * is not silently discarded.
     *
     * @return true if the message was sent, false on failure
     */
    public static boolean send(Plugin plugin, Player player, String channel, byte[] data, Logger logger) {
        // Fast path: client already registered the channel
        if (player.getListeningPluginChannels().contains(channel)) {
            player.sendPluginMessage(plugin, channel, data);
            return true;
        }

        // Slow path: client hasn't registered the channel — force-inject it
        if (!reflectionAvailable) {
            logger.warning("Cannot send payload on channel '" + channel
                    + "' — client has not registered it and reflection fallback is unavailable.");
            return false;
        }

        try {
            @SuppressWarnings("unchecked")
            Set<String> channels = (Set<String>) channelsField.get(player);

            channels.add(channel);
            try {
                player.sendPluginMessage(plugin, channel, data);
            } finally {
                channels.remove(channel);
            }
            return true;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to force-send plugin message on channel '" + channel + "'", e);
            return false;
        }
    }
}
