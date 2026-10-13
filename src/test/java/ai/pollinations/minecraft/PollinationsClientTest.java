package ai.pollinations.minecraft;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollinationsClientTest {

    @Test
    void imageUrlEncodesPromptAndModel() {
        String url = PollinationsClient.imageUrl("https://gen.pollinations.ai/", "a red fox & a creeper?", "flux", 512, 512, 42);
        assertEquals("https://gen.pollinations.ai/image/a%20red%20fox%20%26%20a%20creeper%3F"
                + "?model=flux&width=512&height=512&seed=42&nologo=true", url);
    }

    @Test
    void imageUrlEncodesNamespacedModel() {
        String url = PollinationsClient.imageUrl("https://gen.pollinations.ai", "cat", "black-forest-labs/flux.1-schnell", 128, 128, 1);
        assertTrue(url.contains("model=black-forest-labs%2Fflux.1-schnell"), url);
    }

    @Test
    void chatBodyHasModelMessagesAndMaxTokens() {
        String body = PollinationsClient.chatBody("openai", List.of(
                new PollinationsClient.Message("system", "be \"nice\""),
                new PollinationsClient.Message("user", "hi")), 100);
        assertEquals("{\"model\":\"openai\",\"messages\":[{\"role\":\"system\",\"content\":\"be \\\"nice\\\"\"},"
                + "{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":100}", body);
    }

    @Test
    void parsesChatContentStringAndParts() {
        assertEquals("Hello there", PollinationsClient.parseChatContent(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\" Hello there \"}}]}"));
        assertEquals("ab", PollinationsClient.parseChatContent(
                "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}}]}"));
        assertThrows(PollinationsClient.ApiException.class,
                () -> PollinationsClient.parseChatContent("{\"choices\":[]}"));
    }

    @Test
    void parsesLiveModelListShape() {
        List<String> models = PollinationsClient.parseModelList("[{\"name\":\"black-forest-labs/flux.1-schnell\","
                + "\"aliases\":[\"flux\"],\"paid_only\":false},{\"name\":\"x/y\",\"aliases\":[],\"paid_only\":true},\"plain\"]");
        assertEquals(List.of("black-forest-labs/flux.1-schnell (flux)", "x/y [paid]", "plain"), models);
    }

    @Test
    void errorMessagesExplainCommonStatuses() {
        String unauthorized = PollinationsClient.errorMessage(401, "{\"error\":{\"message\":\"Invalid API key\"}}");
        assertTrue(unauthorized.contains("401") && unauthorized.contains("Invalid API key")
                && unauthorized.contains("enter.pollinations.ai/keys"), unauthorized);
        String broke = PollinationsClient.errorMessage(402, "not json");
        assertTrue(broke.contains("402") && broke.contains("not json") && broke.contains("Pollen"), broke);
    }

    @Test
    void baseUrlDefaultsAndTrims() {
        assertEquals("https://gen.pollinations.ai", PollinationsClient.stripTrailingSlash(""));
        assertEquals("http://localhost:8080", PollinationsClient.stripTrailingSlash("http://localhost:8080//"));
    }

    @Test
    void conversationKeepsSystemPromptAndLatestTurns() {
        Conversation conversation = new Conversation("sys", 4, 0);
        for (int i = 1; i <= 3; i++) {
            conversation.addUser("u" + i, i);
            conversation.addAssistant("a" + i, i);
        }
        List<PollinationsClient.Message> messages = conversation.messages();
        assertEquals("system", messages.get(0).role());
        assertEquals(List.of("u2", "a2", "u3", "a3"),
                messages.subList(1, messages.size()).stream().map(PollinationsClient.Message::content).toList());
        conversation.addUser("u4", 10);
        conversation.dropLastUser();
        assertEquals("a3", conversation.messages().get(conversation.messages().size() - 1).content());
        // History never starts with an assistant turn after trimming.
        Conversation odd = new Conversation("sys", 3, 0);
        odd.addUser("u1", 1);
        odd.addAssistant("a1", 1);
        odd.addUser("u2", 2);
        odd.addAssistant("a2", 2);
        assertEquals("user", odd.messages().get(1).role());
        assertTrue(conversation.isIdle(200_000, 120_000));
        assertFalse(conversation.isIdle(10, 120_000));
    }

    @Test
    void cooldownBlocksUntilItExpires() {
        Cooldowns cooldowns = new Cooldowns(5_000);
        UUID player = UUID.randomUUID();
        assertEquals(0, cooldowns.tryUse(player, 1_000));
        assertEquals(3_000, cooldowns.tryUse(player, 3_000));
        assertEquals(0, cooldowns.tryUse(player, 6_000));
        assertEquals(0, cooldowns.tryUse(UUID.randomUUID(), 6_001));
    }

    @Test
    void personaPromptUsesTemplateOrFreeText() {
        String fromConfig = Personas.systemPrompt("You are {name}, a smith.", "blacksmith", "Bob");
        assertTrue(fromConfig.startsWith("You are Bob, a smith."), fromConfig);
        String custom = Personas.systemPrompt(null, "a pirate who loves treasure", "Jack");
        assertTrue(custom.contains("Jack") && custom.contains("a pirate who loves treasure"), custom);
    }

    @Test
    void cleanFlattensMarkdownAndColourCodes() {
        assertEquals("Hello brave miner!", Personas.clean("<think>plan</think>**Hello**\n\n  brave _miner_!"));
        assertEquals("&4red", Personas.clean("\u00a74red"));
        assertEquals(600, Personas.clean("x".repeat(1000)).length());
    }

    @Test
    void fitSquareCentreCropsToMapSize() {
        BufferedImage wide = new BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 300; x++) {
            for (int y = 0; y < 100; y++) {
                wide.setRGB(x, y, x < 100 || x >= 200 ? 0xFF0000 : 0x00FF00);
            }
        }
        BufferedImage out = MapImages.fitSquare(wide, 128);
        assertEquals(128, out.getWidth());
        assertEquals(128, out.getHeight());
        // Only the green centre survives the crop.
        assertEquals(0x00FF00, out.getRGB(2, 64) & 0xFFFFFF);
        assertEquals(0x00FF00, out.getRGB(125, 64) & 0xFFFFFF);
    }
}
