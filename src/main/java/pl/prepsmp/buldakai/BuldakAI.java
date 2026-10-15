package pl.prepsmp.buldakai;

import org.bukkit.ChatColor; import org.bukkit.entity.Player; import
org.bukkit.event.EventHandler; import org.bukkit.event.Listener; import
org.bukkit.event.player.AsyncPlayerChatEvent; import
org.bukkit.plugin.java.JavaPlugin; import
org.bukkit.scheduler.BukkitRunnable; import
org.bukkit.configuration.file.FileConfiguration;

import java.net.URI; import java.net.http.HttpClient; import
java.net.http.HttpRequest; import java.net.http.HttpResponse; import
java.nio.charset.StandardCharsets; import java.text.Normalizer; import
java.time.Duration; import java.util.*; import
java.util.concurrent.ConcurrentHashMap; import
java.util.concurrent.TimeUnit; import java.util.regex.Matcher; import
java.util.regex.Pattern;

public final class BuldakAI extends JavaPlugin implements Listener {
private HttpClient http; private final Map<UUID, Long> lastRequest = new
ConcurrentHashMap<>(); private final Map<UUID, Deque> history = new
ConcurrentHashMap<>(); private final Map<UUID, List> hourlyRequests =
new ConcurrentHashMap<>(); private String trigger, prefix, systemPrompt,
apiUrl, apiKey, model; private int cooldown, maxInput, maxOutput,
maxHistory, hourlyLimit, timeout;

