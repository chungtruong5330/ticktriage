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
package dev.ticktriage.core.remedy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Combines several protection providers. A location is protected if <em>any</em>
 * of them says so, and also if any of them throws.
 *
 * <p>Swallowing the exception and continuing would be worse than useless here:
 * a WorldGuard query that starts failing would silently turn claim protection
 * off, and the first anyone would know about it is a player asking where their
 * items went.
 */
public final class CompositeProtection implements ProtectionOracle {

    private final List<ProtectionOracle> oracles;

    public CompositeProtection(List<ProtectionOracle> oracles) {
        this.oracles = Collections.unmodifiableList(new ArrayList<>(oracles));
    }

    public static ProtectionOracle of(List<ProtectionOracle> oracles) {
        List<ProtectionOracle> real = new ArrayList<>();
        for (ProtectionOracle o : oracles) {
            if (o != null && o != ProtectionOracle.NONE) {
                real.add(o);
            }
        }
        if (real.isEmpty()) {
            return ProtectionOracle.NONE;
        }
        // A single provider is still wrapped. Returning it bare would skip the
        // try/catch below, so one throwing provider would fail *open* - which
        // is the exact failure this class exists to prevent. The extra
        // indirection is worth the guarantee.
        return new CompositeProtection(real);
    }

    public List<ProtectionOracle> providers() {
        return oracles;
    }

    @Override
    public boolean isProtected(String world, int x, int y, int z) {
        for (ProtectionOracle oracle : oracles) {
            try {
                if (oracle.isProtected(world, x, y, z)) {
                    return true;
                }
            } catch (Throwable t) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (ProtectionOracle o : oracles) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(o.describe());
        }
        return sb.toString();
    }
}
