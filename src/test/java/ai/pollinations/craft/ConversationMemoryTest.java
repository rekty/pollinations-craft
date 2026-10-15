package ai.pollinations.craft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryTest {

    @Test
    void keepsTheMostRecentTurns() {
        ConversationMemory memory = new ConversationMemory(12, 10_000);
        for (int i = 1; i <= 20; i++) memory.remember("line " + i, "reply " + i);
        assertEquals(12, memory.turns());
        List<String[]> messages = memory.asMessages();
        assertEquals("user", messages.get(0)[0]);
        assertEquals("line 9", messages.get(0)[1]);
        assertEquals("reply 20", messages.get(messages.size() - 1)[1]);
    }

    @Test
    void alsoCapsByCharactersSoOnePlayerCannotSpendTheWholeBudget() {
        ConversationMemory memory = new ConversationMemory(100, 200);
        for (int i = 1; i <= 50; i++) memory.remember("x".repeat(50), "y".repeat(50));
        assertTrue(memory.totalChars() <= 200, "history stayed at " + memory.totalChars());
        assertTrue(memory.turns() >= 1);
        assertTrue(memory.turns() < 50);
    }

    @Test
    void roundTripsThroughTheStorageShape() {
        ConversationMemory memory = new ConversationMemory(12, 10_000);
        memory.remember("hello", "hi");
        memory.remember("how are you", "fine, thanks");
        // NpcRegistry stores the turns as alternating lines; reading them back
        // in pairs must rebuild the same conversation.
        List<String[]> raw = memory.asMessages();
        ConversationMemory rebuilt = new ConversationMemory(12, 10_000);
        for (int i = 0; i + 1 < raw.size(); i += 2) {
            assertEquals("user", raw.get(i)[0]);
            assertEquals("assistant", raw.get(i + 1)[0]);
            rebuilt.remember(raw.get(i)[1], raw.get(i + 1)[1]);
        }
        assertEquals(memory.turns(), rebuilt.turns());
        assertEquals(memory.totalChars(), rebuilt.totalChars());
    }

    @Test
    void theSystemPromptCarriesThePersonaAndTheRules() {
        String prompt = ConversationMemory.systemPrompt("a grumpy blacksmith", "Bjorn", "Steve");
        assertTrue(prompt.contains("Bjorn"));
        assertTrue(prompt.contains("a grumpy blacksmith"));
        assertTrue(prompt.contains("Steve"));
        assertTrue(prompt.contains("cannot run commands"));
    }
}
