package ai.pollinations.craft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** /polli: the one command this plugin adds, and everything it can do. */
public final class PolliCommand implements CommandExecutor, TabCompleter {

    private static final int MAX_PROMPT = 400;
    private final PollinationsPlugin plugin;
    // Players in a shared room around one NPC (via /polli join).
    private final Map<String, java.util.Set<UUID>> rooms = new ConcurrentHashMap<>();

    public PolliCommand(PollinationsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "ask" -> ask(sender, args);
            case "npc" -> npc(sender, args);
            case "say" -> say(sender, args);
            case "join" -> join(sender, args);
            case "image" -> image(sender, args);
            case "models" -> models(sender, args);
            case "reload" -> reload(sender);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("PollinationsCraft:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/polli ask <prompt> — one answer from the model", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/polli say <npc> <message> — talk to an NPC", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/polli join <npc> — join that NPC's room", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/polli npc create <name> <persona> — spawn an NPC (admin)", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/polli image <prompt> — a picture on a map", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/polli models [text|image] — the live model lists", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/polli reload — re-read the config (admin)", NamedTextColor.GRAY));
    }

    // ---------------------------------------------------------------- ask

    private void ask(CommandSender sender, String[] args) {
        if (!perm(sender, "pollinations.chat")) return;
        if (!hasKey(sender)) return;
        String prompt = join(args, 1);
        if (prompt.isBlank()) {
            sender.sendMessage(Component.text("Usage: /polli ask <prompt>", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Thinking…", NamedTextColor.GRAY));
        runAsync(() -> {
            try {
                PollinationsClient.ChatReply reply = plugin.client().chat(
                        plugin.settings().textModel(),
                        plugin.settings().apiKey(),
                        "You are the assistant of a Minecraft server. Answer plainly.",
                        List.of(), truncate(prompt), 160);
                runSync(() -> {
                    sender.sendMessage(Component.text(reply.text(), NamedTextColor.WHITE));
                    cost(sender, reply.promptTokens(), reply.completionTokens());
                });
            } catch (Exception e) {
                runSync(() -> sender.sendMessage(
                        Component.text("The model gateway said: " + e.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    // ---------------------------------------------------------------- npc

    private void npc(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "create" -> createNpc(sender, args);
            case "remove" -> removeNpc(sender, args);
            case "list" -> {
                List<String> names = plugin.registry().names();
                sender.sendMessage(Component.text(
                        names.isEmpty() ? "No NPCs yet."
                                : "NPCs: " + String.join(", ", names),
                        NamedTextColor.GRAY));
            }
            default -> sender.sendMessage(Component.text(
                    "Usage: /polli npc <create|remove|list>", NamedTextColor.RED));
        }
    }

    private void createNpc(CommandSender sender, String[] args) {
        if (!perm(sender, "pollinations.admin")) return;
        if (args.length < 4) {
            sender.sendMessage(Component.text(
                    "Usage: /polli npc create <name> <persona>", NamedTextColor.RED));
            return;
        }
        String name = args[2];
        if (!NpcRegistry.NAME.matcher(name).matches()) {
            sender.sendMessage(Component.text(
                    "NPC names are letters, digits and _, up to 16 characters.",
                    NamedTextColor.RED));
            return;
        }
        if (plugin.registry().get(name) != null) {
            sender.sendMessage(Component.text("There is an NPC called " + name + ".", NamedTextColor.RED));
            return;
        }
        String persona = truncate(join(args, 3));
        Location where;
        if (sender instanceof Player player) {
            where = player.getLocation().add(player.getLocation().getDirection().setY(0).normalize().multiply(2));
        } else {
            World world = Bukkit.getWorlds().get(0);
            where = world.getSpawnLocation().add(2, 0, 0);
        }
        NpcRegistry.Npc npc = plugin.registry().add(name, persona, "", NpcRegistry.Place.of(where));
        Villager villager = plugin.spawnVillager(npc, where);
        plugin.registry().save();
        sender.sendMessage(Component.text(
                npc.name() + " the villager now stands at "
                        + where.getBlockX() + ", " + where.getBlockY() + ", " + where.getBlockZ()
                        + " (entity " + villager.getEntityId() + ")",
                NamedTextColor.GREEN));
    }

    private void removeNpc(CommandSender sender, String[] args) {
        if (!perm(sender, "pollinations.admin")) return;
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /polli npc remove <name>", NamedTextColor.RED));
            return;
        }
        NpcRegistry.Npc npc = plugin.registry().get(args[2]);
        if (npc == null) {
            sender.sendMessage(Component.text("No NPC called " + args[2] + ".", NamedTextColor.RED));
            return;
        }
        Villager villager = plugin.findNpc(npc.id());
        if (villager != null) villager.remove();
        plugin.registry().remove(npc.name());
        plugin.registry().save();
        sender.sendMessage(Component.text(npc.name() + " is gone.", NamedTextColor.GREEN));
    }

    // ---------------------------------------------------------------- say

    private void say(CommandSender sender, String[] args) {
        if (!perm(sender, "pollinations.chat")) return;
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /polli say <npc> <message>", NamedTextColor.RED));
            return;
        }
        talkToNpc(sender, args[1], join(args, 2), false);
    }

    private void join(CommandSender sender, String[] args) {
        if (!perm(sender, "pollinations.chat")) return;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can join a room.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2 || plugin.registry().get(args[1]) == null) {
            sender.sendMessage(Component.text("Usage: /polli join <npc>", NamedTextColor.RED));
            return;
        }
        String npcName = plugin.registry().get(args[1]).name();
        plugin.sessions().put(player.getUniqueId(),
                new PollinationsPlugin.Session(npcName, System.currentTimeMillis() + 600_000, true));
        rooms.computeIfAbsent(npcName, key -> ConcurrentHashMap.newKeySet()).add(player.getUniqueId());
        player.sendMessage(Component.text(
                "You joined " + npcName + "'s room. Everyone in the room sees what you say"
                        + " and hears the answer. \"bye\" leaves.", NamedTextColor.GRAY));
    }

    /**
     * One message to an NPC: the model answers with the persona and the shared
     * memory, the room sees it when the conversation is public.
     */
    void talkToNpc(CommandSender sender, String npcName, String line, boolean room) {
        NpcRegistry.Npc npc = plugin.registry().get(npcName);
        if (npc == null) {
            sender.sendMessage(Component.text("No NPC called " + npcName + ".", NamedTextColor.RED));
            return;
        }
        if (plugin.settings().apiKey().isEmpty()) {
            sender.sendMessage(Component.text(
                    "The server owner has not set an API key yet.", NamedTextColor.RED));
            return;
        }
        UUID playerId = sender instanceof Player player
                ? player.getUniqueId()
                : new UUID(0, 0);
        ConversationMemory memory = plugin.registry().memory(npc.id(), playerId);
        String playerName = sender instanceof Player player ? player.getName() : "the console";
        String system = ConversationMemory.systemPrompt(npc.persona(), npc.name(), playerName);
        List<String[]> history = memory.asMessages();
        sender.sendMessage(Component.text(npc.name() + " is thinking…", NamedTextColor.DARK_GRAY));
        runAsync(() -> {
            try {
                String model = npc.model().isBlank()
                        ? plugin.settings().textModel()
                        : npc.model();
                PollinationsClient.ChatReply reply = plugin.client().chat(
                        model,
                        plugin.settings().apiKey(),
                        system,
                        history,
                        truncate(line),
                        160);
                runSync(() -> {
                    memory.remember(line, reply.text());
                    if (room) {
                        for (UUID memberId : rooms.getOrDefault(npc.name(), java.util.Set.of())) {
                            Player member = Bukkit.getPlayer(memberId);
                            if (member != null) {
                                member.sendMessage(Component.text(npc.name() + ": ", NamedTextColor.AQUA)
                                        .append(Component.text(reply.text(), NamedTextColor.WHITE)));
                            }
                        }
                    } else {
                        sender.sendMessage(Component.text(npc.name() + ": ", NamedTextColor.AQUA)
                                .append(Component.text(reply.text(), NamedTextColor.WHITE)));
                        cost(sender, reply.promptTokens(), reply.completionTokens());
                    }
                });
            } catch (Exception e) {
                runSync(() -> sender.sendMessage(Component.text(
                        npc.name() + " could not answer: " + e.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    // -------------------------------------------------------------- image

    private void image(CommandSender sender, String[] args) {
        if (!perm(sender, "pollinations.image")) return;
        if (!hasKey(sender)) return;
        String prompt = join(args, 1);
        if (prompt.isBlank()) {
            sender.sendMessage(Component.text("Usage: /polli image <prompt>", NamedTextColor.RED));
            return;
        }
        Player player = sender instanceof Player p ? p : null;
        if (player != null && !plugin.allowImage(player)) {
            sender.sendMessage(Component.text(
                    "You used your " + plugin.settings().dailyImageLimit()
                            + " pictures for today. Try tomorrow.", NamedTextColor.RED));
            return;
        }
        int width = plugin.settings().imageWidth();
        int height = plugin.settings().imageHeight();
        sender.sendMessage(Component.text("Painting a map…", NamedTextColor.GRAY));
        runAsync(() -> {
            try {
                byte[] png = plugin.client().image(
                        plugin.settings().imageModel(),
                        plugin.settings().apiKey(),
                        truncate(prompt),
                        width,
                        height,
                        -1);
                BufferedImage picture = ImageIO.read(new ByteArrayInputStream(png));
                if (picture == null) throw new IllegalStateException("the picture could not be read");
                File export = exportCopy(png);
                runSync(() -> {
                    try {
                        World world = player != null
                                ? player.getWorld()
                                : Bukkit.getWorlds().get(0);
                        var item = plugin.gallery().paint(world, picture);
                        if (player != null) {
                            plugin.gallery().give(player, item);
                            sender.sendMessage(Component.text(
                                    "Here is your map of \u201c" + prompt + "\u201d.", NamedTextColor.GREEN));
                            int used = plugin.imagesUsed(player);
                            if (plugin.settings().dailyImageLimit() > 0) {
                                sender.sendMessage(Component.text(
                                        used + " of " + plugin.settings().dailyImageLimit()
                                                + " pictures used today.", NamedTextColor.DARK_GRAY));
                            }
                        } else {
                            sender.sendMessage(Component.text(
                                    "Painted and saved; a copy is at " + export
                                            + ", maps/" + " holds every map.", NamedTextColor.GREEN));
                        }
                        imageCost(sender);
                    } catch (IOException e) {
                        sender.sendMessage(Component.text(
                                "The map could not be painted: " + e.getMessage(), NamedTextColor.RED));
                    }
                });
            } catch (Exception e) {
                runSync(() -> sender.sendMessage(Component.text(
                        "The image endpoint said: " + e.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    /** A copy of every generated picture, so the server owner can audit them. */
    private File exportCopy(byte[] png) throws IOException {
        File folder = new File(plugin.getDataFolder(), "exports");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("cannot create " + folder);
        }
        File file = new File(folder, "picture-" + System.currentTimeMillis() + ".png");
        java.nio.file.Files.write(file.toPath(), png);
        return file;
    }

    // ------------------------------------------------------------- models

    private void models(CommandSender sender, String[] args) {
        if (!perm(sender, "pollinations.chat")) return;
        String kind = args.length > 1 && args[1].equalsIgnoreCase("image") ? "image" : "text";
        runAsync(() -> {
            List<ModelInfo> models = plugin.catalog().models(kind);
            runSync(() -> {
                if (models.isEmpty()) {
                    sender.sendMessage(Component.text(
                            "The model list at gen.pollinations.ai is not reachable right now.",
                            NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text(
                        models.size() + " " + kind + " models, e.g.:", NamedTextColor.GOLD));
                models.stream().limit(12).forEach(model -> sender.sendMessage(Component.text(
                        model.name() + " \u2014 " + model.title(), NamedTextColor.GRAY)));
                sender.sendMessage(Component.text(
                        "All of them: https://gen.pollinations.ai/" + kind + "/models",
                        NamedTextColor.DARK_GRAY));
            });
        });
    }

    private void reload(CommandSender sender) {
        if (!perm(sender, "pollinations.admin")) return;
        plugin.reload();
        sender.sendMessage(Component.text("Config re-read.", NamedTextColor.GREEN));
        for (String problem : plugin.settings().problems()) {
            sender.sendMessage(Component.text(problem, NamedTextColor.RED));
        }
    }

    // ------------------------------------------------------------- helpers

    private void cost(CommandSender sender, int promptTokens, int completionTokens) {
        if (!plugin.settings().announceCosts()) return;
        ModelInfo model = ModelCatalog.find(
                plugin.catalog().models("text"), plugin.settings().textModel());
        double pollen = model == null ? -1 : model.textCost(promptTokens, completionTokens);
        if (pollen >= 0) {
            sender.sendMessage(Component.text(
                    String.format(java.util.Locale.ROOT, "\u2248 %.8f Pollen", pollen),
                    NamedTextColor.DARK_GRAY));
        }
    }

    private void imageCost(CommandSender sender) {
        if (!plugin.settings().announceCosts()) return;
        ModelInfo model = ModelCatalog.find(
                plugin.catalog().models("image"), plugin.settings().imageModel());
        double pollen = ModelCatalog.imageCost(model);
        if (pollen >= 0) {
            sender.sendMessage(Component.text(
                    String.format(java.util.Locale.ROOT, "\u2248 %.6f Pollen for the picture", pollen),
                    NamedTextColor.DARK_GRAY));
        }
    }

    private boolean hasKey(CommandSender sender) {
        if (plugin.settings().apiKey().isEmpty()) {
            sender.sendMessage(Component.text(
                    "The server owner has not set an API key yet: "
                            + "https://enter.pollinations.ai/keys", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private static boolean perm(CommandSender sender, String node) {
        if (sender.hasPermission(node)) return true;
        sender.sendMessage(Component.text("You lack the " + node + " permission.", NamedTextColor.RED));
        return false;
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_PROMPT ? text : text.substring(0, MAX_PROMPT);
    }

    private static void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(
                Bukkit.getPluginManager().getPlugin("PollinationsCraft"), task);
    }

    private static void runSync(Runnable task) {
        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugin("PollinationsCraft"), task);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (String one : List.of("ask", "say", "join", "npc", "image", "models", "reload")) {
                if (one.startsWith(args[0].toLowerCase(Locale.ROOT))) options.add(one);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("say") || args[0].equalsIgnoreCase("join"))) {
            for (String name : plugin.registry().names()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    options.add(name);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
            for (String one : List.of("create", "remove", "list")) {
                if (one.startsWith(args[1].toLowerCase(Locale.ROOT))) options.add(one);
            }
        }
        return options;
    }
}
