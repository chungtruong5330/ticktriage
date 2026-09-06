package dev.ticktriage.paper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import dev.ticktriage.core.Json;
import dev.ticktriage.core.WebhookUrl;

/**
 * Posts incident reports to a Discord webhook.
 *
 * <p>Server owners live in Discord, not in the console. An alert that only
 * reaches the log is an alert nobody reads until the morning.
 *
 * <p>The URL is checked to be an HTTPS Discord webhook before anything is sent.
 * The plugin will happily POST whatever an admin puts in config.yml, so
 * restricting the destination keeps a typo - or a malicious config shipped in
 * somebody's "server pack" - from turning this into a way to fire requests at
 * arbitrary hosts from inside the server's network.
 */
public final class DiscordNotifier {

    /** Discord rejects anything longer outright. */
    private static final int MAX_CONTENT = 1900;
    private static final int TIMEOUT_MILLIS = 5000;

    private final Plugin plugin;
    private final String url;
    private volatile boolean failureLogged;

    private DiscordNotifier(Plugin plugin, String url) {
        this.plugin = plugin;
        this.url = url;
    }

    /** @return a notifier, or null when no valid webhook is configured. */
    public static DiscordNotifier create(Plugin plugin, String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return null;
        }
        String url = rawUrl.trim();
        if (!WebhookUrl.isDiscordWebhook(url)) {
            plugin.getLogger().warning("alerts.discord-webhook is not a Discord"
                    + " webhook URL; Discord alerts are disabled.");
            return null;
        }
        return new DiscordNotifier(plugin, url);
    }

    /** Fire-and-forget. Never blocks the main thread. */
    public void send(String message) {
        final String payload = Json.object("content",
                Json.truncate(message, MAX_CONTENT));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                post(payload);
            }
        });
    }

    private void post(String payload) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL()
                    .openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "TickTriage");
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setDoOutput(true);

            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                failureLogged = false;
                return;
            }
            logFailureOnce("Discord webhook returned HTTP " + status, null);
        } catch (Exception e) {
            // A dead webhook must not produce a log line every five minutes -
            // that is its own kind of noise. Log the first failure, stay quiet
            // until one succeeds again.
            logFailureOnce("Could not reach the Discord webhook", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void logFailureOnce(String message, Exception e) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        if (e == null) {
            plugin.getLogger().warning(message);
        } else {
            plugin.getLogger().log(Level.WARNING, message, e);
        }
    }
}
