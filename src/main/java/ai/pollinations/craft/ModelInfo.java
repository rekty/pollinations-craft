package ai.pollinations.craft;

import java.util.List;

/**
 * One entry of the live model list at gen.pollinations.ai. Pricing is in
 * Pollen: text models charge per token, image models per image.
 */
public record ModelInfo(
        String name,
        String title,
        String category,
        double promptTextTokens,
        double completionTextTokens,
        double completionImageTokens,
        List<String> aliases,
        boolean paidOnly,
        boolean down) {

    /** What the owner pays for one reply, or -1 when the price is unknown. */
    public double textCost(int promptTokens, int completionTokens) {
        if (promptTextTokens < 0 || completionTextTokens < 0) return -1;
        return promptTokens * promptTextTokens + completionTokens * completionTextTokens;
    }
}
