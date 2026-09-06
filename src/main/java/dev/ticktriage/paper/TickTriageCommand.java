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
package dev.ticktriage.paper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import dev.ticktriage.core.Diagnosis;
import dev.ticktriage.core.IncidentLog;
import dev.ticktriage.core.IncidentRecord;
import dev.ticktriage.core.Report;
import dev.ticktriage.core.Stats;
import dev.ticktriage.core.remedy.RemediationAction;
import dev.ticktriage.core.remedy.RemediationPlan;
import dev.ticktriage.core.remedy.RemediationResult;

/** {@code /ticktriage [report|status|fix|undo|reset]}. */
public final class TickTriageCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            Arrays.asList("report", "status", "history", "fix", "undo",
                    "reset", "help");

    private final TickTriagePlugin plugin;

    public TickTriageCommand(TickTriagePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        String sub = args.length == 0 ? "report" : args[0].toLowerCase();

        switch (sub) {
            case "report":
                sendReport(sender);
                return true;
            case "status":
                sendStatus(sender);
                return true;
            case "history":
                sendHistory(sender, args);
                return true;
            case "help":
                sendHelp(sender, label);
                return true;
            case "fix":
                doFix(sender, args, label);
                return true;
            case "undo":
                doUndo(sender, args);
                return true;
            case "reset":
                if (!sender.hasPermission("ticktriage.admin")) {
                    sender.sendMessage("You need ticktriage.admin for that.");
                    return true;
                }
                plugin.resetHistory();
                sender.sendMessage("History cleared. Re-learning what normal"
                        + " looks like.");
                return true;
            default:
                sendHelp(sender, label);
                return true;
        }
    }

    private void sendReport(CommandSender sender) {
        Report report = plugin.analyse();
        if (report.healthy) {
            sender.sendMessage(report.render());
            return;
        }

        if (report.incident == null) {
            // A standing problem - the baseline itself - with no spike to name.
            sender.sendMessage(report.render().split("\n")[0]);
        } else {
            sender.sendMessage("Lag incident: "
                    + report.incident.describeImpact() + " for "
                    + Stats.formatDouble(report.incident.durationSeconds(), 0)
                    + "s");
        }

        for (Diagnosis d : report.diagnoses) {
            sender.sendMessage("");
            sender.sendMessage("[" + d.severity + "] " + d.headline
                    + " (" + d.confidencePercent() + "%)");
            for (String line : d.evidence) {
                sender.sendMessage("  - " + line);
            }
            sender.sendMessage("  Fix: " + d.suggestedFix);
        }

        if (plugin.isRemediationEnabled()
                && plugin.currentPlan().hasExecutableActions()) {
            sender.sendMessage("");
            sender.sendMessage("A targeted fix is available - /ticktriage fix");
        }
    }

    private void sendStatus(CommandSender sender) {
        int samples = plugin.history().size();
        sender.sendMessage("Samples collected: " + samples);
        if (samples < 30) {
            sender.sendMessage("Still learning - diagnosis starts at 30"
                    + " samples.");
            return;
        }
        sender.sendMessage(plugin.analyse().render().split("\n")[0]);
        sender.sendMessage("Platform: " + plugin.platform().name()
                + " - " + plugin.samplingDescription());
        sender.sendMessage("Claim protection: " + plugin.protectionDescription());
        sender.sendMessage("Remediation: "
                + (!plugin.isRemediationEnabled() ? "disabled"
                        : (plugin.isAutoApply() ? "automatic"
                                : "manual (/ticktriage fix)")));
        if (plugin.isDiscordEnabled()) {
            sender.sendMessage("Discord alerts: on");
        }
    }

    private void sendHistory(CommandSender sender, String[] args) {
        IncidentLog log = plugin.incidentLog();
        int limit = 10;
        if (args.length > 1) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                sender.sendMessage("Usage: /ticktriage history [count]");
                return;
            }
        }

        long now = System.currentTimeMillis();
        sender.sendMessage(log.summarise(now - 86_400_000L).render("24 hours"));

        List<IncidentRecord> recent = log.recent(limit);
        if (recent.isEmpty()) {
            sender.sendMessage("No incidents recorded yet.");
            return;
        }
        sender.sendMessage("");
        for (IncidentRecord record : recent) {
            sender.sendMessage("  " + record.summarise(now));
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("TickTriage - finds out why the server lagged, then"
                + " fixes it surgically.");
        sender.sendMessage("  /" + label + " report      diagnosis of the last"
                + " incident");
        sender.sendMessage("  /" + label + " status      health plus current"
                + " settings");
        sender.sendMessage("  /" + label + " history [n] past incidents");
        sender.sendMessage("  /" + label + " fix         show the plan and"
                + " dry-run it");
        sender.sendMessage("  /" + label + " fix confirm apply it");
        sender.sendMessage("  /" + label + " undo [id]   put back what a fix"
                + " removed");
        sender.sendMessage("  /" + label + " reset       forget the baseline and"
                + " relearn");
    }

    private void doFix(CommandSender sender, String[] args, String label) {
        if (!plugin.isRemediationEnabled()) {
            sender.sendMessage("Remediation is disabled in config.yml.");
            return;
        }

        RemediationPlan plan = plugin.currentPlan();
        if (plan.isEmpty()) {
            sender.sendMessage("Nothing to fix - no incident with an"
                    + " actionable cause.");
            return;
        }

        for (String line : plan.render().split("\n")) {
            sender.sendMessage(line);
        }

        if (!plan.hasExecutableActions()) {
            return;
        }

        boolean confirm = args.length > 1 && "confirm".equalsIgnoreCase(args[1]);
        if (confirm && !sender.hasPermission("ticktriage.fix")) {
            sender.sendMessage("You need ticktriage.fix to apply changes.");
            return;
        }

        // Dry run unless explicitly confirmed. Destroying things must always be
        // something a human typed on purpose.
        final boolean dryRun = !confirm;
        plugin.executor().execute(plan, dryRun, result -> {
            sender.sendMessage("");
            for (String line : result.render().split("\n")) {
                sender.sendMessage(line);
            }
            if (dryRun) {
                sender.sendMessage("  (/" + label + " fix confirm)");
            }
        });
    }

    private void doUndo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ticktriage.fix")) {
            sender.sendMessage("You need ticktriage.fix to undo.");
            return;
        }

        UndoStore store = plugin.undoStore();
        UndoStore.Operation op = args.length > 1
                ? store.byId(args[1]) : store.latest();

        if (op == null) {
            List<UndoStore.Operation> all = store.all();
            if (all.isEmpty()) {
                sender.sendMessage("Nothing to undo. Undo history is in memory"
                        + " only and does not survive a restart.");
            } else {
                sender.sendMessage("No such operation. Available:");
                for (UndoStore.Operation o : all) {
                    sender.sendMessage("  " + o.id + " - "
                            + Stats.formatCount(o.restorable())
                            + " items, " + o.ageSeconds() + "s ago");
                }
            }
            return;
        }

        final UndoStore.Operation operation = op;
        store.restore(plugin.platform(), operation, restored -> {
            if (restored < 0) {
                sender.sendMessage("World '" + operation.world
                        + "' is not loaded.");
                return;
            }
            sender.sendMessage("Restored " + Stats.formatCount(restored)
                    + " items from " + operation.id + ".");
            if (operation.notRestorable() > 0) {
                sender.sendMessage(Stats.formatCount(operation.notRestorable())
                        + " removals were past the undo cap and are gone.");
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBCOMMANDS) {
                if (s.startsWith(args[0].toLowerCase())) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && "fix".equalsIgnoreCase(args[0])) {
            out.add("confirm");
        } else if (args.length == 2 && "undo".equalsIgnoreCase(args[0])) {
            for (UndoStore.Operation o : plugin.undoStore().all()) {
                out.add(o.id);
            }
        }
        return out;
    }
}
