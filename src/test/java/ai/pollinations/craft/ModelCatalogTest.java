package ai.pollinations.craft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCatalogTest {

    private static final String TEXT_LIST = """
            [
              {"name": "openai/gpt-5.4-nano", "title": "GPT-5.4 Nano",
               "category": "text", "pricing": {"currency": "pollen",
                 "promptTextTokens": "0.00000015", "completionTextTokens": "0.0000009375"}},
              {"name": "tongyi-mai/z-image-turbo", "title": "Z-Image Turbo",
               "category": "image", "pricing": {"completionImageTokens": "0.004"}}
            ]""";

    @Test
    void parsesBothKindsOfPricing() {
        List<ModelInfo> models = ModelCatalog.parse(TEXT_LIST);
        assertEquals(2, models.size());
        assertEquals("openai/gpt-5.4-nano", models.get(0).name());
        assertEquals(0.00000015, models.get(0).promptTextTokens(), 1e-12);
        assertEquals(0.004, models.get(1).completionImageTokens(), 1e-12);
        assertEquals(-1, models.get(1).promptTextTokens());
    }

    @Test
    void pricesAChatTurnInPollen() {
        List<ModelInfo> models = ModelCatalog.parse(TEXT_LIST);
        double cost = models.get(0).textCost(1_000, 200);
        // 1000 prompt tokens at 0.00000015 + 200 completion tokens at 0.0000009375.
        assertEquals(0.0003375, cost, 1e-12);
        assertEquals(-1, models.get(1).textCost(1, 1));
    }

    @Test
    void resolvesShortNamesAndAliases() {
        List<ModelInfo> models = ModelCatalog.parse(TEXT_LIST);
        assertEquals("openai/gpt-5.4-nano", ModelCatalog.resolve(models, "gpt-5.4-nano"));
        assertEquals("openai/gpt-5.4-nano", ModelCatalog.resolve(models, "OPENAI/GPT-5.4-NANO"));
        assertEquals("tongyi-mai/z-image-turbo", ModelCatalog.resolve(models, "z-image-turbo"));
        assertNull(ModelCatalog.resolve(models, "gpt-4o"));
        assertNull(ModelCatalog.resolve(models, null));
    }

    @Test
    void survivesABrokenList() {
        assertTrue(ModelCatalog.parse("not json at all").isEmpty());
        assertTrue(ModelCatalog.parse("{}").isEmpty());
        assertTrue(ModelCatalog.parse("[{\"no\": \"name\"}]").isEmpty());
    }
}
