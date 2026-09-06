package dev.ticktriage.paper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import dev.ticktriage.core.IncidentRecord;

/**
 * Persists the incident log as a tab-separated file in the plugin folder.
 *
 * <p>Plain text on purpose. An owner asking "what happened last night while I
 * was asleep" should be able to answer it with {@code cat}, without the server
 * running and without this plugin's help.
 */
public final class IncidentLogStore {

    private final File file;
    private final int maxRecords;

    public IncidentLogStore(File dataFolder, int maxRecords) {
        this.file = new File(dataFolder, "incidents.tsv");
        this.maxRecords = Math.max(1, maxRecords);
    }

    public File file() {
        return file;
    }

    public List<IncidentRecord> load() throws IOException {
        List<IncidentRecord> out = new ArrayList<>();
        if (!file.exists()) {
            return out;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(),
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                IncidentRecord record = IncidentRecord.decode(line);
                if (record != null) {
                    out.add(record);
                }
            }
        }
        // Keep the newest, drop the rest.
        if (out.size() > maxRecords) {
            out = new ArrayList<>(out.subList(out.size() - maxRecords,
                    out.size()));
        }
        return out;
    }

    public void append(IncidentRecord record) throws IOException {
        ensureFile();
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            writer.write(record.encode());
            writer.newLine();
        }
    }

    /** Rewrites the whole file, used to trim it back to the cap on shutdown. */
    public void rewrite(List<IncidentRecord> records) throws IOException {
        ensureFile();
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("# TickTriage incident log."
                    + " start\tseconds\tworstTps\tplayers\trule\tseverity"
                    + "\theadline\tremoved");
            writer.newLine();
            for (IncidentRecord record : records) {
                writer.write(record.encode());
                writer.newLine();
            }
        }
    }

    private void ensureFile() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
    }
}
