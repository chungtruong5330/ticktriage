/*
 * TickTriage - finds out why a Minecraft server lagged, then fixes it surgically.
 * Copyright (C) 2026 chungtruong5330
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. It is distributed WITHOUT ANY WARRANTY; see the GNU General Public
 * License for details: <https://www.gnu.org/licenses/>.
 */
package dev.ticktriage.core;

import java.net.URI;
import java.net.URL;
import java.util.Locale;

/**
 * Validates the one URL this plugin will ever send a request to.
 *
 * <p>The plugin posts whatever an admin puts in config.yml, so the destination
 * is restricted to Discord's own webhook endpoints over HTTPS. Without that, a
 * typo - or a hostile config bundled into somebody's "server pack" - turns a
 * lag plugin into a way to fire requests at arbitrary hosts from inside the
 * server's network.
 *
 * <p>Host matching is exact or a proper subdomain. A naive
 * {@code contains("discord.com")} would happily accept
 * {@code https://discord.com.evil.example/}.
 */
public final class WebhookUrl {

    private WebhookUrl() {
    }

    public static boolean isDiscordWebhook(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        try {
            URL url = URI.create(raw.trim()).toURL();
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                return false;
            }
            if (url.getUserInfo() != null) {
                // user:pass@host is a classic way to make a URL read as one
                // host while resolving to another.
                return false;
            }
            String host = url.getHost() == null ? ""
                    : url.getHost().toLowerCase(Locale.ROOT);
            boolean allowed = host.equals("discord.com")
                    || host.equals("discordapp.com")
                    || host.endsWith(".discord.com")
                    || host.endsWith(".discordapp.com");
            String path = url.getPath() == null ? "" : url.getPath();
            return allowed && path.startsWith("/api/webhooks/");
        } catch (Exception e) {
            return false;
        }
    }
}
