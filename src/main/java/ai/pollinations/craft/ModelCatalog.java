package ai.pollinations.craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the model lists served by gen.pollinations.ai (/text/models and
 * /image/models) and prices a call against them. Pure: no Bukkit, no network,
 * so it is covered by the unit tests directly.
 */
public final class ModelCatalog {

    private ModelCatalog() {}

    public static List<ModelInfo> parse(String json) {
        List<ModelInfo> out = new ArrayList<>();
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            // A gateway that answered HTML instead of JSON changes nothing here.
            return out;
        }
        if (!root.isJsonArray()) return out;
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject o = element.getAsJsonObject();
            String name = str(o, "name");
            if (name == null || name.isBlank()) continue;
            double prompt = -1, completion = -1, image = -1;
            if (o.has("pricing") && o.get("pricing").isJsonObject()) {
                JsonObject p = o.getAsJsonObject("pricing");
                prompt = num(p, "promptTextTokens");
                completion = num(p, "completionTextTokens");
                image = num(p, "completionImageTokens");
            }
            List<String> aliases = new ArrayList<>();
            if (o.has("aliases") && o.get("aliases").isJsonArray()) {
                for (JsonElement alias : o.getAsJsonArray("aliases")) {
                    if (alias.isJsonPrimitive()) aliases.add(alias.getAsString());
                }
            }
            JsonObject health = o.has("health") && o.get("health").isJsonObject()
                    ? o.getAsJsonObject("health")
                    : new JsonObject();
            out.add(new ModelInfo(
                    name,
                    str(o, "title") == null ? name : str(o, "title"),
                    str(o, "category") == null ? "" : str(o, "category"),
                    prompt,
                    completion,
                    image,
                    aliases,
                    "true".equals(str(o, "paid_only")),
                    "down".equals(str(health, "status"))));
        }
        return out;
    }

    /** Exact name, or the first alias or suffix that matches, ignoring case. */
    public static String resolve(List<ModelInfo> models, String asked) {
        if (asked == null || asked.isBlank()) return null;
        String want = asked.trim().toLowerCase(Locale.ROOT);
        for (ModelInfo model : models) {
            if (model.name().equalsIgnoreCase(asked.trim())) return model.name();
        }
        for (ModelInfo model : models) {
            for (String alias : model.aliases()) {
                if (alias.equalsIgnoreCase(want)) return model.name();
            }
        }
        for (ModelInfo model : models) {
            if (model.name().toLowerCase(Locale.ROOT).endsWith("/" + want)
                    || model.name().toLowerCase(Locale.ROOT).equals(want)) {
                return model.name();
            }
        }
        return null;
    }

    /**
     * What the admin should hear about a configured model: missing from the
     * live list, paid-only, or down. Empty when the model is fine. The idea
     * comes from tomdacatto's "Pollinations for Paper" (Catalogue.java).
     */
    public static List<String> warnings(List<ModelInfo> models, String asked, String kind) {
        List<String> warnings = new ArrayList<>();
        ModelInfo model = find(models, asked);
        if (model == null) {
            warnings.add(kind + " model " + asked + " is not in the live list at"
                    + " https://gen.pollinations.ai/" + kind + "/models");
        } else {
            if (model.paidOnly()) {
                warnings.add(kind + " model " + asked + " needs paid Pollen;"
                        + " a key with only free Pollen gets errors.");
            }
            if (model.down()) {
                warnings.add(kind + " model " + asked + " is reported down right now.");
            }
        }
        return warnings;
    }

    /** The model entry a short or full name points at, or null. */
    public static ModelInfo find(List<ModelInfo> models, String asked) {
        String resolved = resolve(models, asked);
        if (resolved == null) return null;
        for (ModelInfo model : models) {
            if (model.name().equals(resolved)) return model;
        }
        return null;
    }

    /** Pollen for one image on this model, or -1 when the price is unknown. */
    public static double imageCost(ModelInfo model) {
        return model == null || model.completionImageTokens() < 0
                ? -1
                : model.completionImageTokens();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static double num(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return -1;
        try {
            return Double.parseDouble(o.get(key).getAsString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Names only, for /polli models output. */
    public static JsonArray namesJson(List<ModelInfo> models) {
        JsonArray array = new JsonArray();
        for (ModelInfo model : models) array.add(model.name());
        return array;
    }
}
