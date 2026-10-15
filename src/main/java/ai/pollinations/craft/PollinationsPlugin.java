package ai.pollinations.craft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PollinationsCraft: villagers with personas that chat, and pictures on maps,
 * all billed to the owner's own Pollinations key.
 */
public final class PollinationsPlugin extends JavaPlugin implements Listener {

    static final org.bukkit.NamespacedKey NPC_KEY =
            new org.bukkit.NamespacedKey("pollinationscraft", "npc");

    private CraftConfig settings;
    private PolliCommand handler;
    private PollinationsClient client;
    private NpcRegistry registry;
    private MapGallery gallery;
    private ModelCatalogCache catalog;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> imagesToday = new ConcurrentHashMap<>();
    private String budgetDay = day();

    /** While a player is in a conversation, which NPC it goes to. */
    public record Session(String npcName, long until, boolean room) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reload();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("polli");
        handler = new PolliCommand(this);
        if (command != null) {
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        Bukkit.getScheduler().runTaskLater(this, this::spawnAll, 1L);
        int restored = gallery.restore();
        getLogger().info("pollinations-craft enabled, " + restored + " saved map(s) restored");
        for (String problem : settings.problems()) getLogger().warning(problem);
    }

    /** Re-reads the config and drops the caches; used by enable and reload. */
    public void reload() {
        reloadConfig();
        settings = CraftConfig.read(getConfig());
        client = new PollinationsClient();
        registry = new NpcRegistry(getDataFolder(), settings.memoryTurns(), settings.memoryChars());
        registry.load();
        gallery = new MapGallery(getDataFolder());
        catalog = new ModelCatalogCache(client, settings.apiKey());
    }

    @Override
    public void onDisable() {
        sessions.clear();
        if (registry != null) registry.save();
    }

    public CraftConfig settings() {
        return settings;
    }

    public PollinationsClient client() {
        return client;
    }

    public NpcRegistry registry() {
        return registry;
    }

    public MapGallery gallery() {
        return gallery;
    }

    public ModelCatalogCache catalog() {
        return catalog;
    }

    public Map<UUID, Session> sessions() {
        return sessions;
    }

    /** One more picture today, when the owner's budget allows it. */
    public boolean allowImage(Player player) {
        rollDay();
        int limit = settings.dailyImageLimit();
        if (limit > 0 && imagesToday.getOrDefault(player.getUniqueId(), 0) >= limit) {
            return false;
        }
        imagesToday.merge(player.getUniqueId(), 1, Integer::sum);
        return true;
    }

    public int imagesUsed(Player player) {
        rollDay();
        return imagesToday.getOrDefault(player.getUniqueId(), 0);
    }

    private void rollDay() {
        String today = day();
        if (!today.equals(budgetDay)) {
            budgetDay = today;
            imagesToday.clear();
        }
    }

    private static String day() {
        return java.time.LocalDate.now().toString();
    }

    /** Spawns every saved NPC at its place; missing worlds are skipped. */
    private void spawnAll() {
        for (NpcRegistry.Npc npc : registry.all()) {
            if (findNpc(npc.id()) != null) continue;
            World world = Bukkit.getWorld(npc.place().world());
            if (world == null) continue;
            spawnVillager(npc, npc.place().toLocation());
        }
    }

    /** The live villager of one NPC, or null when it is not loaded. */
    public Villager findNpc(String npcId) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(Villager.class)) {
                String id = entity.getPersistentDataContainer().get(
                        NPC_KEY, PersistentDataType.STRING);
                if (npcId.equals(id)) return (Villager) entity;
            }
        }
        return null;
    }

    public Villager spawnVillager(NpcRegistry.Npc npc, org.bukkit.Location location) {
        Villager villager = location.getWorld().spawn(location, Villager.class, entity -> {
            entity.setAI(false);
            entity.setSilent(true);
            entity.setInvulnerable(true);
            entity.setProfession(Villager.Profession.LIBRARIAN);
            entity.customName(Component.text(npc.name(), NamedTextColor.AQUA));
            entity.setCustomNameVisible(true);
        });
        NpcRegistry.tag(villager, npc);
        return villager;
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Villager villager
                && villager.getPersistentDataContainer().has(NPC_KEY, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    /** Right-clicking an NPC starts the conversation. */
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        String npcId = villager.getPersistentDataContainer().get(
                NPC_KEY, PersistentDataType.STRING);
        if (npcId == null) return;
        event.setCancelled(true);
        NpcRegistry.Npc npc = registry.all().stream()
                .filter(one -> one.id().equals(npcId))
                .findFirst()
                .orElse(null);
        if (npc == null) return;
        Player player = event.getPlayer();
        sessions.put(player.getUniqueId(), new Session(npc.name(), System.currentTimeMillis() + 180_000, false));
        player.sendMessage(Component.text(
                "You are now talking to " + npc.name() + " (" + npc.persona() + ")."
                        + " Type your message in chat, or \"bye\" to stop.",
                NamedTextColor.GRAY));
    }

    /** A line of chat goes to the NPC when a conversation is open. */
    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || System.currentTimeMillis() > session.until()) {
            sessions.remove(event.getPlayer().getUniqueId());
            return;
        }
        String line = strip(event.message());
        if (line.isBlank()) return;
        if (line.equalsIgnoreCase("bye")) {
            sessions.remove(event.getPlayer().getUniqueId());
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "You left the conversation.", NamedTextColor.GRAY));
            return;
        }
        event.setCancelled(true);
        if (handler != null) {
            handler.talkToNpc(event.getPlayer(), session.npcName(), line, session.room());
        }
    }

    static String strip(Component message) {
        // Plain text of the chat line; components carry formatting only.
        return plain(message).trim();
    }

    private static String plain(Component component) {
        StringBuilder out = new StringBuilder();
        for (Component child : component.children()) out.append(plain(child));
        if (component instanceof net.kyori.adventure.text.TextComponent text) {
            out.append(text.content());
        }
        return out.toString();
    }

    /** Cache of the live model list, refreshed every few minutes. */
    public static final class ModelCatalogCache {

        private final PollinationsClient client;
        private final String apiKey;
        private final Map<String, Cached> cache = new ConcurrentHashMap<>();

        private record Cached(List<ModelInfo> models, long at) {}

        public ModelCatalogCache(PollinationsClient client, String apiKey) {
            this.client = client;
            this.apiKey = apiKey;
        }

        /** The text or image models, fetched at most once per five minutes. */
        public List<ModelInfo> models(String kind) {
            Cached cached = cache.get(kind);
            if (cached != null && System.currentTimeMillis() - cached.at() < 300_000) {
                return cached.models();
            }
            try {
                List<ModelInfo> models =
                        ModelCatalog.parse(client.modelList(kind, apiKey));
                cache.put(kind, new Cached(models, System.currentTimeMillis()));
                return models;
            } catch (Exception e) {
                if (cached != null) return cached.models();
                return new ArrayList<>();
            }
        }
    }
}
