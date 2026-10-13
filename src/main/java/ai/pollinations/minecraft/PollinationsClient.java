package ai.pollinations.minecraft;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Small async client for the Pollinations API (https://gen.pollinations.ai/docs).
 * Every call runs off the server thread and is billed to the configured API key.
 */
public final class PollinationsClient {

    /** One chat message: role is "system", "user" or "assistant". */
    public record Message(String role, String content) {
    }

    /** Thrown when the API answers with a non-2xx status. */
    public static final class ApiException extends RuntimeException {
        private final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final String userAgent;

    public PollinationsClient(String baseUrl, String apiKey, Duration timeout, String userAgent) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = timeout;
        this.userAgent = userAgent;
    }

    public boolean hasKey() {
        return !apiKey.isEmpty();
    }

    /** POST /v1/chat/completions and return the assistant's text. */
    public CompletableFuture<String> chat(String model, List<Message> messages, int maxTokens) {
        HttpRequest request = authorized(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(chatBody(model, messages, maxTokens)))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureOk(response.statusCode(), response.body());
                    return parseChatContent(response.body());
                });
    }

    /** GET /image/{prompt} and return the image bytes (JPEG or PNG). */
    public CompletableFuture<byte[]> image(String prompt, String model, int width, int height, long seed) {
        HttpRequest request = authorized(URI.create(imageUrl(baseUrl, prompt, model, width, height, seed)))
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    ensureOk(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
                    return response.body();
                });
    }

    /** GET /text/models or /image/models and return model names with their aliases. */
    public CompletableFuture<List<String>> models(String kind) {
        String path = "image".equals(kind) ? "/image/models" : "/text/models";
        HttpRequest request = authorized(URI.create(baseUrl + path)).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureOk(response.statusCode(), response.body());
                    return parseModelList(response.body());
                });
    }

    private HttpRequest.Builder authorized(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json, image/*");
        if (hasKey()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    // ---- pure helpers (unit tested) ----

    static String stripTrailingSlash(String url) {
        String value = url == null || url.isBlank() ? "https://gen.pollinations.ai" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    static String chatBody(String model, List<Message> messages, int maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray array = new JsonArray();
        for (Message message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            array.add(item);
        }
        body.add("messages", array);
        if (maxTokens > 0) {
            body.addProperty("max_tokens", maxTokens);
        }
        return body.toString();
    }

    static String imageUrl(String baseUrl, String prompt, String model, int width, int height, long seed) {
        return stripTrailingSlash(baseUrl) + "/image/" + encodePath(prompt)
                + "?model=" + encodeQuery(model)
                + "&width=" + width
                + "&height=" + height
                + "&seed=" + seed
                + "&nologo=true";
    }

    static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String parseChatContent(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ApiException(200, "The model returned no answer.");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        JsonElement content = message == null ? null : message.get("content");
        if (content == null || content.isJsonNull()) {
            throw new ApiException(200, "The model returned an empty answer.");
        }
        if (content.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    text.append(part.getAsJsonObject().get("text").getAsString());
                }
            }
            return text.toString().trim();
        }
        return content.getAsString().trim();
    }

    static List<String> parseModelList(String json) {
        List<String> names = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                names.add(element.getAsString());
                continue;
            }
            JsonObject model = element.getAsJsonObject();
            if (!model.has("name")) {
                continue;
            }
            StringBuilder entry = new StringBuilder(model.get("name").getAsString());
            JsonArray aliases = model.has("aliases") && model.get("aliases").isJsonArray()
                    ? model.getAsJsonArray("aliases") : new JsonArray();
            if (!aliases.isEmpty()) {
                List<String> list = new ArrayList<>();
                aliases.forEach(alias -> list.add(alias.getAsString()));
                entry.append(" (").append(String.join(", ", list)).append(')');
            }
            if (model.has("paid_only") && model.get("paid_only").getAsBoolean()) {
                entry.append(" [paid]");
            }
            names.add(entry.toString());
        }
        return names;
    }

    static String errorMessage(int status, String body) {
        String detail = "";
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement error = root.get("error");
            if (error != null && error.isJsonObject() && error.getAsJsonObject().has("message")) {
                detail = error.getAsJsonObject().get("message").getAsString();
            } else if (error != null && error.isJsonPrimitive()) {
                detail = error.getAsString();
            } else if (root.has("message")) {
                detail = root.get("message").getAsString();
            }
        } catch (RuntimeException ignored) {
            detail = body == null ? "" : body.strip();
        }
        if (detail.length() > 200) {
            detail = detail.substring(0, 200) + "...";
        }
        String hint = switch (status) {
            case 401 -> "Check api-key in config.yml (create one at https://enter.pollinations.ai/keys).";
            case 402 -> "The API key is out of Pollen. Top up at https://enter.pollinations.ai.";
            case 429 -> "Rate limited, try again in a moment.";
            default -> "";
        };
        String text = "Pollinations API error " + status + (detail.isEmpty() ? "" : ": " + detail);
        return hint.isEmpty() ? text : text + " " + hint;
    }

    private static void ensureOk(int status, String body) {
        if (status < 200 || status >= 300) {
            throw new ApiException(status, errorMessage(status, body));
        }
    }

    /** Unwraps CompletionException so players see the real cause. */
    public static String describe(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return "The Pollinations API took too long to answer.";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
