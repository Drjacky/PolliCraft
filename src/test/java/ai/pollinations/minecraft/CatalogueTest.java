package ai.pollinations.minecraft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogueTest {

    private static final String LIST = "["
            + "{\"name\":\"openai/gpt-5.4-nano\",\"aliases\":[\"openai\"],\"paid_only\":false,"
            + "\"pricing\":{\"promptTextTokens\":\"0.00000015\",\"completionTextTokens\":\"0.0000009375\"},"
            + "\"health\":{\"status\":\"healthy\"}},"
            + "{\"name\":\"amazon/nova-micro-v1\",\"aliases\":[],\"paid_only\":true,\"health\":{\"status\":\"down\"}},"
            + "{\"name\":\"no-price\",\"health\":null}]";

    @Test
    void freeHealthyModelHasNoWarningsByNameOrAlias() {
        Catalogue catalogue = Catalogue.parse(LIST);
        assertEquals(List.of(), catalogue.warnings("openai", "text"));
        assertEquals(List.of(), catalogue.warnings("OpenAI/GPT-5.4-nano", "text"));
        assertEquals(List.of(), catalogue.warnings("no-price", "text"));
    }

    @Test
    void warnsAboutPaidOnlyDownAndMissingModels() {
        Catalogue catalogue = Catalogue.parse(LIST);
        List<String> warnings = catalogue.warnings("amazon/nova-micro-v1", "text");
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("paid Pollen"), warnings.get(0));
        assertTrue(warnings.get(1).contains("down"), warnings.get(1));
        assertTrue(catalogue.warnings("nope", "image").get(0).contains("not in the live list"));
    }

    @Test
    void pricesAnAnswerFromTokenCounts() {
        Catalogue catalogue = Catalogue.parse(LIST);
        double pollen = catalogue.textCost("openai", 70, 20);
        assertEquals(70 * 0.00000015 + 20 * 0.0000009375, pollen, 1e-12);
        assertEquals("0.000029", Catalogue.format(pollen));
        assertEquals(-1, catalogue.textCost("no-price", 70, 20));
        assertEquals(-1, catalogue.textCost("nope", 70, 20));
    }
}
