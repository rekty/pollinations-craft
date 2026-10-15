package ai.pollinations.craft;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * The plugin's settings, read once and again on /polli reload. The API key
 * stays in the config file on the owner's disk; nothing sends it to players.
 */
public record CraftConfig(
        String apiKey,
        String textModel,
        String imageModel,
        int memoryTurns,
        int memoryChars,
        int imageWidth,
        int imageHeight,
        int dailyImageLimit,
        boolean announceCosts) {

    public static CraftConfig read(FileConfiguration config) {
        return new CraftConfig(
                config.getString("api-key", "").trim(),
                config.getString("text-model", "openai/gpt-5.4-nano").trim(),
                config.getString("image-model", "flux").trim(),
                config.getInt("memory.turns", 12),
                config.getInt("memory.characters", 2000),
                clamp(config.getInt("image.width", 512), 256, 1024),
                clamp(config.getInt("image.height", 512), 256, 1024),
                Math.max(0, config.getInt("daily-image-limit", 40)),
                config.getBoolean("announce-costs", true));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** What the plugin tells an admin is missing, or an empty list. */
    public java.util.List<String> problems() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (apiKey.isEmpty()) {
            problems.add("api-key is empty: create one at https://enter.pollinations.ai/keys"
                    + " and put it in plugins/PollinationsCraft/config.yml");
        } else if (!apiKey.startsWith("pk_") && !apiKey.startsWith("sk_")) {
            problems.add("api-key does not look like a Pollinations key (pk_...)");
        }
        return problems;
    }
}
