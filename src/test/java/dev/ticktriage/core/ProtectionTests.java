/*
 * TickTriage - finds out why a Minecraft server lagged, then fixes it surgically.
 * Copyright (C) 2026 slateline
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. It is distributed WITHOUT ANY WARRANTY; see the GNU General Public
 * License for details: <https://www.gnu.org/licenses/>.
 */
package dev.ticktriage.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.ticktriage.core.remedy.CompositeProtection;
import dev.ticktriage.core.remedy.ConfiguredProtection;
import dev.ticktriage.core.remedy.EntityFacts;
import dev.ticktriage.core.remedy.ProtectionOracle;
import dev.ticktriage.core.remedy.RemovalFilter;
import dev.ticktriage.core.remedy.SafetyPolicy;

/**
 * Tests for claim protection.
 *
 * <p>{@code java dev.ticktriage.core.ProtectionTests}
 *
 * <p>The behaviour these pin down is <b>failing closed</b>. A protection
 * provider that throws must make the location protected, not unprotected. Get
 * that backwards and a broken WorldGuard hook silently turns claim checking off
 * — and nobody finds out until a player asks where their base went.
 */
public final class ProtectionTests {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failures.add(name);
            System.out.println("  FAIL  " + name + "  " + detail);
        }
    }

    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static EntityFacts oldItem(int x, int y, int z) {
        return new EntityFacts("ITEM", x, y, z, 100000, 0);
    }

    /** A provider that blows up, standing in for a broken WorldGuard hook. */
    private static final ProtectionOracle EXPLODING = new ProtectionOracle() {
        @Override
        public boolean isProtected(String world, int x, int y, int z) {
            throw new IllegalStateException("region container went away");
        }

        @Override
        public String describe() {
            return "exploding";
        }
    };

    // --- built-ins --------------------------------------------------------

    static void testNoneProtectsNothing() {
        check("NONE protects nothing",
                !ProtectionOracle.NONE.isProtected("world", 0, 64, 0));
    }

    static void testAllProtectsEverything() {
        check("ALL protects everything",
                ProtectionOracle.ALL.isProtected("world", 12345, 64, -999));
    }

    // --- configured protection -------------------------------------------

    static void testProtectedWorldIsEntirelyOffLimits() {
        ProtectionOracle o = ConfiguredProtection.of(set("creative"),
                new ArrayList<ConfiguredProtection.Box>());
        check("every location in a protected world is off limits",
                o.isProtected("creative", 0, 0, 0)
                        && o.isProtected("creative", 99999, 300, -99999));
        check("other worlds are unaffected",
                !o.isProtected("world", 0, 64, 0));
    }

    static void testWorldMatchIsCaseInsensitive() {
        ProtectionOracle o = ConfiguredProtection.of(set("Creative"),
                new ArrayList<ConfiguredProtection.Box>());
        check("world names match regardless of case",
                o.isProtected("cReAtIvE", 0, 64, 0));
    }

    static void testBoxBoundsAreInclusive() {
        ConfiguredProtection.Box box =
                new ConfiguredProtection.Box("world", -100, 0, -100, 100, 128, 100);
        ProtectionOracle o = ConfiguredProtection.of(set(),
                Arrays.asList(box));
        check("a point inside the box is protected",
                o.isProtected("world", 0, 64, 0));
        check("both corners are inclusive",
                o.isProtected("world", -100, 0, -100)
                        && o.isProtected("world", 100, 128, 100));
        check("just outside the box is not protected",
                !o.isProtected("world", 101, 64, 0)
                        && !o.isProtected("world", 0, 129, 0)
                        && !o.isProtected("world", 0, 64, -101));
        check("the same coordinates in another world are not protected",
                !o.isProtected("world_nether", 0, 64, 0));
    }

    static void testBoxCornersMayBeGivenInAnyOrder() {
        // An admin writing to: before from: should still get a valid box.
        ConfiguredProtection.Box box =
                new ConfiguredProtection.Box("world", 100, 128, 100, -100, 0, -100);
        check("reversed corners still describe the same box",
                box.contains("world", 0, 64, 0) && box.minX == -100
                        && box.maxY == 128);
    }

    static void testEmptyConfigurationIsNone() {
        check("empty configuration collapses to NONE",
                ConfiguredProtection.of(set(),
                        new ArrayList<ConfiguredProtection.Box>())
                        == ProtectionOracle.NONE);
    }

    // --- composite --------------------------------------------------------

    static void testCompositeIsProtectedIfAnyProviderSaysSo() {
        ProtectionOracle o = CompositeProtection.of(Arrays.asList(
                ConfiguredProtection.of(set("creative"),
                        new ArrayList<ConfiguredProtection.Box>()),
                ProtectionOracle.NONE));
        check("any provider claiming a location is enough",
                o.isProtected("creative", 0, 64, 0));
        check("no provider claiming it leaves it clear",
                !o.isProtected("world", 0, 64, 0));
    }

    static void testCompositeFailsClosedWhenAProviderThrows() {
        ProtectionOracle o = CompositeProtection.of(Arrays.asList(
                EXPLODING, ProtectionOracle.NONE));
        check("a throwing provider makes the location protected",
                o.isProtected("world", 0, 64, 0),
                "failing open would silently disable claim protection");
    }

    static void testCompositeCollapsesTrivialCases() {
        check("an empty composite is NONE",
                CompositeProtection.of(new ArrayList<ProtectionOracle>())
                        == ProtectionOracle.NONE);
        ProtectionOracle single = ConfiguredProtection.of(set("creative"),
                new ArrayList<ConfiguredProtection.Box>());
        // Deliberately NOT unwrapped: the wrapper is what contains a throwing
        // provider, so returning the bare provider would fail open.
        ProtectionOracle wrapped = CompositeProtection.of(Arrays.asList(single));
        check("a single provider is still wrapped so errors stay contained",
                wrapped != single && wrapped instanceof CompositeProtection);
        check("the wrapper still delegates correctly",
                wrapped.isProtected("creative", 0, 64, 0)
                        && !wrapped.isProtected("world", 0, 64, 0));
        check("NONE entries are dropped from a composite",
                ((CompositeProtection) CompositeProtection.of(
                        Arrays.asList(ProtectionOracle.NONE, single)))
                        .providers().size() == 1);
    }

    // --- the combined filter ---------------------------------------------

    static void testFilterBlocksInsideClaims() {
        RemovalFilter filter = new RemovalFilter(SafetyPolicy.defaults(),
                ConfiguredProtection.of(set(),
                        Arrays.asList(new ConfiguredProtection.Box(
                                "world", 0, 0, 0, 100, 128, 100))));
        SafetyPolicy.Decision inside =
                filter.evaluate("world", oldItem(50, 64, 50));
        SafetyPolicy.Decision outside =
                filter.evaluate("world", oldItem(500, 64, 500));
        check("an otherwise-removable item inside a claim is spared",
                !inside.allowed && inside.reason.contains("protected region"),
                String.valueOf(inside.reason));
        check("the same item outside the claim is removable", outside.allowed,
                String.valueOf(outside.reason));
    }

    static void testEntityRulesStillApplyOutsideClaims() {
        RemovalFilter filter = new RemovalFilter(SafetyPolicy.defaults(),
                ProtectionOracle.NONE);
        SafetyPolicy.Decision d = filter.evaluate("world",
                new EntityFacts("ITEM", 0, 64, 0, 100000, EntityFacts.NAMED));
        check("entity protections are not bypassed by an unclaimed location",
                !d.allowed && d.reason.contains("custom name"),
                String.valueOf(d.reason));
    }

    static void testFilterFailsClosedOnProtectionErrors() {
        RemovalFilter filter = new RemovalFilter(SafetyPolicy.defaults(),
                EXPLODING);
        SafetyPolicy.Decision d = filter.evaluate("world", oldItem(0, 64, 0));
        check("a protection error spares the entity", !d.allowed,
                String.valueOf(d.reason));
    }

    static void testCheapChecksRunBeforeClaimLookups() {
        // A claim lookup can be expensive; a fresh item must be rejected on age
        // before the oracle is ever consulted.
        final int[] calls = {0};
        ProtectionOracle counting = new ProtectionOracle() {
            @Override
            public boolean isProtected(String world, int x, int y, int z) {
                calls[0]++;
                return false;
            }
        };
        RemovalFilter filter =
                new RemovalFilter(SafetyPolicy.defaults(), counting);
        filter.evaluate("world", new EntityFacts("ITEM", 0, 64, 0, 5, 0));
        check("a cheap rejection skips the claim lookup entirely",
                calls[0] == 0, "oracle was called " + calls[0] + " times");
        filter.evaluate("world", oldItem(0, 64, 0));
        check("a surviving candidate does get a claim lookup", calls[0] == 1,
                "oracle was called " + calls[0] + " times");
    }

    static void testNullProtectionDegradesToNone() {
        RemovalFilter filter = new RemovalFilter(SafetyPolicy.defaults(), null);
        check("a null oracle is treated as no protection, not a crash",
                filter.evaluate("world", oldItem(0, 64, 0)).allowed);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\nClaim protection tests\n"
                + "----------------------------------------------------");
        List<java.lang.reflect.Method> tests = new ArrayList<>();
        for (java.lang.reflect.Method m
                : ProtectionTests.class.getDeclaredMethods()) {
            if (m.getName().startsWith("test") && m.getParameterCount() == 0) {
                tests.add(m);
            }
        }
        tests.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (java.lang.reflect.Method m : tests) {
            try {
                m.setAccessible(true);
                m.invoke(null);
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                failures.add(m.getName());
                System.out.println("  ERROR " + m.getName() + ": " + cause);
            }
        }
        System.out.println("----------------------------------------------------");
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            System.out.println("failed: " + String.join(", ", failures));
            System.exit(1);
        }
    }
}
