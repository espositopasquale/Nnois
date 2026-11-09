package com.nnois;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.Test;

public class OmwDatabaseBuilderTest {
    @Test
    public void buildsSchemaAndAttributionFromOwmFiles() throws Exception {
        Path root = Files.createTempDirectory("nnois-omw-test-");
        try {
            Path data = root.resolve("data/wns/eng");
            Files.createDirectories(data);
            Files.writeString(data.resolve("wn-data-eng.tab"),
                    "# English WordNet\t1\thttps://example.org\tCC0\n"
                    + "00001740-n\teng:lemma\ttree\n"
                    + "00001740-n\teng:def\ta woody perennial plant\n");
            Path destination = root.resolve("output/omw");

            Path database = OmwDatabaseBuilder.buildDatabase(root.resolve("data"), root, destination);

            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT l.lang, l.lemma, d.def FROM synset_lemmas l JOIN synset_def d ON d.synset_id = l.synset_id")) {
                assertTrue(result.next());
                assertEquals("eng", result.getString("lang"));
                assertEquals("tree", result.getString("lemma"));
                assertEquals("a woody perennial plant", result.getString("def"));
            }
            assertTrue(Files.readString(destination.resolve("WORDNET_SOURCES.md")).contains("CC0"));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}