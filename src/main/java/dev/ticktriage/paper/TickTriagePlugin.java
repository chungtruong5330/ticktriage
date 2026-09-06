package dev.ticktriage.paper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.plugin.java.JavaPlugin;

import dev.ticktriage.core.Diagnosis;
import dev.ticktriage.core.DiagnosisEngine;
import dev.ticktriage.core.History;
import dev.ticktriage.core.IncidentLog;
import dev.ticktriage.core.IncidentRecord;
import dev.ticktriage.core.Report;
import dev.ticktriage.core.remedy.ConfiguredProtection;
import dev.ticktriage.core.remedy.ProtectionOracle;
import dev.ticktriage.core.remedy.RemediationPlan;
import dev.ticktriage.core.remedy.RemediationPlanner;
import dev.ticktriage.core.remedy.RemovalFilter;
import dev.ticktriage.core.remedy.SafetyPolicy;

/**
 * Entry point. Samples the server on a timer, keeps a rolling history, and
 * shouts only when something is genuinely wrong.
 *
 * <p>Runs on Paper and Folia. Everything that touches world state goes through
 * {@link Platform}, which aims it at the thread allowed to do so.
 */
public final class TickTriagePlugin extends JavaPlugin {

    private Platform platform;
    private History history;
    private SnapshotSource sampler;
    private DiagnosisEngine engine;
    private RemediationPlanner planner;
    private RemediationExecutor executor;
    private UndoStore undoStore;
    private ProtectionOracle protection;
    private IncidentLog incidentLog;
    private IncidentLogStore incidentStore;
    private DiscordNotifier discord;

    private long lastAlertAtMillis = 0L;
    private long alertCooldownMillis;
    private boolean alertsEnabled;
    private boolean remediationEnabled;
    private boolean autoApply;
    private String serverName;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        platform = Platforms.detect(this);

        int intervalTicks = getConfig().getInt("sample-interval-ticks", 40);
        int historySize = getConfig().getInt("history-samples", 3600);
        int blockCensusEvery = getConfig().getInt("block-census-every-n-samples", 15);
        serverName = getConfig().getString("server-name", "");
        alertsEnabled = getConfig().getBoolean("alerts.enabled", true);
        alertCooldownMillis =
                getConfig().getLong("alerts.cooldown-seconds", 300L) * 1000L;

        remediationEnabled = getConfig().getBoolean("remediation.enabled", true);
        autoApply = getConfig().getBoolean("remediation.auto-apply", false);
        int radius = getConfig().getInt("remediation.radius-chunks",
                RemediationPlanner.DEFAULT_RADIUS_CHUNKS);
        long minAgeTicks =
                getConfig().getLong("remediation.min-entity-age-seconds", 60L) * 20L;

        SafetyPolicy policy = SafetyPolicy.defaults().withMinAgeTicks(minAgeTicks);
        List<String> configuredTypes =
                getConfig().getStringList("remediation.removable-types");
        if (!configuredTypes.isEmpty()) {
            Set<String> types = new LinkedHashSet<>();
            for (String t : configuredTypes) {
                types.add(t.toUpperCase());
            }
            policy = policy.withRemovableTypes(types);
        }

        history = new History(historySize);
        sampler = platform.isFolia()
                ? new FoliaSampler(platform, blockCensusEvery,
                        getConfig().getInt("folia.scan-radius-chunks",
                                FoliaSampler.DEFAULT_SCAN_RADIUS))
                : new ServerSampler(blockCensusEvery);
        engine = new DiagnosisEngine();
        planner = new RemediationPlanner(policy, radius);
        undoStore = new UndoStore();
        protection = ProtectionProviders.build(getLogger(),
                configuredProtection(),
                getConfig().getBoolean("remediation.respect-claims.worldguard",
                        true),
                getConfig().getBoolean(
                        "remediation.respect-claims.griefprevention", true));
        executor = new RemediationExecutor(new RemovalFilter(policy, protection),
                undoStore, platform);

        setUpIncidentLog();
        discord = DiscordNotifier.create(this,
                getConfig().getString("alerts.discord-webhook", ""));

        platform.runRepeating(this::takeSample, intervalTicks, intervalTicks);
        // Analysis is far cheaper than sampling, but there is no reason to run
        // it every sample - a spike is still there thirty seconds later.
        platform.runRepeating(this::checkForAlerts, 600L, 600L);

        TickTriageCommand command = new TickTriageCommand(this);
        if (getCommand("ticktriage") != null) {
            getCommand("ticktriage").setExecutor(command);
            getCommand("ticktriage").setTabCompleter(command);
        }

