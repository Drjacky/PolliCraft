package ai.pollinations.minecraft;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** PolliCraft: Pollinations AI for Paper, Folia and Spigot servers. */
public final class PolliCraftPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("ask", "image", "npc", "models", "reload", "help");
    private static final long HOUR_MILLIS = 3_600_000L;

    private NamespacedKey personaKey;
    private NamespacedKey npcNameKey;
    private NamespacedKey modelKey;

    private PollinationsClient client;
    private RateLimiter chatLimits;
    private RateLimiter imageLimits;
    private volatile long cooldownMillis;
    private MapImages mapImages;
    private Memory memory;
    private Tasks tasks;
    /** One background thread for files (villager memory, map pictures), so the game never waits on the disk. */
    private ExecutorService io;
    /** The live text model list with prices, loaded in the background at start and on reload; null until then. */
    private volatile Catalogue textCatalogue;

    /** Active conversation per player: the villager they talk to. */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * {@code model} is the villager's own text model, or null for the default one. {@code remembered} completes
     * once the saved memory is back in the conversation.
     */
    private record Session(UUID villager, String villagerName, String model, Conversation conversation,
                           CompletableFuture<Void> remembered) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        personaKey = new NamespacedKey(this, "persona");
        npcNameKey = new NamespacedKey(this, "npc_name");
        modelKey = new NamespacedKey(this, "model");
        tasks = new Tasks(this);
        io = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "PolliCraft-files");
            thread.setDaemon(true);
            return thread;
        });
        mapImages = new MapImages(new File(getDataFolder(), "maps"), getLogger());
        memory = new Memory(new File(getDataFolder(), "memory").toPath(), io, getLogger());
        loadSettings();

        PluginCommand command = getCommand("polli");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        tasks.repeatGlobal(this::expireSessions, 20L * 10);

        if (!client.hasKey()) {
            getLogger().warning("No Pollinations API key set. Add api-key to plugins/PolliCraft/config.yml"
                    + " (create one at https://enter.pollinations.ai/keys) and run /polli reload.");
        }
        getLogger().info("PolliCraft ready on " + (Tasks.isFolia() ? "Folia" : Bukkit.getName())
                + ". Text model: " + textModel() + ", image model: " + imageModel());
        checkModels();
    }

    @Override
    public void onDisable() {
        sessions.clear();
        if (tasks != null) {
            tasks.cancelAll();
        }
        if (io != null) {
            io.shutdown(); // finish the last memory and map writes
            try {
                if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                    getLogger().warning("Some villager memory or map files may not have been saved.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration config = getConfig();
        String key = config.getString("api-key", "");
        if (key == null || key.isBlank()) {
            key = System.getenv("POLLINATIONS_API_KEY");
        }
        client = new PollinationsClient(
                config.getString("api-base", "https://gen.pollinations.ai"),
                key,
                Duration.ofSeconds(Math.max(5, config.getInt("timeout-seconds", 90))),
                "PolliCraft/" + getDescription().getVersion());
        cooldownMillis = Math.max(0, config.getLong("cooldown-seconds", 5)) * 1000L;
        int chatsPerHour = config.getInt("chat-limit-per-hour", 60);
        int imagesPerHour = config.getInt("image-limit-per-hour", 10);
        if (chatLimits == null) {
            chatLimits = new RateLimiter(cooldownMillis, chatsPerHour, HOUR_MILLIS);
            imageLimits = new RateLimiter(cooldownMillis, imagesPerHour, HOUR_MILLIS);
        } else {
            chatLimits.setLimits(cooldownMillis, chatsPerHour);
            imageLimits.setLimits(cooldownMillis, imagesPerHour);
        }
    }

    /**
     * Loads the live model lists once, in the background, and warns in the log when a configured model is
     * missing, needs paid Pollen or is down. The text list also prices answers for the cost line.
     */
    private void checkModels() {
        String text = textModel();
        String image = imageModel();
        client.modelList("text").whenComplete((json, error) -> {
            Catalogue catalogue = readCatalogue(json, error, "text");
            if (catalogue != null) {
                textCatalogue = catalogue;
                catalogue.warnings(text, "text").forEach(getLogger()::warning);
            }
        });
        client.modelList("image").whenComplete((json, error) -> {
            Catalogue catalogue = readCatalogue(json, error, "image");
            if (catalogue != null) {
                catalogue.warnings(image, "image").forEach(getLogger()::warning);
            }
        });
    }

    private Catalogue readCatalogue(String json, Throwable error, String kind) {
        try {
            if (error == null) {
                return Catalogue.parse(json);
            }
        } catch (RuntimeException unreadable) {
            error = unreadable;
        }
        getLogger().info("Could not check the live " + kind + " model list (" + PollinationsClient.describe(error)
                + "). Continuing without it.");
        return null;
    }

    private String textModel() {
        return getConfig().getString("text-model", "openai");
    }

    private String imageModel() {
        return getConfig().getString("image-model", "flux");
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String rest = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        switch (sub) {
            case "ask" -> ask(sender, rest);
            case "image", "img", "map" -> image(sender, rest);
            case "npc" -> npc(sender, Arrays.copyOfRange(args, 1, args.length));
            case "models" -> models(sender, args.length > 1 ? args[1] : "text");
            case "reload" -> {
                if (!check(sender, "pollicraft.reload")) {
                    return true;
                }
                loadSettings();
                checkModels();
                send(sender, "&aConfig reloaded. Text model: &f" + textModel() + "&a, image model: &f" + imageModel());
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        send(sender, "&eCommands:");
        sender.sendMessage(color("&7/" + label + " ask <question> &f- ask the AI"));
        sender.sendMessage(color("&7/" + label + " image <prompt> &f- paint a prompt on a map"));
        sender.sendMessage(color("&7/" + label + " npc create <name> [persona or description] &f- spawn an AI villager"));
        sender.sendMessage(color("&7/" + label + " npc persona <persona or description> &f- change the nearest AI villager"));
        sender.sendMessage(color("&7/" + label + " npc model <model|default> &f- give the nearest AI villager its own text model"));
        sender.sendMessage(color("&7/" + label + " npc remove &f- remove the nearest AI villager"));
        sender.sendMessage(color("&7/" + label + " npc personas &f- list personas from config.yml"));
        sender.sendMessage(color("&7/" + label + " models [text|image] &f- list live models"));
        sender.sendMessage(color("&7/" + label + " reload &f- reload config.yml"));
        sender.sendMessage(color("&7Right-click an AI villager to talk to it, type &fbye&7 to stop."));
    }

    private void ask(CommandSender sender, String question) {
        if (!check(sender, "pollicraft.ask") || !ready(sender)) {
            return;
        }
        if (question.isEmpty()) {
            send(sender, "&cUsage: /polli ask <question>");
            return;
        }
        if (!passLimit(sender, chatLimits)) {
            return;
        }
        send(sender, "&7Thinking...");
        List<PollinationsClient.Message> messages = List.of(
                new PollinationsClient.Message("system", getConfig().getString("ask.system-prompt", "")),
                new PollinationsClient.Message("user", question));
        String model = textModel();
        client.chat(model, messages, getConfig().getInt("max-tokens", 300))
                .whenComplete((answer, error) -> reply(sender, () -> {
                    if (error != null) {
                        send(sender, "&c" + PollinationsClient.describe(error));
                    } else {
                        send(sender, "&f" + clean(answer.text()));
                        sendCost(sender, model, answer);
                    }
                }));
    }

    private void image(CommandSender sender, String prompt) {
        if (!check(sender, "pollicraft.image") || !ready(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "&cOnly players can receive a map.");
            return;
        }
        if (prompt.isEmpty()) {
            send(sender, "&cUsage: /polli image <prompt>");
            return;
        }
        if (!passLimit(sender, imageLimits)) {
            return;
        }
        int size = Math.max(128, Math.min(2048, getConfig().getInt("image-size", 512)));
        long seed = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        send(player, "&7Painting &f\"" + prompt + "\"&7 with " + imageModel() + "...");
        client.image(prompt, imageModel(), size, size, seed)
                .thenApply(bytes -> {
                    try {
                        return MapImages.toMapImage(bytes);
                    } catch (java.io.IOException e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                })
                .whenComplete((picture, error) -> tasks.entity(player, () -> {
                    if (error != null) {
                        send(player, "&c" + PollinationsClient.describe(error));
                        return;
                    }
                    giveMap(player, picture, prompt);
                }));
    }

    private void giveMap(Player player, BufferedImage picture, String prompt) {
        MapView view = Bukkit.createMap(player.getWorld());
        MapImages.attach(view, picture);
        int mapId = view.getId();
        io.execute(() -> mapImages.save(mapId, picture));
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.setDisplayName(color("&d" + shorten(prompt, 40)));
        meta.setLore(List.of(color("&7Painted by Pollinations (" + imageModel() + ")")));
        item.setItemMeta(meta);
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        left.values().forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        send(player, "&aYour map is ready" + (left.isEmpty() ? "" : " (dropped at your feet, inventory full)")
                + ". Put it in an item frame to show it off!");
    }

    private void models(CommandSender sender, String kind) {
        if (!check(sender, "pollicraft.models")) {
            return;
        }
        String type = kind.equalsIgnoreCase("image") ? "image" : "text";
        client.models(type).whenComplete((list, error) -> reply(sender, () -> {
            if (error != null) {
                send(sender, "&c" + PollinationsClient.describe(error));
                return;
            }
            send(sender, "&e" + list.size() + " " + type + " models (set " + type + "-model in config.yml):");
            String all = String.join(", ", list);
            sender.sendMessage(color("&7" + all));
            if (!(sender instanceof Player) && sender != Bukkit.getConsoleSender()) {
                getLogger().info(all);
            }
        }));
    }

    // ---------------------------------------------------------------- NPCs

    private void npc(CommandSender sender, String[] args) {
        if (!check(sender, "pollicraft.npc")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "&cOnly players can manage AI villagers.");
            return;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create", "spawn" -> {
                if (args.length < 2) {
                    send(player, "&cUsage: /polli npc create <name> [persona or description]");
                    return;
                }
                String name = args[1];
                String persona = args.length > 2
                        ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                        : getConfig().getString("npc.default-persona", "blacksmith");
                spawnVillager(player, name, persona);
            }
            case "persona" -> {
                Villager villager = nearestAiVillager(player, 6);
                if (villager == null || args.length < 2) {
                    send(player, villager == null ? "&cStand next to an AI villager first."
                            : "&cUsage: /polli npc persona <persona or description>");
                    return;
                }
                String persona = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                villager.getPersistentDataContainer().set(personaKey, PersistentDataType.STRING, persona);
                sessions.values().removeIf(session -> session.villager().equals(villager.getUniqueId()));
                memory.forget(villager.getUniqueId());
                send(player, "&a" + npcName(villager) + " now has a new persona.");
            }
            case "model" -> {
                Villager villager = nearestAiVillager(player, 6);
                if (villager == null || args.length < 2) {
                    send(player, villager == null ? "&cStand next to an AI villager first."
                            : "&cUsage: /polli npc model <model|default>");
                    return;
                }
                String model = args[1];
                if (model.equalsIgnoreCase("default")) {
                    villager.getPersistentDataContainer().remove(modelKey);
                    send(player, "&a" + npcName(villager) + " now uses the default model (" + textModel() + ").");
                } else {
                    villager.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, model);
                    send(player, "&a" + npcName(villager) + " now uses " + model + ".");
                    Catalogue catalogue = textCatalogue;
                    if (catalogue != null) {
                        catalogue.warnings(model, "text").forEach(warning -> send(player, "&e" + warning));
                    }
                }
                sessions.values().removeIf(session -> session.villager().equals(villager.getUniqueId()));
            }
            case "remove", "delete" -> {
                Villager villager = nearestAiVillager(player, 6);
                if (villager == null) {
                    send(player, "&cStand next to an AI villager first.");
                    return;
                }
                String name = npcName(villager);
                sessions.values().removeIf(session -> session.villager().equals(villager.getUniqueId()));
                memory.forget(villager.getUniqueId());
                villager.remove();
                send(player, "&aRemoved " + name + ".");
            }
            case "personas" -> {
                ConfigurationSection section = getConfig().getConfigurationSection("personas");
                send(player, "&ePersonas: &f" + (section == null ? "none" : String.join(", ", section.getKeys(false))));
                send(player, "&7Or write your own: /polli npc create Bob a pirate who only talks about treasure");
            }
            default -> send(player, "&cUsage: /polli npc <create|persona|model|remove|personas>");
        }
    }

    private void spawnVillager(Player player, String name, String persona) {
        boolean freeze = getConfig().getBoolean("npc.freeze", true);
        boolean invulnerable = getConfig().getBoolean("npc.invulnerable", true);
        Location location = player.getLocation();
        Villager villager = player.getWorld().spawn(location, Villager.class, spawned -> {
            spawned.setCustomName(color("&d" + name));
            spawned.setCustomNameVisible(true);
            spawned.setPersistent(true);
            spawned.setRemoveWhenFarAway(false);
            spawned.setAI(!freeze);
            spawned.setInvulnerable(invulnerable);
            spawned.setSilent(false);
            PersistentDataContainer data = spawned.getPersistentDataContainer();
            data.set(personaKey, PersistentDataType.STRING, persona);
            data.set(npcNameKey, PersistentDataType.STRING, name);
        });
        send(player, "&aSpawned &d" + name + "&a (" + shorten(persona, 50) + "). Right-click to talk.");
        getLogger().info(player.getName() + " spawned AI villager " + name + " at " + describe(villager.getLocation()));
    }

    private Villager nearestAiVillager(Player player, double radius) {
        Villager best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof Villager villager && isAiVillager(villager)) {
                double distance = villager.getLocation().distanceSquared(player.getLocation());
                if (distance < bestDistance) {
                    best = villager;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean isAiVillager(Entity entity) {
        return entity.getPersistentDataContainer().has(personaKey, PersistentDataType.STRING);
    }

    /** The villager's own text model, or null when it uses text-model from config.yml. */
    private String modelOf(Entity villager) {
        String model = villager.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
        return model == null || model.isBlank() ? null : model;
    }

    private String npcName(Entity villager) {
        String name = villager.getPersistentDataContainer().get(npcNameKey, PersistentDataType.STRING);
        return name == null ? "Villager" : name;
    }

    /** Turns a persona key from config.yml (or free text) into a system prompt. */
    String systemPrompt(String persona, String name) {
        String template = getConfig().getString("personas." + persona.toLowerCase(Locale.ROOT));
        return Personas.systemPrompt(template, persona, name);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Villager villager)
                || !isAiVillager(villager)) {
            return;
        }
        event.setCancelled(true); // no trade window, we talk instead
        Player player = event.getPlayer();
        if (!player.hasPermission("pollicraft.talk")) {
            send(player, "&cYou are not allowed to talk to AI villagers.");
            return;
        }
        if (!ready(player)) {
            return;
        }
        String name = npcName(villager);
        Session current = sessions.get(player.getUniqueId());
        if (current != null && current.villager().equals(villager.getUniqueId())) {
            send(player, "&7You are talking to &d" + name + "&7. Type in chat, or &fbye&7 to stop.");
            return;
        }
        String persona = villager.getPersistentDataContainer().get(personaKey, PersistentDataType.STRING);
        Conversation conversation = new Conversation(systemPrompt(persona, name),
                getConfig().getInt("npc.history-size", 12), System.currentTimeMillis());
        CompletableFuture<Void> remembered = memory.load(villager.getUniqueId(), player.getUniqueId())
                .thenAccept(conversation::restore);
        sessions.put(player.getUniqueId(),
                new Session(villager.getUniqueId(), name, modelOf(villager), conversation, remembered));
        try {
            villager.lookAt(player);
        } catch (NoSuchMethodError spigot) {
            // Paper-only; ignored on Spigot.
        }
        // A right-click only opens the chat and never calls the model, so clicking can't spend Pollen.
        send(player, "&7You are now talking to &d" + name + "&7. Type in chat, or &fbye&7 to stop.");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // AsyncPlayerChatEvent keeps Spigot support; Paper still fires it.
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        String text = event.getMessage().trim();
        if (text.equalsIgnoreCase("bye") || text.equalsIgnoreCase("/bye") || text.equalsIgnoreCase("exit")) {
            sessions.remove(player.getUniqueId());
            send(player, "&7You stop talking to &d" + session.villagerName() + "&7.");
            return;
        }
        tasks.entity(player, () -> {
            Entity villager = Bukkit.getEntity(session.villager());
            double maxDistance = getConfig().getDouble("npc.talk-distance", 10);
            if (villager == null || !villager.isValid() || villager.getWorld() != player.getWorld()
                    || villager.getLocation().distance(player.getLocation()) > maxDistance) {
                sessions.remove(player.getUniqueId());
                send(player, "&7" + session.villagerName() + " is too far away. The conversation ended.");
                return;
            }
            if (!passLimit(player, chatLimits)) {
                return;
            }
            broadcastNear(player, color("&7" + player.getName() + " &8> &d" + session.villagerName()
                    + "&8: &f" + text));
            talk(player, session, text);
        });
    }

    private void talk(Player player, Session session, String text) {
        Conversation conversation = session.conversation();
        String name = session.villagerName();
        String model = session.model() != null ? session.model() : textModel();
        int maxTokens = getConfig().getInt("max-tokens", 300);
        conversation.addUser(text, System.currentTimeMillis());
        // Wait for the saved memory (read when the chat opened), so the answer knows it and the save keeps it.
        session.remembered()
                .thenCompose(loaded -> client.chat(model, conversation.messages(), maxTokens))
                .whenComplete((answer, error) -> tasks.entity(player, () -> {
                    if (error != null) {
                        conversation.dropLastUser();
                        send(player, "&c" + name + " could not answer: " + PollinationsClient.describe(error));
                        return;
                    }
                    String reply = clean(answer.text());
                    conversation.addAssistant(reply, System.currentTimeMillis());
                    if (sessions.get(player.getUniqueId()) == session) {
                        // Only the open chat saves, so a late answer can't bring back memory after bye,
                        // a new persona or a removed villager.
                        memory.save(session.villager(), player.getUniqueId(), conversation.history());
                    }
                    broadcastNear(player, color("&d" + name + "&8: &f" + reply));
                    sendCost(player, model, answer);
                    Entity villager = Bukkit.getEntity(session.villager());
                    if (villager instanceof Villager v && v.getWorld() == player.getWorld()) {
                        Location eyes = player.getEyeLocation();
                        tasks.entity(v, () -> { // the villager's own thread on Folia
                            v.getWorld().playSound(v.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
                            try {
                                v.lookAt(eyes);
                            } catch (NoSuchMethodError spigot) {
                                // Paper-only; plain Spigot just skips it.
                            }
                        });
                    }
                }));
    }

    /** Shows a line to the talking player and to players near them. */
    private void broadcastNear(Player player, String line) {
        double radius = getConfig().getDouble("npc.hear-radius", 16);
        player.sendMessage(line);
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof Player other) {
                other.sendMessage(line);
            }
        }
    }

    private void expireSessions() {
        long idle = Math.max(10, getConfig().getLong("npc.idle-timeout-seconds", 120)) * 1000L;
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            if (!entry.getValue().conversation().isIdle(now, idle)) {
                return false;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                tasks.entity(player, () -> send(player, "&7" + entry.getValue().villagerName()
                        + " went back to work. Right-click to talk again."));
            }
            return true;
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isAiVillager(event.getEntity()) && getConfig().getBoolean("npc.invulnerable", true)) {
            event.setCancelled(true);
        }
    }

    /** Restores generated pictures on maps after a restart. */
    @EventHandler
    public void onMapInit(MapInitializeEvent event) {
        MapView view = event.getMap();
        BufferedImage picture = mapImages.has(view.getId()) ? mapImages.load(view.getId()) : null;
        if (picture != null) {
            MapImages.attach(view, picture);
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean check(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        send(sender, "&cYou don't have permission (" + permission + ").");
        return false;
    }

    private boolean ready(CommandSender sender) {
        if (client.hasKey()) {
            return true;
        }
        send(sender, "&cPolliCraft has no API key yet. An admin must set api-key in plugins/PolliCraft/config.yml.");
        return false;
    }

    /** The cooldown and the hourly cap, per player. The console and pollicraft.bypasscooldown skip both. */
    private boolean passLimit(CommandSender sender, RateLimiter limits) {
        if (!(sender instanceof Player player) || player.hasPermission("pollicraft.bypasscooldown")) {
            return true;
        }
        RateLimiter.Decision decision = limits.tryAcquire(player.getUniqueId(), System.currentTimeMillis());
        if (decision.allowed()) {
            return true;
        }
        long left = decision.retryAfterMillis();
        if (left > cooldownMillis) {
            send(player, "&cYou reached the hourly limit. Try again in " + (left + 59_999) / 60_000 + " min.");
        } else {
            send(player, "&cPlease wait " + (left + 999) / 1000 + "s before the next request.");
        }
        return false;
    }

    /** Shows players with pollicraft.cost (ops by default) what an answer cost, using the list loaded at start. */
    private void sendCost(CommandSender sender, String model, PollinationsClient.Answer answer) {
        Catalogue catalogue = textCatalogue;
        if (catalogue == null || !sender.hasPermission("pollicraft.cost")) {
            return;
        }
        double pollen = catalogue.textCost(model, answer.promptTokens(), answer.completionTokens());
        if (pollen > 0) {
            send(sender, "&8\u2248 " + Catalogue.format(pollen) + " Pollen");
        }
    }

    /** Sends the reply on the player's thread, or the global thread for the console. */
    private void reply(CommandSender sender, Runnable task) {
        if (sender instanceof Player player) {
            tasks.entity(player, task);
        } else {
            tasks.global(task);
        }
    }

    private void send(CommandSender sender, String message) {
        String line = color(getConfig().getString("prefix", "&d[Polli]&r ") + message);
        sender.sendMessage(line);
        if (!(sender instanceof Player) && sender != Bukkit.getConsoleSender()) {
            // RCON senders are gone once an async answer arrives, so keep it in the server log too.
            getLogger().info(ChatColor.stripColor(line));
        }
    }

    static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Flattens model output into one chat-safe line. */
    static String clean(String text) {
        return Personas.clean(text);
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private static String describe(Location location) {
        World world = location.getWorld();
        return (world == null ? "?" : world.getName()) + " " + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("models")) {
            return filter(List.of("text", "image"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
            return filter(List.of("create", "persona", "model", "remove", "personas"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("model")) {
            return filter(List.of("default"), args[2]);
        }
        boolean personaArg = (args.length == 4 && args[1].equalsIgnoreCase("create"))
                || (args.length == 3 && args[1].equalsIgnoreCase("persona"));
        if (args[0].equalsIgnoreCase("npc") && personaArg) {
            ConfigurationSection section = getConfig().getConfigurationSection("personas");
            return section == null ? List.of() : filter(new ArrayList<>(section.getKeys(false)), args[args.length - 1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).collect(Collectors.toList());
    }
}
