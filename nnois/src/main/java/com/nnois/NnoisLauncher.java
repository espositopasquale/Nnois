package com.nnois;

import com.nnois.utils.ResourceLoaderHelper;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class NnoisLauncher {
    private static final Set<String> COMMANDS = Set.of("run", "demo", "build-db", "setup", "repair-data");
    private static final String DATABASE = "omw/omw_multilingual.db";
    private static final String ATTRIBUTION = "omw/WORDNET_SOURCES.md";

    private NnoisLauncher() { }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (Exception error) {
            System.err.println("Nnois helper: " + error.getMessage());
            exitCode = 1;
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args) throws Exception {
        Options options = parse(args);
        if (options.command.equals("help")) return 0;
        Path projectRoot = findProjectRoot();
        Path dataDirectory = options.dataDirectory == null
                ? projectRoot.resolve("omw-data") : options.dataDirectory;

        if (options.command.equals("repair-data")) {
            OmwDatabaseBuilder.prepareData(dataDirectory, projectRoot, true);
            return 0;
        }
        if (options.command.equals("build-db") || options.command.equals("setup")) {
            buildDatabase(dataDirectory, projectRoot);
            if (options.command.equals("build-db")) return 0;
        } else if (!hasInstalledDatabase()) {
            buildDatabase(dataDirectory, projectRoot);
        }

        List<String> appArgs = new ArrayList<>();
        if (options.command.equals("demo")) {
            appArgs.add("--demo");
            if (options.language != null) appArgs.add(options.language);
        }
        App.main(appArgs.toArray(String[]::new));
        return 0;
    }

    private static void buildDatabase(Path dataDirectory, Path projectRoot) throws Exception {
        Path destination = ResourceLoaderHelper.dataRoot().resolve("omw");
        OmwDatabaseBuilder.buildDatabase(dataDirectory, projectRoot, destination);
    }

    private static boolean hasInstalledDatabase() {
        return ResourceLoaderHelper.resourceExists(DATABASE)
                && ResourceLoaderHelper.resourceExists(ATTRIBUTION);
    }

    private static Options parse(String[] args) {
        String command = "run";
        boolean commandSeen = false;
        Path dataDirectory = null;
        String language = null;

        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--help", "-h" -> {
                    printUsage();
                    return new Options("help", null, null);
                }
                case "--data-dir" -> {
                    dataDirectory = Path.of(requireValue(args, ++index, argument)).toAbsolutePath().normalize();
                }
                case "--language" -> language = requireValue(args, ++index, argument);
                case "--maven" -> {
                    requireValue(args, ++index, argument);
                    System.out.println("Ignoring --maven: the Java launcher does not require Maven.");
                }
                case "--fetch-data" -> { }
                default -> {
                    if (!COMMANDS.contains(argument) || commandSeen) {
                        throw new IllegalArgumentException("Unknown argument or duplicate command: " + argument);
                    }
                    command = argument;
                    commandSeen = true;
                }
            }
        }
        if (language != null && !command.equals("demo")) {
            throw new IllegalArgumentException("--language is only available with demo");
        }
        return new Options(command, dataDirectory, language);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static Path findProjectRoot() {
        List<Path> starts = new ArrayList<>();
        starts.add(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
        try {
            Path location = Path.of(NnoisLauncher.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            starts.add(Files.isDirectory(location) ? location : location.getParent());
        } catch (URISyntaxException | NullPointerException ignored) {
            // Running from an unusual class loader; the working directory remains a useful fallback.
        }
        for (Path start : starts) {
            for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
                if (Files.isRegularFile(candidate.resolve("omw-database/create_db.py"))) return candidate;
            }
        }
        return starts.get(0);
    }

    private static void printUsage() {
        System.out.println("Usage: nnois [run|demo|build-db|setup|repair-data] [options]");
        System.out.println("  --data-dir PATH   OMW data checkout (default: <project>/omw-data)");
        System.out.println("  --language CODE   Demo language: en, it, fr, es, pt, or nl");
        System.out.println("  --help            Show this help");
    }

    private record Options(String command, Path dataDirectory, String language) { }
}
