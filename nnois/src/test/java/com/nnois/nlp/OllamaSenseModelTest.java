package com.nnois.nlp;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class OllamaSenseModelTest {
    @Test public void batchesContextAndGlossesAndCalculatesCosine() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"embeddings\":[[1,0],[0,1],[2,0]]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var model = new OllamaSenseModel(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/embed"), "test-model");
            assertArrayEquals(new double[] {0, 1}, model.score("river water", List.of("money", "river shore")), 1e-9);
            var json = JsonParser.parseString(request.get()).getAsJsonObject();
            assertEquals("test-model", json.get("model").getAsString());
            assertEquals(3, json.getAsJsonArray("input").size());
            assertFalse(json.get("truncate").getAsBoolean());
        } finally { server.stop(0); }
    }
}