    private static final String OWNER_NAME = "2n4rchLoL";
    private static final String BULDAK_NPC_ID = "699";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, timeout)))
                .build();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("BuldakAI enabled. Trigger: " + trigger);
    }

    private void reloadSettings() {
        FileConfiguration c = getConfig();
        trigger = c.getString("chat.trigger", "!buldak").toLowerCase(Locale.ROOT);
        prefix = ChatColor.translateAlternateColorCodes('&', c.getString("chat.prefix", "&d[Buldak] &f"));
        systemPrompt = c.getString("chat.system-prompt", "You are Buldak. Reply in Polish.");
        apiUrl = c.getString("api.base-url", "https://openrouter.ai/api/v1/chat/completions");
        apiKey = c.getString("api.api-key", "").trim();
        model = c.getString("api.model", "nvidia/nemotron-3-ultra-550b-a55b:free");
        timeout = Math.max(5, c.getInt("api.timeout-seconds", 25));
        cooldown = Math.max(1, c.getInt("chat.cooldown-seconds", 8));
        maxInput = Math.max(30, c.getInt("chat.max-message-length", 220));
        maxOutput = Math.max(60, c.getInt("chat.max-response-length", 240));
        maxHistory = Math.max(0, c.getInt("chat.max-history-messages", 8));
        hourlyLimit = Math.max(1, c.getInt("chat.hourly-limit", 20));
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        String raw = event.getMessage();
        if (!raw.toLowerCase(Locale.ROOT).startsWith(trigger)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        String question = raw.substring(trigger.length()).trim();

        if (question.isEmpty()) {
            send(player, ChatColor.LIGHT_PURPLE + "Napisz np. " + trigger + " hej!");
            return;
        }

        // Handle NPC commands before sending the message to the AI.
        String normalized = Normalizer.normalize(question, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);

        if (normalized.equals("teleportuj sie do mnie")
                || normalized.equals("tp do mnie")) {
            if (!player.getName().equalsIgnoreCase(OWNER_NAME)) {
                getServer().getScheduler().runTask(this, () ->
                        send(player, ChatColor.RED + "Tylko wlasciciel moze sterowac Buldakiem."));
                return;
            }

            getServer().getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;
                boolean success = player.performCommand("znpcs move " + BULDAK_NPC_ID);
                if (success) {
                    send(player, ChatColor.GREEN + "Teleportuje Buldaka do Ciebie!");
                } else {
                    send(player, ChatColor.RED + "Nie udalo sie wykonac komendy ZNPCs.");
                }
            });
            return;
        }

        if (question.length() > maxInput) {
            send(player, ChatColor.RED + "Wiadomość jest za długa (limit " + maxInput + " znaków).");
            return;
        }
        if (apiKey.isBlank()) {
            send(player, ChatColor.RED + "AI nie jest skonfigurowane. Administrator musi ustawić api.api-key w plugins/BuldakAI/config.yml.");
            return;
        }

        long now = System.currentTimeMillis();
        long previous = lastRequest.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < cooldown * 1000L) {
            send(player, ChatColor.GRAY + "Daj mi sekundkę — cooldown " + cooldown + " s.");
            return;
        }
        List<Long> times = hourlyRequests.computeIfAbsent(player.getUniqueId(), k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (times) {
            times.removeIf(t -> now - t >= TimeUnit.HOURS.toMillis(1));
            if (times.size() >= hourlyLimit) {
                send(player, ChatColor.RED + "Limit Buldaka: " + hourlyLimit + " zapytań na godzinę.");
                return;
            }
            times.add(now);
        }
        lastRequest.put(player.getUniqueId(), now);
        send(player, ChatColor.GRAY + "Myślę...");

        new BukkitRunnable() {
            @Override public void run() {
                try {
                    String answer = ask(player.getUniqueId(), question);
                    String finalAnswer = trim(answer, maxOutput);
                    getServer().getScheduler().runTask(BuldakAI.this, () -> {
                        if (player.isOnline()) send(player, finalAnswer);
                    });
                } catch (Exception ex) {
                    getLogger().warning("AI request failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    getServer().getScheduler().runTask(BuldakAI.this, () -> {
                        if (player.isOnline()) send(player, ChatColor.RED + "Nie mogę teraz odpowiedzieć. Spróbuj później.");
                    });
                }
            }
        }.runTaskAsynchronously(this);
    }

    private String ask(UUID uuid, String question) throws Exception {
        Deque<Message> deque = history.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        synchronized (deque) {
            messages.addAll(deque);
        }
        messages.add(new Message("user", question));
        String payload = buildPayload(model, messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Provider HTTP " + response.statusCode());
        }
        String answer = extractContent(response.body());
        if (answer.isBlank()) throw new IllegalStateException("Empty AI response");

        synchronized (deque) {
            if (maxHistory > 0) {
                deque.addLast(new Message("user", question));
                deque.addLast(new Message("assistant", answer));
                while (deque.size() > maxHistory) deque.removeFirst();
            }
        }
        return answer;
    }

    private static String buildPayload(String model, List<Message> messages) {
        StringBuilder b = new StringBuilder("{\"model\":\"").append(json(model)).append("\",\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) b.append(',');
            Message m = messages.get(i);
            b.append("{\"role\":\"").append(json(m.role)).append("\",\"content\":\"")
             .append(json(m.content)).append("\"}");
        }
        return b.append("],\"temperature\":0.7,\"max_tokens\":180}").toString();
    }

    private static String extractContent(String body) {
        Pattern p = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(body);
        if (!m.find()) throw new IllegalStateException("No content in API response");
        return unjson(m.group(1));
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 32) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
            }
        }
        return b.toString();
    }

    private static String unjson(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case '"' -> b.append('"');
                case '\\' -> b.append('\\');
                case '/' -> b.append('/');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> b.append(n);
            }
        }
        return b.toString();
    }

    private void send(Player player, String text) {
        player.sendMessage(prefix + ChatColor.stripColor(text));
    }

    private static String trim(String s, int max) {
        String cleaned = s.replaceAll("[\\r\\n]+", " ").trim();
        if (cleaned.length() <= max) return cleaned;
        return cleaned.substring(0, Math.max(0, max - 1)) + "…";
    }

    private record Message(String role, String content) {}

}