        getLogger().info("Running on " + platform.name() + " - "
                + sampler.describe() + ".");
        getLogger().info("Give it a few minutes to learn what normal looks like"
                + " on this server.");
        if (autoApply) {
            getLogger().warning("remediation.auto-apply is ON - fixes will be"
                    + " applied without confirmation.");
        }
    }

    @Override
    public void onDisable() {
        if (platform != null) {
            platform.cancelAll();
        }
        // Rewrite rather than append, so the file is trimmed to the cap.
        if (incidentStore != null && incidentLog != null) {
            try {
                incidentStore.rewrite(incidentLog.all());
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not save the incident log",
                        e);
            }
        }
    }

    private void setUpIncidentLog() {
        int capacity = getConfig().getInt("incident-log.keep",
                IncidentLog.DEFAULT_CAPACITY);
        incidentLog = new IncidentLog(capacity);
        if (!getConfig().getBoolean("incident-log.enabled", true)) {
            return;
        }
        incidentStore = new IncidentLogStore(getDataFolder(), capacity);
        try {
            List<IncidentRecord> loaded = incidentStore.load();
            incidentLog.restore(loaded);
            if (!loaded.isEmpty()) {
                getLogger().info("Loaded " + loaded.size()
                        + " past incidents from incidents.tsv");
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not read the incident log;"
                    + " starting a fresh one", e);
        }
    }

    private void takeSample() {
        try {
            sampler.sample(history::add);
        } catch (Throwable t) {
            // Never let a sampling failure kill the repeating task - a plugin
            // that silently stops watching is worse than one that logs.
            getLogger().log(Level.WARNING, "Sampling failed", t);
        }
    }

    private void checkForAlerts() {
        if (!alertsEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAlertAtMillis < alertCooldownMillis) {
            return;
        }

        final Report report = analyse();
        final Diagnosis primary = report.primary();
        if (report.healthy || primary == null
                || primary.severity != Diagnosis.Severity.CRITICAL) {
            return;
        }

        lastAlertAtMillis = now;
        final boolean unseen = incidentLog.isNew(report.incident);

        getLogger().warning("Lag incident detected:");
        for (String line : primary.render().split("\n")) {
            getLogger().warning(line);
        }

        RemediationPlan plan = remediationEnabled
                ? planner.planFor(report) : RemediationPlan.empty();

        if (plan.hasExecutableActions() && autoApply) {
            // Remediation is asynchronous on Folia, so the incident is recorded
            // in the callback, with its real removal count rather than a zero
            // that has to be patched up afterwards.
            try {
                executor.execute(plan, false, result -> {
                    getLogger().warning("Auto-applied fix:");
                    for (String line : result.render().split("\n")) {
                        getLogger().warning(line);
                    }
                    if (unseen) {
                        recordIncident(report, primary, result.affected);
                    }
                });
                return;
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Auto-remediation failed", t);
            }
        } else if (plan.hasExecutableActions()) {
            getLogger().warning("A targeted fix is available. Review it with"
                    + " /ticktriage fix");
        }

        if (unseen) {
            recordIncident(report, primary, 0);
        }
    }

    private void recordIncident(Report report, Diagnosis primary, int removed) {
        IncidentRecord record =
                IncidentRecord.from(report.incident, primary, removed);
        if (incidentLog.record(record) && incidentStore != null) {
            try {
                incidentStore.append(record);
            } catch (IOException e) {
                getLogger().log(Level.WARNING,
                        "Could not append to the incident log", e);
            }
        }
        notifyDiscord(primary, removed);
    }

    private void notifyDiscord(Diagnosis primary, int removed) {
        if (discord == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("**Lag incident**");
        if (serverName != null && !serverName.isEmpty()) {
            sb.append(" on `").append(serverName).append("`");
        }
        sb.append("\n```\n").append(primary.render()).append("\n```");
        if (removed > 0) {
            sb.append("\nAuto-applied fix removed ").append(removed)
                    .append(" entities.");
        }
        discord.send(sb.toString());
    }

    /**
     * Protection an admin declared directly in config.yml, for servers that
     * guard spawn by convention rather than with a claim plugin.
     */
    private ProtectionOracle configuredProtection() {
        Set<String> worlds = new LinkedHashSet<>(
                getConfig().getStringList("remediation.protected-worlds"));
        List<ConfiguredProtection.Box> boxes = new ArrayList<>();
        for (Map<?, ?> raw
                : getConfig().getMapList("remediation.protected-regions")) {
            try {
                List<?> from = (List<?>) raw.get("from");
                List<?> to = (List<?>) raw.get("to");
                boxes.add(new ConfiguredProtection.Box(
                        String.valueOf(raw.get("world")),
                        intAt(from, 0), intAt(from, 1), intAt(from, 2),
                        intAt(to, 0), intAt(to, 1), intAt(to, 2)));
            } catch (Throwable t) {
                // One bad entry must not take the rest of the config with it,
                // but silently ignoring it would be worse than saying so.
                getLogger().warning("Ignoring malformed"
                        + " remediation.protected-regions entry: " + raw);
            }
        }
        return ConfiguredProtection.of(worlds, boxes);
    }

    private static int intAt(List<?> list, int index) {
        return ((Number) list.get(index)).intValue();
    }

    public Platform platform() {
        return platform;
    }

    public String samplingDescription() {
        return sampler == null ? "not started" : sampler.describe();
    }

    public String protectionDescription() {
        return protection == null ? "none" : protection.describe();
    }

    public Report analyse() {
        return engine.analyse(history);
    }

    public RemediationPlan currentPlan() {
        return planner.planFor(analyse());
    }

    public RemediationExecutor executor() {
        return executor;
    }

    public UndoStore undoStore() {
        return undoStore;
    }

    public IncidentLog incidentLog() {
        return incidentLog;
    }

    public boolean isRemediationEnabled() {
        return remediationEnabled;
    }

    public boolean isAutoApply() {
        return autoApply;
    }

    public boolean isDiscordEnabled() {
        return discord != null;
    }

    public History history() {
        return history;
    }

    public void resetHistory() {
        history.clear();
        lastAlertAtMillis = 0L;
    }
}
