package ai.pollinations.craft;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client against a local server: request shape, reply parsing, the key
 * header, error messages and image bytes. No Pollinations account involved.
 */
class PollinationsClientTest {

    private static HttpServer server;
    private static String base;
    private static final AtomicReference<String> lastPath = new AtomicReference<>("");
    private static final AtomicReference<String> lastBody = new AtomicReference<>("");
    private static final AtomicReference<String> lastAuth = new AtomicReference<>("");

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastPath.set(exchange.getRequestURI().toString());
            lastAuth.set(exchange.getRequestHeaders().getFirst("authorization"));
            lastBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (lastBody.get().contains("boom")) {
                respond(exchange, 500, "application/json", """
                        {"error":{"message":"insufficient balance"}}
                        """.strip().getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] reply = """
                    {"choices":[{"message":{"role":"assistant","content":" AYE "}}],
                     "usage":{"prompt_tokens":42,"completion_tokens":3}}
                    """.strip().getBytes(StandardCharsets.UTF_8);
            respond(exchange, 200, "application/json", reply);
        });
        server.createContext("/text/models", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("authorization"));
            respond(exchange, 200, "application/json",
                    "[{\"name\":\"openai/gpt-5.4-nano\",\"title\":\"Nano\"}]"
                            .getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/image/", exchange -> {
            lastPath.set(exchange.getRequestURI().toString());
            lastAuth.set(exchange.getRequestHeaders().getFirst("authorization"));
            BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.MAGENTA);
            g.fillRect(0, 0, 32, 32);
            g.dispose();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            respond(exchange, 200, "image/png", bytes.toByteArray());
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, int status, String type, byte[] body)
            throws java.io.IOException {
        exchange.getResponseHeaders().set("content-type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void sendsTheModelHistoryAndKeyAndReadsTheReply() throws Exception {
        PollinationsClient client = new PollinationsClient(base);
        PollinationsClient.ChatReply reply = client.chat(
                "openai/gpt-5.4-nano",
                "pk_test",
                "You are Bjorn.",
                List.of(new String[] {"user", "hello"}, new String[] {"assistant", "hi"}),
                "how are you",
                160);
        assertEquals("AYE", reply.text());
        assertEquals(42, reply.promptTokens());
        assertEquals(3, reply.completionTokens());
        assertEquals("Bearer pk_test", lastAuth.get());
        assertTrue(lastBody.get().contains("\"model\":\"openai/gpt-5.4-nano\""));
        assertTrue(lastBody.get().contains("\"role\":\"system\""));
        assertTrue(lastBody.get().contains("\"content\":\"how are you\""));
        // History arrives in order: the first user line, then the answer.
        int first = lastBody.get().indexOf("hello");
        int second = lastBody.get().indexOf("how are you");
        assertTrue(first < second, "history precedes the new line");
    }

    @Test
    void readsTheModelList() throws Exception {
        PollinationsClient client = new PollinationsClient(base);
        List<ModelInfo> models = ModelCatalog.parse(client.modelList("text", "pk_test"));
        assertEquals(1, models.size());
        assertEquals("openai/gpt-5.4-nano", models.get(0).name());
        assertEquals("Bearer pk_test", lastAuth.get());
    }

    @Test
    void aGatewayErrorBecomesAPlayerReadableMessage() {
        PollinationsClient client = new PollinationsClient(base);
        Exception error = assertThrows(Exception.class, () -> client.chat(
                "openai/gpt-5.4-nano", "pk_test", null, List.of(), "boom", 160));
        assertTrue(error.getMessage() != null && error.getMessage().contains("insufficient balance"),
                "the player sees the gateway's own reason: " + error.getMessage());
        assertTrue(!error.getMessage().contains("Exception"),
                "the message is meant for a player: " + error.getMessage());
    }

    @Test
    void anImageArrivesAsBytesWithTheKeyInTheHeaderOnly() throws Exception {
        PollinationsClient client = new PollinationsClient(base);
        byte[] png = client.image("flux", "pk_test", "a red cube", 512, 512, -1);
        assertTrue(png.length > 100);
        assertTrue(lastPath.get().startsWith("/image/a%20red%20cube?"));
        assertTrue(lastPath.get().contains("width=512"));
        assertEquals("Bearer pk_test", lastAuth.get());
        assertFalse(lastPath.get().contains("pk_test"), "the key never goes in the URL: " + lastPath.get());
    }
}
