package ai.pollinations.craft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
    private RateLimiter chatLimit;
    private RateLimiter imageLimit;
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
        int restored = gallery.restore();
        getLogger().info("pollinations-craft enabled, " + restored + " saved map(s) restored");
        for (String problem : settings.problems()) getLogger().warning(problem);
    }

    /** Re-reads the config and drops the caches; used by enable and reload. */
    public void reload() {
        reloadConfig();
        settings = CraftConfig.read(getConfig());
        client = new PollinationsClient();
        if (registry != null) registry.close();
        registry = new NpcRegistry(getDataFolder(), settings.memoryTurns(), settings.memoryChars());
        registry.load();
        gallery = new MapGallery(getDataFolder());
        catalog = new ModelCatalogCache(this, client, settings.apiKey());
        chatLimit = limiter(settings.chatsPerHour());
        imageLimit = limiter(settings.imagesPerHour());
        settleLoadedNpcs();
        checkModels();
    }

    private RateLimiter limiter(int perHour) {
        return new RateLimiter(settings.cooldownSeconds() * 1000L,
                perHour == 0 ? Integer.MAX_VALUE : perHour, 3_600_000L);
    }

    /** Warns in the log when a configured model is missing, paid-only or down. */
    private void checkModels() {
        CraftConfig current = settings;
        List<String> npcModels = new ArrayList<>();
        for (NpcRegistry.Npc npc : registry.all()) {
            if (!npc.model().isBlank()) npcModels.add(npc.model());
        }
        catalog.fetch("text", models -> {
            if (models.isEmpty()) {
                getLogger().info("Could not check the live text model list; costs show once it loads.");
                return;
            }
            warn(ModelCatalog.warnings(models, current.textModel(), "text"));
            for (String model : npcModels) warn(ModelCatalog.warnings(models, model, "text"));
        });
        catalog.fetch("image", models -> {
            if (!models.isEmpty()) warn(ModelCatalog.warnings(models, current.imageModel(), "image"));
        });
    }

    private void warn(List<String> warnings) {
        for (String warning : warnings) getLogger().warning(warning);
    }

    @Override
    public void onDisable() {
        sessions.clear();
        if (registry != null) {
            registry.save();
            registry.close();
        }
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

    /** One more NPC answer for this player now, or a "slow down" note. The console is never limited. */
    public boolean allowChat(CommandSender sender) {
        return allow(sender, chatLimit);
    }

    /** One more picture for this player now, or a "slow down" note. */
    public boolean allowImageRequest(CommandSender sender) {
        return allow(sender, imageLimit);
    }

    private static boolean allow(CommandSender sender, RateLimiter limiter) {
        if (!(sender instanceof Player player)) return true;
        RateLimiter.Decision decision = limiter.tryAcquire(player.getUniqueId(), System.currentTimeMillis());
        if (!decision.allowed()) {
            long seconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
            player.sendMessage(Component.text(
                    "Slow down: try again in " + seconds + "s.", NamedTextColor.RED));
        }
        return decision.allowed();
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

    /**
     * The world saves each NPC's villager, so a restart reuses it. Villagers
     * load with their chunk, often after the plugin starts, so each chunk that
     * holds an NPC is checked once its entities are in: the chunks already
     * loaded now, the others in {@link #onEntitiesLoad}.
     */
    private void settleLoadedNpcs() {
        for (NpcRegistry.Npc npc : registry.all()) {
            Location at = npc.place().toLocation();
            if (at != null && at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                settle(at.getChunk());
            }
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        Chunk chunk = event.getChunk();
        boolean ours = event.getEntities().stream().anyMatch(entity -> npcId(entity) != null)
                || registry.all().stream().anyMatch(npc -> npc.place().in(chunk));
        if (ours) Bukkit.getScheduler().runTask(this, () -> settle(chunk));
    }

    /**
     * One villager per NPC: a villager in this chunk goes when its NPC was
     * removed or another copy of it is already loaded (older versions spawned
     * one per restart), and an NPC whose place is here gets a villager when
     * none is loaded. Nothing is touched while npcs.yml cannot be read.
     */
    private void settle(Chunk chunk) {
        if (registry.broken() || !chunk.isLoaded() || !chunk.isEntitiesLoaded()) return;
        for (Entity entity : chunk.getEntities()) {
            String id = npcId(entity);
            if (id == null) continue;
            boolean another = findNpcs(id).stream()
                    .anyMatch(other -> !other.getUniqueId().equals(entity.getUniqueId()));
            if (registry.byId(id) == null || another) entity.remove();
        }
        for (NpcRegistry.Npc npc : registry.all()) {
            if (npc.place().in(chunk) && findNpc(npc.id()) == null) {
                spawnVillager(npc, npc.place().toLocation());
            }
        }
    }

    private static String npcId(Entity entity) {
        return entity instanceof Villager
                ? entity.getPersistentDataContainer().get(NPC_KEY, PersistentDataType.STRING)
                : null;
    }

    /** The live villager of one NPC, or null when it is not loaded. */
    public Villager findNpc(String npcId) {
        List<Villager> found = findNpcs(npcId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Every loaded villager of one NPC. */
    public List<Villager> findNpcs(String npcId) {
        List<Villager> found = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (villager.isValid() && npcId.equals(npcId(villager))) found.add(villager);
            }
        }
        return found;
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

    /**
     * Right-clicking an NPC only opens the conversation; nothing is sent to
     * the model until the player types. The hand and permission checks come
     * from tomdacatto's "Pollinations for Paper" (NpcListener.java).
     */
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        String npcId = npcId(event.getRightClicked());
        if (npcId == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        NpcRegistry.Npc npc = registry.byId(npcId);
        if (npc == null) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("pollinations.chat")) {
            player.sendMessage(Component.text(
                    "You lack the pollinations.chat permission.", NamedTextColor.RED));
            return;
        }
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
            // Chat arrives on its own thread; the NPCs and their memory live on the game thread.
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTask(this,
                    () -> handler.talkToNpc(player, session.npcName(), line, session.room()));
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

    /**
     * The live model lists, fetched in the background when the plugin starts
     * and again once an hour. The game never waits for them: until a list
     * arrives, prices are simply not shown.
     */
    public static final class ModelCatalogCache {

        private final JavaPlugin plugin;
        private final PollinationsClient client;
        private final String apiKey;
        private final Map<String, Cached> cache = new ConcurrentHashMap<>();
        private final Map<String, Long> tried = new ConcurrentHashMap<>();

        private record Cached(List<ModelInfo> models, long at) {}

        public ModelCatalogCache(JavaPlugin plugin, PollinationsClient client, String apiKey) {
            this.plugin = plugin;
            this.client = client;
            this.apiKey = apiKey;
        }

        /** The models fetched so far, or an empty list. Never touches the network. */
        public List<ModelInfo> models(String kind) {
            Cached cached = cache.get(kind);
            if (cached == null || System.currentTimeMillis() - cached.at() > 3_600_000) {
                fetch(kind, models -> {});
            }
            return cached == null ? List.of() : cached.models();
        }

        /**
         * Fetches one list off the game thread, at most once a minute, then
         * hands it over (empty when it failed or is still loading).
         */
        public void fetch(String kind, Consumer<List<ModelInfo>> then) {
            long now = System.currentTimeMillis();
            if (now - tried.getOrDefault(kind, 0L) < 60_000) {
                Cached cached = cache.get(kind);
                then.accept(cached == null ? List.of() : cached.models());
                return;
            }
            tried.put(kind, now);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<ModelInfo> models = List.of();
                try {
                    models = ModelCatalog.parse(client.modelList(kind, apiKey));
                } catch (Exception e) {
                    plugin.getLogger().fine("model list: " + e.getMessage());
                }
                if (!models.isEmpty()) cache.put(kind, new Cached(models, System.currentTimeMillis()));
                then.accept(models);
            });
        }
    }
}
