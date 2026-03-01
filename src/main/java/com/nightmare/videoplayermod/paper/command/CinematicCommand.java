package com.nightmare.videoplayermod.paper.command;

import com.nightmare.videoplayermod.paper.VideoPlayerPlugin;
import com.nightmare.videoplayermod.paper.config.VideoRegistry;
import com.nightmare.videoplayermod.paper.network.DirectPayloadSender;
import com.nightmare.videoplayermod.paper.network.PayloadWriter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CinematicCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_SUBCOMMANDS = List.of("play", "stop", "list", "volume", "reload");
    private static final List<String> TARGET_SUGGESTIONS = List.of("@a", "@p", "@s");

    private final VideoPlayerPlugin plugin;
    private final VideoRegistry videoRegistry;

    public CinematicCommand(VideoPlayerPlugin plugin, VideoRegistry videoRegistry) {
        this.plugin = plugin;
        this.videoRegistry = videoRegistry;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /cinematic <play|stop|list|volume|reload>", NamedTextColor.RED));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "play" -> handlePlay(sender, args);
            case "stop" -> handleStop(sender, args);
            case "list" -> handleList(sender);
            case "volume" -> handleVolume(sender, args);
            case "reload" -> handleReload(sender);
            default -> {
                sender.sendMessage(Component.text("Unknown subcommand. Use: /cinematic <play|stop|list|volume|reload>", NamedTextColor.RED));
                yield true;
            }
        };
    }

    private boolean handlePlay(CommandSender sender, String[] args) {
        if (!sender.hasPermission("videoplayermod.play")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /cinematic play <id> [targets]", NamedTextColor.RED));
            return true;
        }

        String id = args[1];
        Map<String, String> videos = videoRegistry.loadVideos();

        if (!videos.containsKey(id)) {
            sender.sendMessage(Component.text("Unknown video ID: " + id, NamedTextColor.RED));
            return true;
        }

        String source = videos.get(id);
        if (source == null || source.isBlank()) {
            sender.sendMessage(Component.text("Video source is empty for ID: " + id, NamedTextColor.RED));
            return true;
        }

        String targetInput = args.length >= 3 ? args[2] : "@a";
        Collection<Player> targets = resolveTargets(sender, targetInput);

        if (targets.isEmpty()) {
            sender.sendMessage(Component.text("No target players matched.", NamedTextColor.RED));
            return true;
        }

        byte[] payload = new PayloadWriter()
                .writeUtf(id)
                .writeUtf(source)
                .toByteArray();

        int sent = 0;
        for (Player player : targets) {
            if (DirectPayloadSender.send(plugin, player, VideoPlayerPlugin.CHANNEL_PLAY_VIDEO, payload, plugin.getLogger())) {
                sent++;
            }
        }

        plugin.getLogger().info("Playing '" + id + "' — sent to " + sent + "/" + targets.size() + " player(s)");

        if ("@a".equals(targetInput)) {
            sender.sendMessage(Component.text("Playing '" + id + "' for all players", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Playing '" + id + "' for " + targets.size() + " player(s)", NamedTextColor.GREEN));
        }

        return true;
    }

    private boolean handleStop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("videoplayermod.stop")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }

        String targetInput = args.length >= 2 ? args[1] : "@a";
        Collection<Player> targets = resolveTargets(sender, targetInput);

        if (targets.isEmpty()) {
            sender.sendMessage(Component.text("No target players matched.", NamedTextColor.RED));
            return true;
        }

        byte[] payload = new byte[0];
        for (Player player : targets) {
            DirectPayloadSender.send(plugin, player, VideoPlayerPlugin.CHANNEL_STOP_VIDEO, payload, plugin.getLogger());
        }

        if ("@a".equals(targetInput)) {
            sender.sendMessage(Component.text("Stopped video for all players", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Stopped video for " + targets.size() + " player(s)", NamedTextColor.GREEN));
        }

        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!sender.hasPermission("videoplayermod.list")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }

        Map<String, String> videos = videoRegistry.loadVideos();
        if (videos.isEmpty()) {
            sender.sendMessage(Component.text(
                    "No videos registered. Add files to config/videoplayermod/videos/ or URLs to urls.properties",
                    NamedTextColor.YELLOW));
            return true;
        }

        sender.sendMessage(Component.text("Registered videos:", NamedTextColor.GOLD));
        for (Map.Entry<String, String> entry : videos.entrySet()) {
            sender.sendMessage(Component.text("  " + entry.getKey() + " = " + entry.getValue(), NamedTextColor.GRAY));
        }
        return true;
    }

    private boolean handleVolume(CommandSender sender, String[] args) {
        if (!sender.hasPermission("videoplayermod.volume")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /cinematic volume <0-100> [targets]", NamedTextColor.RED));
            return true;
        }

        int level;
        try {
            level = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("Volume must be a number between 0 and 100.", NamedTextColor.RED));
            return true;
        }

        if (level < 0 || level > 100) {
            sender.sendMessage(Component.text("Volume must be between 0 and 100.", NamedTextColor.RED));
            return true;
        }

        String targetInput = args.length >= 3 ? args[2] : "@a";
        Collection<Player> targets = resolveTargets(sender, targetInput);

        if (targets.isEmpty()) {
            sender.sendMessage(Component.text("No target players matched.", NamedTextColor.RED));
            return true;
        }

        float volume = level / 100.0f;
        byte[] payload = new PayloadWriter().writeFloat(volume).toByteArray();

        for (Player player : targets) {
            DirectPayloadSender.send(plugin, player, VideoPlayerPlugin.CHANNEL_SET_VOLUME, payload, plugin.getLogger());
        }

        sender.sendMessage(Component.text("Video volume set to " + level + "%", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("videoplayermod.command")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        videoRegistry.invalidateCache();
        int count = videoRegistry.loadVideos().size();
        sender.sendMessage(Component.text("Video registry reloaded: " + count + " entries", NamedTextColor.GREEN));
        return true;
    }

    private Collection<Player> resolveTargets(CommandSender sender, String targetInput) {
        String normalized = targetInput.toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "@a" -> new ArrayList<>(Bukkit.getOnlinePlayers());
            case "@s" -> {
                if (sender instanceof Player player) {
                    yield List.of(player);
                }
                yield Collections.emptyList();
            }
            case "@p" -> resolveNearestPlayer(sender);
            default -> {
                Player player = Bukkit.getPlayerExact(targetInput);
                if (player == null) {
                    yield Collections.emptyList();
                }
                yield List.of(player);
            }
        };
    }

    private Collection<Player> resolveNearestPlayer(CommandSender sender) {
        if (sender instanceof Player senderPlayer) {
            Location origin = senderPlayer.getLocation();
            return Bukkit.getOnlinePlayers().stream()
                    .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(origin)))
                    .map(List::of)
                    .orElseGet(Collections::emptyList);
        }

        return Bukkit.getOnlinePlayers().stream().findFirst().<Collection<Player>>map(List::of).orElseGet(Collections::emptyList);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(ROOT_SUBCOMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if ("play".equals(sub)) {
            if (args.length == 2) {
                return filter(new ArrayList<>(videoRegistry.loadVideos().keySet()), args[1]);
            }
            if (args.length == 3) {
                return filter(buildTargetSuggestions(), args[2]);
            }
        }

        if ("stop".equals(sub) && args.length == 2) {
            return filter(buildTargetSuggestions(), args[1]);
        }

        if ("volume".equals(sub)) {
            if (args.length == 2) {
                return filter(List.of("0", "25", "50", "75", "100"), args[1]);
            }
            if (args.length == 3) {
                return filter(buildTargetSuggestions(), args[2]);
            }
        }

        return Collections.emptyList();
    }

    private List<String> buildTargetSuggestions() {
        Set<String> suggestions = new LinkedHashSet<>(TARGET_SUGGESTIONS);
        for (Player online : Bukkit.getOnlinePlayers()) {
            suggestions.add(online.getName());
        }
        return new ArrayList<>(suggestions);
    }

    private List<String> filter(List<String> values, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(value);
            }
        }
        return matches;
    }
}
