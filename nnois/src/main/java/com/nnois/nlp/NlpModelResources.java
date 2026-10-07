package com.nnois.nlp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Resolve model names published by the bundled OpenNLP model jars. */
final class NlpModelResources {
    private NlpModelResources() { }
    static InputStream open(String requested, String type) throws IOException {
        ClassLoader loader = NlpModelResources.class.getClassLoader();
        InputStream exact = loader.getResourceAsStream(requested);
        if (exact != null) return exact;
        String language = requested.startsWith("opennlp-") ? requested.split("-")[1]
                : requested.split("-")[0];
        var resources = loader.getResources("model.properties");
        while (resources.hasMoreElements()) {
            Properties properties = new Properties();
            try (InputStream in = resources.nextElement().openStream()) { properties.load(in); }
            String name = properties.getProperty("model.name", "");
            if (language.equals(properties.getProperty("model.language")) && name.contains("-" + type + "-")) {
                return loader.getResourceAsStream(name);
            }
        }
        return null;
    }
}
