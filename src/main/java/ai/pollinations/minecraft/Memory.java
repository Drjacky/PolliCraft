package ai.pollinations.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * What each villager remembers about each player, kept across restarts in
 * plugins/PolliCraft/memory/&lt;villager&gt;/&lt;player&gt;.json. Saved after every answer, the way tomdacatto's
 * NpcStore does it. Reads and writes run in order on the plugin's file thread, never on the game thread.
 */
public final class Memory {

    private final Path folder;
    private final Executor io;
    private final Logger logger;

    public Memory(Path folder, Executor io, Logger logger) {
        this.folder = folder;
        this.io = io;
        this.logger = logger;
    }

    /** The saved turns of this player's talk with this villager, oldest first. Empty when there are none. */
    public CompletableFuture<List<PollinationsClient.Message>> load(UUID villager, UUID player) {
        return CompletableFuture.supplyAsync(() -> read(file(villager, player)), io);
    }

    /** Saves the turns after an answer. The JSON is built now, the file is written on the file thread. */
    public void save(UUID villager, UUID player, List<PollinationsClient.Message> turns) {
        String json = toJson(turns);
        Path file = file(villager, player);
        io.execute(() -> write(file, json));
    }

    /** Forgets everything a villager remembers, after its persona changed or it was removed. */
    public void forget(UUID villager) {
        Path dir = folder.resolve(villager.toString());
        io.execute(() -> {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException e) {
                logger.warning("Could not clear " + dir + ": " + e.getMessage());
            }
        });
    }

    private Path file(UUID villager, UUID player) {
        return folder.resolve(villager.toString()).resolve(player + ".json");
    }

    private List<PollinationsClient.Message> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not read " + file + ", starting fresh: " + e.getMessage());
            return List.of();
        }
    }

    private void write(Path file, String json) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warning("Could not save " + file + ": " + e.getMessage());
        }
    }

    static String toJson(List<PollinationsClient.Message> turns) {
        JsonArray array = new JsonArray();
        for (PollinationsClient.Message turn : turns) {
            JsonObject item = new JsonObject();
            item.addProperty("role", turn.role());
            item.addProperty("content", turn.content());
            array.add(item);
        }
        return array.toString();
    }

    static List<PollinationsClient.Message> fromJson(String json) {
        List<PollinationsClient.Message> turns = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject item = element.getAsJsonObject();
            String role = item.get("role").getAsString();
            if (role.equals("user") || role.equals("assistant")) {
                turns.add(new PollinationsClient.Message(role, item.get("content").getAsString()));
            }
        }
        return turns;
    }
}
