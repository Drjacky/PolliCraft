package ai.pollinations.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The live model list, fetched once in the background at start and on /polli reload, never while players wait.
 * It warns about a model that is missing, needs paid Pollen or is down (from tomdacatto's Catalogue) and prices
 * each answer from the list's per-token prices (from rekty's ModelCatalog). Pure, unit tested.
 */
public final class Catalogue {

    /** Model entries by name and by alias, lower case. */
    private final Map<String, JsonObject> models = new HashMap<>();

    private Catalogue() {
    }

    /** Parses the JSON array from /text/models or /image/models. */
    public static Catalogue parse(String json) {
        Catalogue catalogue = new Catalogue();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            if (!element.isJsonObject() || string(element.getAsJsonObject(), "name") == null) {
                continue;
            }
            JsonObject model = element.getAsJsonObject();
            catalogue.models.putIfAbsent(lower(string(model, "name")), model);
            if (model.has("aliases") && model.get("aliases").isJsonArray()) {
                for (JsonElement alias : model.getAsJsonArray("aliases")) {
                    if (alias.isJsonPrimitive()) {
                        catalogue.models.putIfAbsent(lower(alias.getAsString()), model);
                    }
                }
            }
        }
        return catalogue;
    }

    /** Warnings for the admin. Empty when the model is listed, works with free Pollen and is not down. */
    public List<String> warnings(String model, String kind) {
        JsonObject entry = models.get(lower(model));
        if (entry == null) {
            return List.of(kind + " model " + model + " is not in the live list. See /polli models " + kind + ".");
        }
        List<String> warnings = new ArrayList<>();
        if ("true".equals(string(entry, "paid_only"))) {
            warnings.add(kind + " model " + model + " needs paid Pollen. Keys with only free Pollen will get errors.");
        }
        JsonElement health = entry.get("health");
        if (health != null && health.isJsonObject() && "down".equals(string(health.getAsJsonObject(), "status"))) {
            warnings.add(kind + " model " + model + " is down right now.");
        }
        return warnings;
    }

    /** Pollen for one answer from the token counts the API returned, or -1 when the price is unknown. */
    public double textCost(String model, int promptTokens, int completionTokens) {
        double prompt = price(model, "promptTextTokens");
        double completion = price(model, "completionTextTokens");
        return prompt < 0 || completion < 0 ? -1 : promptTokens * prompt + completionTokens * completion;
    }

    private double price(String model, String field) {
        JsonObject entry = models.get(lower(model));
        JsonElement pricing = entry == null ? null : entry.get("pricing");
        String value = pricing != null && pricing.isJsonObject() ? string(pricing.getAsJsonObject(), field) : null;
        try {
            return value == null ? -1 : Double.parseDouble(value);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    /** Two significant digits, no exponent: 0.000029. */
    public static String format(double pollen) {
        return new BigDecimal(pollen).round(new MathContext(2)).stripTrailingZeros().toPlainString();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String lower(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
