package com.nnois.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;

public class ResourceLoaderHelper {

    private File tempDbFile;

    public Connection loadSqliteFromResources(String resourcePath) throws Exception {
        try (InputStream dbStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (dbStream == null) {
                throw new FileNotFoundException("File database non trovato in resources: " + resourcePath);
            }

            this.tempDbFile = File.createTempFile("omw_multilingual_temp_", ".db");
            this.tempDbFile.deleteOnExit();

            Files.copy(dbStream, this.tempDbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        String dbUrl = "jdbc:sqlite:" + this.tempDbFile.getAbsolutePath();
        return DriverManager.getConnection(dbUrl);
    }

    public Set<String> loadStopWordsFromStream(String resourcePath) throws IOException {
        Set<String> words = new HashSet<>();

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new FileNotFoundException("File stopwords non trovato in resources: " + resourcePath);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim().toLowerCase();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        words.add(line);
                    }
                }
            }
        }

        return words;
    }

    public void cleanupTempDb() {
        if (this.tempDbFile != null && this.tempDbFile.exists()) {
            this.tempDbFile.delete();
        }
    }
}
