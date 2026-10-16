package ai.pollinations.craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * The Pollinations gateway, from a plain JVM: chat completions, images and the
 * live model lists. No Bukkit types, so the unit tests can point it at a local
 * server, and every failure comes back as a message a player can read.
 */
public final class PollinationsClient {

    /** One chat completion: what the model said and what it cost in tokens. */
    public record ChatReply(String text, int promptTokens, int completionTokens) {}

    private final HttpClient http;
    private final String baseUrl;

    public PollinationsClient() {
        this("https://gen.pollinations.ai");
    }

    public PollinationsClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private HttpRequest.Builder authorized(String url, String apiKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    /** GET /text/models or /image/models, as the raw JSON body. */
    public String modelList(String kind, String apiKey) throws Exception {
        HttpRequest request = authorized(baseUrl + "/" + kind + "/models", apiKey)
                .GET()
                .build();
        HttpResponse<String> done = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (done.statusCode() != 200) {
            throw new IllegalStateException(
                    "the model list at " + baseUrl + " answered HTTP " + done.statusCode());
        }
        return done.body();
    }

    /**
     * One chat completion. History is pairs of role and content, oldest first.
     */
    public ChatReply chat(
            String model,
            String apiKey,
            String system,
            List<String[]> history,
            String userLine,
            int maxTokens) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        if (system != null && !system.isBlank()) {
            payload.add("messages", messages(system, history, userLine));
        } else {
            payload.add("messages", messages(null, history, userLine));
        }
        payload.addProperty("max_tokens", maxTokens);
        payload.addProperty("temperature", 0.8);

        HttpRequest request = authorized(baseUrl + "/v1/chat/completions", apiKey)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();
        if (response.statusCode() != 200) {
            throw new IllegalStateException(errorMessage(body, response.statusCode()));
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("the model returned no answer");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        String text = message.get("content").getAsString().trim();
        int promptTokens = 0, completionTokens = 0;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usage");
            promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : 0;
            completionTokens =
                    usage.has("completion_tokens") ? usage.get("completion_tokens").getAsInt() : 0;
        }
        return new ChatReply(text, promptTokens, completionTokens);
    }

    private static JsonArray messages(String system, List<String[]> history, String userLine) {
        JsonArray messages = new JsonArray();
        if (system != null && !system.isBlank()) {
            messages.add(role("system", system));
        }
        if (history != null) {
            for (String[] turn : history) {
                messages.add(role(turn[0], turn[1]));
            }
        }
        messages.add(role("user", userLine));
        return messages;
    }

    private static JsonObject role(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    /**
     * One generated image, as PNG or JPEG bytes. The image endpoint streams
     * the picture itself, so a failure arrives as HTML or JSON to read. The
     * key goes in the authorization header, never in the URL.
     */
    public byte[] image(
            String model, String apiKey, String prompt, int width, int height, int seed)
            throws Exception {
        String url = baseUrl
                + "/image/" + URLEncoder.encode(prompt, StandardCharsets.UTF_8)
                .replace("+", "%20")
                + "?width=" + width + "&height=" + height
                + "&safe=true"
                + (seed >= 0 ? "&seed=" + seed : "")
                + (model == null || model.isBlank() ? ""
                        : "&model=" + URLEncoder.encode(model, StandardCharsets.UTF_8));
        HttpRequest request = authorized(url, apiKey)
                .timeout(Duration.ofMinutes(3))
                .header("user-agent", "pollinations-craft")
                .GET()
                .build();
        HttpResponse<byte[]> response =
                http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = response.body();
        if (response.statusCode() != 200 || body.length < 100) {
            throw new IllegalStateException(
                    "the image endpoint answered HTTP " + response.statusCode() + ": "
                            + new String(body, 0, Math.min(body.length, 200), StandardCharsets.UTF_8));
        }
        return body;
    }

    private static String errorMessage(String body, int status) {
        String message = "the model gateway answered HTTP " + status;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("error")) {
                JsonElement error = parsed.getAsJsonObject().get("error");
                if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                    message += ": " + error.getAsJsonObject().get("message").getAsString();
                } else if (error.isJsonPrimitive()) {
                    message += ": " + error.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // The body is not JSON; the status line is all we can say.
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}
