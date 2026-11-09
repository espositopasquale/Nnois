package com.nnois;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class OmwDatabaseBuilder {
    private static final String OMW_URL = "https://github.com/omwn/omw-data.git";
    private static final Pattern LANGUAGE_FILE = Pattern.compile("wn-data-([a-z]{2,3})\\.tab$");
    private static final int BATCH_SIZE = 10_000;

    private OmwDatabaseBuilder() { }

    static Path prepareData(Path dataDirectory, Path projectRoot, boolean repair) throws IOException, InterruptedException {
        Path data = dataDirectory.toAbsolutePath().normalize();
        boolean cloned = false;
        if (!Files.exists(data) && !repair) {
            run(List.of("git", "clone", "--depth", "1", "--no-checkout", OMW_URL, data.toString()), projectRoot);
            cloned = true;
        }
        if (cloned || repair) {
            if (!Files.exists(data.resolve(".git"))) {
                throw new IOException("Not a Git checkout: " + data);
            }
            List<String> git = List.of("git", "-c", "core.protectNTFS=false", "-C", data.toString());
            run(append(git, "sparse-checkout", "set", "--no-cone", "/*", "!/wns/wikt/"), projectRoot);
            if (repair) System.out.println("Restoring the failed OMW checkout from HEAD.");
            run(append(git, "read-tree", "-mu", "HEAD"), projectRoot);
        }
        return data;
    }

    static Path buildDatabase(Path dataDirectory, Path projectRoot, Path destination)
            throws IOException, InterruptedException, SQLException {
        Path data = prepareData(dataDirectory, projectRoot, false);
        List<Path> tabFiles;
        try (Stream<Path> paths = Files.walk(data)) {
            tabFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("wn-data-")
                            && path.getFileName().toString().endsWith(".tab"))
                    .sorted().toList();
        }
        if (tabFiles.isEmpty()) {
            throw new IOException("No wn-data-*.tab files found in " + data
                    + ". Select a valid OMW checkout with --data-dir PATH.");
        }

        Files.createDirectories(destination);
        Path staging = Files.createTempDirectory(destination, "omw-build-");
        Path stagedDatabase = staging.resolve("omw_multilingual.db");
        Path stagedAttribution = staging.resolve("WORDNET_SOURCES.md");
        try {
            BuildSummary summary = createDatabase(tabFiles, stagedDatabase);
            validateDatabase(stagedDatabase);
            writeAttribution(stagedAttribution, summary.sources);
            replace(stagedAttribution, destination.resolve("WORDNET_SOURCES.md"));
            replace(stagedDatabase, destination.resolve("omw_multilingual.db"));
            System.out.printf("Database and attribution installed in %s%n", destination);
            System.out.printf("Lemmas: %d; definitions: %d; sources: %d%n",
                    summary.lemmas, summary.definitions, summary.sources.size());
            return destination.resolve("omw_multilingual.db");
        } finally {
            deleteTree(staging);
        }
    }

    private static BuildSummary createDatabase(List<Path> tabFiles, Path database) throws IOException, SQLException {
        List<Source> sources = new ArrayList<>();
        long lemmas = 0;
        long definitions = 0;
        System.out.printf("Found %d .tab files to process.%n", tabFiles.size());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA synchronous = OFF");
                statement.execute("PRAGMA journal_mode = MEMORY");
                statement.execute("CREATE TABLE synset_lemmas (synset_offset TEXT NOT NULL, pos TEXT NOT NULL, synset_id TEXT NOT NULL, lang TEXT NOT NULL, lemma TEXT NOT NULL)");
                statement.execute("CREATE TABLE synset_def (synset_offset TEXT NOT NULL, pos TEXT NOT NULL, synset_id TEXT NOT NULL, lang TEXT NOT NULL, def TEXT NOT NULL)");
                statement.execute("CREATE TABLE sources (lang TEXT NOT NULL, name TEXT, url TEXT, license TEXT, file TEXT NOT NULL)");
            }
            connection.setAutoCommit(false);
            try (PreparedStatement lemmaInsert = connection.prepareStatement(
                         "INSERT INTO synset_lemmas (synset_offset, pos, synset_id, lang, lemma) VALUES (?, ?, ?, ?, ?)");
                 PreparedStatement definitionInsert = connection.prepareStatement(
                         "INSERT INTO synset_def (synset_offset, pos, synset_id, lang, def) VALUES (?, ?, ?, ?, ?)");
                 PreparedStatement sourceInsert = connection.prepareStatement(
                         "INSERT INTO sources (lang, name, url, license, file) VALUES (?, ?, ?, ?, ?)")) {
                int lemmaBatch = 0;
                int definitionBatch = 0;
                for (Path file : tabFiles) {
                    String language = languageFor(file);
                    if (language == null) continue;
                    System.out.printf("Processing language '%s': %s%n", language, file);
                    boolean headerSeen = false;
                    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        String rawLine;
                        while ((rawLine = reader.readLine()) != null) {
                            String line = rawLine.strip();
                            if (line.isEmpty()) continue;
                            if (line.startsWith("#")) {
                                if (!headerSeen) {
                                    headerSeen = true;
                                    sources.add(parseSource(line, language, file.getFileName().toString()));
                                }
                                continue;
                            }
                            String[] fields = line.split("\\t", -1);
                            if (fields.length < 3) continue;
                            String synsetId = fields[0].strip();
                            String keyType = fields[1].strip();
                            int separator = synsetId.indexOf('-');
                            String offset = separator >= 0 ? synsetId.substring(0, separator)
                                    : synsetId.substring(0, synsetId.length() - 1);
                            String pos = separator >= 0 ? synsetId.substring(separator + 1)
                                    : synsetId.substring(synsetId.length() - 1);
                            if (keyType.equals("lemma") || keyType.endsWith(":lemma")) {
                                lemmaInsert.setString(1, offset);
                                lemmaInsert.setString(2, pos);
                                lemmaInsert.setString(3, synsetId);
                                lemmaInsert.setString(4, language);
                                lemmaInsert.setString(5, fields[2].strip());
                                lemmaInsert.addBatch();
                                lemmas++;
                                if (++lemmaBatch == BATCH_SIZE) {
                                    lemmaInsert.executeBatch();
                                    lemmaBatch = 0;
                                }
                            } else if (keyType.equals("def") || keyType.endsWith(":def")) {
                                String definition = fields[fields.length - 1].strip();
                                if (definition.length() > 1) {
                                    definitionInsert.setString(1, offset);
                                    definitionInsert.setString(2, pos);
                                    definitionInsert.setString(3, synsetId);
                                    definitionInsert.setString(4, language);
                                    definitionInsert.setString(5, definition);
                                    definitionInsert.addBatch();
                                    definitions++;
                                    if (++definitionBatch == BATCH_SIZE) {
                                        definitionInsert.executeBatch();
                                        definitionBatch = 0;
                                    }
                                }
                            }
                        }
                    }
                }
                if (lemmaBatch > 0) lemmaInsert.executeBatch();
                if (definitionBatch > 0) definitionInsert.executeBatch();
                sources.sort(Comparator.comparing(Source::language));
                for (Source source : sources) {
                    sourceInsert.setString(1, source.language);
                    sourceInsert.setString(2, source.name);
                    sourceInsert.setString(3, source.url);
                    sourceInsert.setString(4, source.license);
                    sourceInsert.setString(5, source.file);
                    sourceInsert.addBatch();
                }
                sourceInsert.executeBatch();
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE INDEX idx_lemmas_offset_pos_lang ON synset_lemmas (synset_offset, pos, lang)");
                statement.execute("CREATE INDEX idx_lemmas_synset_full ON synset_lemmas (synset_id, lang)");
                statement.execute("CREATE INDEX idx_lemmas_lemma ON synset_lemmas (lemma, lang)");
                statement.execute("CREATE INDEX idx_def_offset_pos_lang ON synset_def (synset_offset, pos, lang)");
                statement.execute("CREATE INDEX idx_def_synset_full ON synset_def (synset_id, lang)");
            }
            connection.commit();
        }
        return new BuildSummary(lemmas, definitions, sources);
    }

    private static String languageFor(Path file) {
        Matcher matcher = LANGUAGE_FILE.matcher(file.getFileName().toString());
        if (!matcher.find()) return null;
        String language = matcher.group(1);
        return language.equals("en") || language.equals("eng") ? "eng" : language;
    }

    private static Source parseSource(String line, String language, String file) {
        String[] parts = line.substring(1).strip().split("\\t", -1);
        return new Source(language, valueAt(parts, 0), valueAt(parts, 2), valueAt(parts, 3), file);
    }

    private static String valueAt(String[] values, int index) {
        return index < values.length ? emptyToNull(values[index].strip()) : null;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static void validateDatabase(Path database) throws SQLException, IOException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
                if (!result.next() || !"ok".equals(result.getString(1))) {
                    throw new IOException("Generated database failed SQLite integrity checking.");
                }
            }
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM synset_lemmas")) {
                if (!result.next() || result.getLong(1) == 0) {
                    throw new IOException("Generated database contains no lemmas.");
                }
            }
        }
    }

    private static void writeAttribution(Path file, List<Source> sources) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("# Wordnet Sources\n\n");
            writer.write("OMW citation: Francis Bond and Ryan Foster (2013). ");
            writer.write("[Linking and Extending an Open Multilingual Wordnet](https://aclanthology.org/P13-1133/). ");
            writer.write("ACL, pages 1352-1362. Also cite the individual wordnets used. ");
            writer.write("[OMW Data](https://github.com/omwn/omw-data).\n\n");
            writer.write("Auto-generated by the Nnois Java launcher. Lists every individual wordnet bundled ");
            writer.write("into omw_multilingual.db, per Open Multilingual Wordnet's attribution terms. ");
            writer.write("See omw-data/CITATION.cff for the overall OMW citation.\n\n");
            writer.write("| Lang | Name | License | URL |\n| --- | --- | --- | --- |\n");
            for (Source source : sources) {
                writer.write("| " + source.language + " | " + empty(source.name) + " | "
                        + empty(source.license) + " | " + empty(source.url) + " |\n");
            }
        }
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    private static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static List<String> append(List<String> prefix, String... suffix) {
        List<String> command = new ArrayList<>(prefix);
        command.addAll(List.of(suffix));
        return command;
    }

    private static void run(List<String> command, Path workingDirectory) throws IOException, InterruptedException {
        System.out.println("Running: " + String.join(" ", command));
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).inheritIO().start();
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new IOException("Command failed with exit code " + exitCode + ": " + command.get(0));
    }

    private record Source(String language, String name, String url, String license, String file) { }
    private record BuildSummary(long lemmas, long definitions, List<Source> sources) { }
}