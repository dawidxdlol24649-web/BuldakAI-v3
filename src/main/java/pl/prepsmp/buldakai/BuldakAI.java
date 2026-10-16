
package pl.prepsmp.buldakai;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuldakAI extends JavaPlugin implements Listener {

    private static final String OWNER_NAME = "2n4rchLoL";
    private static final String NPC_COMMAND = "znpcs move 699";

    private HttpClient http;
    private BuldakInventory buldakInventory;

    private String trigger;
    private String prefix;
    private String systemPrompt;
    private String apiUrl;
    private String apiKey;
    private String model;

    private int cooldownSeconds;
    private int maxMessageLength;
    private int maxResponseLength;
    private int maxHistoryMessages;
    private int hourlyLimit;
    private int timeoutSeconds;

    private final Map<UUID, Long> lastRequest =
            new ConcurrentHashMap<>();

    private final Map<UUID, Deque<Long>> hourlyRequests =
            new ConcurrentHashMap<>();

    private final Map<UUID, List<Message>> history =
            new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        // Konstruktor sam rejestruje listener GUI.
        buldakInventory = new BuldakInventory(this);

        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("BuldakAI wlaczony!");
    }

    private void loadSettings() {
        reloadConfig();

        trigger = getConfig().getString(
                "chat.trigger", "!buldak");

        prefix = ChatColor.translateAlternateColorCodes(
                '&',
                getConfig().getString("chat.prefix", "&d[Buldak] &f")
        );

        systemPrompt = getConfig().getString(
                "chat.system-prompt",
                "Masz na imię Buldak. Odpowiadaj po polsku."
        );

        apiUrl = getConfig().getString(
                "api.base-url",
                "https://openrouter.ai/api/v1/chat/completions"
        );

        apiKey = getConfig().getString(
                "api.api-key", ""
        ).trim();

        model = getConfig().getString(
                "api.model",
                "nvidia/nemotron-3-ultra-550b-a55b:free"
        );

        timeoutSeconds = Math.max(
                1, getConfig().getInt("api.timeout-seconds", 25));

        cooldownSeconds = Math.max(
                0, getConfig().getInt("chat.cooldown-seconds", 8));

        maxMessageLength = Math.max(
                1, getConfig().getInt("chat.max-message-length", 220));

        maxResponseLength = Math.max(
                1, getConfig().getInt("chat.max-response-length", 240));

        maxHistoryMessages = Math.max(
                0, getConfig().getInt("chat.max-history-messages", 8));

        hourlyLimit = Math.max(
                1, getConfig().getInt("chat.hourly-limit", 20));
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        String original = event.getMessage();

        if (!original.regionMatches(
                true, 0, trigger, 0, trigger.length())) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();
        String question = original.substring(trigger.length()).trim();

        if (question.isEmpty()) {
            send(player, "Napisz pytanie po " + trigger + "!");
            return;
        }

        String normalized = normalize(question);

        // !buldak eq
        if (normalized.equals("eq")
                || normalized.equals("ekwipunek")) {

            if (!player.getName().equalsIgnoreCase(OWNER_NAME)) {
                send(player, ChatColor.RED
                        + "Tylko wlasciciel moze otworzyc ekwipunek Buldaka.");
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) {
                    return;
                }

                buldakInventory.open(player);
                send(player, "Otwieram ekwipunek Buldaka!");
            });

            return;
        }

        // !buldak teleportuj sie do mnie
        // !buldak tp do mnie
        if (normalized.equals("teleportuj sie do mnie")
                || normalized.equals("tp do mnie")) {

            if (!player.getName().equalsIgnoreCase(OWNER_NAME)) {
                send(player, "Nie moge wykonac tej komendy.");
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) {
                    return;
                }

                boolean success = player.performCommand(NPC_COMMAND);

                if (success) {
                    send(player, "Juz lece szefie!");
                } else {
                    send(player, ChatColor.RED
                            + "Nie udalo sie wykonac komendy teleportu.");
                }
            });

            return;
        }

        // Limit długości pytania
        if (question.length() > maxMessageLength) {
            send(player, ChatColor.RED
                    + "Maksymalna dlugosc wiadomosci to "
                    + maxMessageLength + " znakow.");
            return;
        }

        // Klucz API
        if (apiKey.isEmpty()) {
            send(player, ChatColor.RED
                    + "Brak klucza API. Ustaw api.api-key w config.yml.");
            return;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Cooldown
        Long previous = lastRequest.get(uuid);

        if (previous != null) {
            long cooldownMillis = cooldownSeconds * 1000L;
            long elapsed = now - previous;

            if (elapsed < cooldownMillis) {
                long remaining =
                        (cooldownMillis - elapsed + 999) / 1000;

                send(player, "Poczekaj jeszcze " + remaining + " s!");
                return;
            }
        }

        // Limit wiadomości na godzinę
        Deque<Long> requests = hourlyRequests.computeIfAbsent(
                uuid, ignored -> new ArrayDeque<>());

        synchronized (requests) {
            long hourAgo = now - 3_600_000L;

            while (!requests.isEmpty()
                    && requests.peekFirst() <= hourAgo) {
                requests.removeFirst();
            }

            if (requests.size() >= hourlyLimit) {
                send(player, ChatColor.RED
                        + "Wykorzystales limit "
                        + hourlyLimit + " wiadomosci na godzine.");
                return;
            }

            requests.addLast(now);
        }

        lastRequest.put(uuid, now);

        // Historia rozmowy
        List<Message> conversation = history.computeIfAbsent(
                uuid, ignored -> new ArrayList<>());

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));

        synchronized (conversation) {
            messages.addAll(conversation);
        }

        messages.add(new Message("user", question));

        send(player, ChatColor.GRAY + "Buldak mysli...");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String answer = askAI(messages);

                if (answer == null || answer.isBlank()) {
                    send(player, ChatColor.RED
                            + "AI nie zwrocilo odpowiedzi.");
                    return;
                }

                answer = answer.trim();

                if (answer.length() > maxResponseLength) {
                    answer = answer.substring(0, maxResponseLength);
                }

                synchronized (conversation) {
                    if (maxHistoryMessages > 0) {
                        conversation.add(new Message("user", question));
                        conversation.add(new Message("assistant", answer));

                        while (conversation.size() > maxHistoryMessages) {
                            conversation.remove(0);
                        }
                    } else {
                        conversation.clear();
                    }
                }

                send(player, answer);

            } catch (Exception exception) {
                getLogger().warning(
                        "Blad OpenRouter: " + exception.getMessage());

                send(player, ChatColor.RED
                        + "Blad polaczenia z AI. Sprobuj ponownie pozniej.");
            }
        });
    }

    private String askAI(List<Message> messages) throws Exception {
        StringBuilder body = new StringBuilder();

        body.append("{\"model\":\"")
                .append(json(model))
                .append("\",\"messages\":[");

        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                body.append(",");
            }

            Message message = messages.get(i);

            body.append("{\"role\":\"")
                    .append(json(message.role()))
                    .append("\",\"content\":\"")
                    .append(json(message.content()))
                    .append("\"}");
        }

        body.append("],\"max_tokens\":")
                .append(maxResponseLength)
                .append("}");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(
                request,
                HttpResponse.BodyHandlers.ofString(
                        StandardCharsets.UTF_8));

        if (response.statusCode() < 200
                || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "OpenRouter HTTP " + response.statusCode()
                            + ": " + response.body());
        }

        return extractContent(response.body());
    }

    private String extractContent(String response) {
        Pattern pattern = Pattern.compile(
                "\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

        Matcher matcher = pattern.matcher(response);

        if (!matcher.find()) {
            return null;
        }

        return unjson(matcher.group(1));
    }

    private String json(String value) {
        StringBuilder result = new StringBuilder();

        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }

        return result.toString();
    }

    private String unjson(String value) {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (c != '\\' || i + 1 >= value.length()) {
                result.append(c);
                continue;
            }

            char next = value.charAt(++i);

            switch (next) {
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case '/' -> result.append('/');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        String hex = value.substring(i + 1, i + 5);

                        try {
                            result.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            result.append("\\u").append(hex);
                            i += 4;
                        }
                    }
                }
                default -> result.append(next);
            }
        }

        return result.toString();
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private void send(Player player, String message) {
        Runnable task = () -> {
            if (player.isOnline()) {
                player.sendMessage(prefix + message);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    private record Message(String role, String content) {
    }
  }
  
