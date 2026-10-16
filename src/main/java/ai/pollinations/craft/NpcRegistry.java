package ai.pollinations.craft;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * The NPCs on this server and what each of them remembers. Personas, locations
 * and the tail of every conversation are written to two files in the plugin
 * folder, so an NPC still recognises a player after a restart.
 */
public final class NpcRegistry {

    /** One NPC: a name, the character it plays and where it stands. */
    public record Npc(String id, String name, String persona, String model, Place place) {}

    /** A saved position; the world is looked up again after a restart. */
    public record Place(String world, double x, double y, double z, float yaw) {

        public static Place of(Location location) {
            return new Place(
                    location.getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw());
        }

        public Location toLocation() {
            World world = Bukkit.getWorld(this.world);
            if (world == null) return null;
            return new Location(world, x, y, z, yaw, 0f);
        }

        /** True when this place lies in that chunk. */
        public boolean in(Chunk chunk) {
            return chunk.getWorld().getName().equals(world)
                    && ((int) Math.floor(x) >> 4) == chunk.getX()
                    && ((int) Math.floor(z) >> 4) == chunk.getZ();
        }
    }

    /** NPC names: what a player can type, and what the server allows. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final File npcsFile;
    private final File memoryFile;
    private final int maxTurns;
    private final int maxChars;
    private final Map<String, Npc> byName = new LinkedHashMap<>();
    // npc id -> player id -> memory
    private final Map<String, Map<UUID, ConversationMemory>> memories = new LinkedHashMap<>();
    // One writer thread: memory.yml is saved in order, and never on the game thread.
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "PollinationsCraft memory writer");
        thread.setDaemon(true);
        return thread;
    });

    public NpcRegistry(File dataFolder, int maxTurns, int maxChars) {
        this.npcsFile = new File(dataFolder, "npcs.yml");
        this.memoryFile = new File(dataFolder, "memory.yml");
        this.maxTurns = maxTurns;
        this.maxChars = maxChars;
    }

    public void load() {
        byName.clear();
        if (!npcsFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(npcsFile);
        ConfigurationSection section = yaml.getConfigurationSection("npcs");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection one = section.getConfigurationSection(id);
            if (one == null) continue;
            Place place = new Place(
                    one.getString("world", "world"),
                    one.getDouble("x"),
                    one.getDouble("y"),
                    one.getDouble("z"),
                    (float) one.getDouble("yaw"));
            Npc npc = new Npc(
                    id,
                    one.getString("name", id),
                    one.getString("persona", "a quiet villager"),
                    one.getString("model", ""),
                    place);
            byName.put(npc.name().toLowerCase(), npc);
        }
        loadMemories();
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Npc npc : byName.values()) {
            String base = "npcs." + npc.id() + ".";
            yaml.set(base + "name", npc.name());
            yaml.set(base + "persona", npc.persona());
            yaml.set(base + "model", npc.model());
            yaml.set(base + "world", npc.place().world());
            yaml.set(base + "x", npc.place().x());
            yaml.set(base + "y", npc.place().y());
            yaml.set(base + "z", npc.place().z());
            yaml.set(base + "yaw", npc.place().yaw());
        }
        try {
            yaml.save(npcsFile);
        } catch (IOException e) {
            Bukkit.getLogger().log(Level.WARNING, "pollinations-craft: cannot save npcs.yml", e);
        }
        saveMemories();
    }

    private void loadMemories() {
        memories.clear();
        if (!memoryFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(memoryFile);
        ConfigurationSection npcs = yaml.getConfigurationSection("memory");
        if (npcs == null) return;
        for (String npcId : npcs.getKeys(false)) {
            ConfigurationSection players = npcs.getConfigurationSection(npcId);
            if (players == null) continue;
            Map<UUID, ConversationMemory> perPlayer = new LinkedHashMap<>();
            for (String player : players.getKeys(false)) {
                List<String> lines = players.getStringList(player);
                ConversationMemory memory = new ConversationMemory(maxTurns, maxChars);
                for (int i = 0; i + 1 < lines.size(); i += 2) {
                    memory.remember(lines.get(i), lines.get(i + 1));
                }
                try {
                    perPlayer.put(UUID.fromString(player), memory);
                } catch (IllegalArgumentException ignored) {
                    // A hand-edited file; skip that player.
                }
            }
            memories.put(npcId, perPlayer);
        }
    }

    /**
     * Writes memory.yml in the background, so a crash or a reload loses at
     * most the answer in flight. Call it on the game thread after each answer.
     */
    public void saveMemoriesLater() {
        String text = memoriesYaml().saveToString();
        writer.execute(() -> write(memoryFile, text));
    }

    /** Waits for the background writes; call before the files are read again. */
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void saveMemories() {
        write(memoryFile, memoriesYaml().saveToString());
    }

    private YamlConfiguration memoriesYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Map<UUID, ConversationMemory>> npc : memories.entrySet()) {
            for (Map.Entry<UUID, ConversationMemory> player : npc.getValue().entrySet()) {
                List<String> lines = new ArrayList<>();
                for (String[] pair : rawTurns(player.getValue())) {
                    lines.add(pair[0]);
                    lines.add(pair[1]);
                }
                yaml.set("memory." + npc.getKey() + "." + player.getKey(), lines);
            }
        }
        return yaml;
    }

    /** Write to a temporary file, then swap it in, so a crash never leaves half a file. */
    private static void write(File file, String text) {
        try {
            Path temporary = file.toPath().resolveSibling(file.getName() + ".tmp");
            Files.createDirectories(temporary.getParent());
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Bukkit.getLogger().log(Level.WARNING, "pollinations-craft: cannot save " + file.getName(), e);
        }
    }

    /** The raw player/NPC pairs of one memory, oldest first. */
    private static List<String[]> rawTurns(ConversationMemory memory) {
        List<String[]> turns = new ArrayList<>();
        for (String[] pair : memory.asMessages()) {
            if ("user".equals(pair[0])) {
                turns.add(new String[] {pair[1], ""});
            } else if (!turns.isEmpty()) {
                turns.get(turns.size() - 1)[1] = pair[1];
            }
        }
        return turns;
    }

    public List<Npc> all() {
        return new ArrayList<>(byName.values());
    }

    public Npc get(String name) {
        return name == null ? null : byName.get(name.toLowerCase());
    }

    /** The NPC a villager's tag points at, or null for a removed one. */
    public Npc byId(String id) {
        for (Npc npc : byName.values()) {
            if (npc.id().equals(id)) return npc;
        }
        return null;
    }

    public Npc add(String name, String persona, String model, Place place) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Npc npc = new Npc(id, name, persona, model, place);
        byName.put(name.toLowerCase(), npc);
        return npc;
    }

    public boolean remove(String name) {
        return byName.remove(name.toLowerCase()) != null;
    }

    public ConversationMemory memory(String npcId, UUID player) {
        return memories.computeIfAbsent(npcId, key -> new LinkedHashMap<>())
                .computeIfAbsent(player, key -> new ConversationMemory(maxTurns, maxChars));
    }

    /** Names for tab completion and /polli npc list. */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Npc npc : byName.values()) names.add(npc.name());
        return names;
    }

    /** How the NPC's entity is marked, so a restart can tell it from a stray villager. */
    public static void tag(Villager villager, Npc npc) {
        villager.getPersistentDataContainer().set(
                PollinationsPlugin.NPC_KEY,
                org.bukkit.persistence.PersistentDataType.STRING,
                npc.id());
    }
}
