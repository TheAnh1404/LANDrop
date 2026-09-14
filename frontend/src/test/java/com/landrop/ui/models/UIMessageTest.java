package com.landrop.ui.models;

import com.landrop.chat.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UIMessageTest {

    @Test
    @DisplayName("15.5B.2: Message ownership is determined by senderId === auth.user.id, NOT peerA")
    void testMessageOwnershipBySenderId() {
        String myUserId = "user-ada-123";
        String otherUserId = "user-ken-456";

        UIMessage myMessage = new UIMessage(
                "msg-1",
                myUserId,
                "Ada",
                "ada",
                null,
                "Hello world",
                System.currentTimeMillis(),
                false // even if legacy isLocal was false, senderId determines ownership!
        );

        UIMessage otherMessage = new UIMessage(
                "msg-2",
                otherUserId,
                "Ken",
                "ken",
                null,
                "Hi Ada",
                System.currentTimeMillis(),
                true // even if legacy isLocal was true, senderId determines ownership!
        );

        assertTrue(myMessage.isMine(myUserId), "Message with senderId == myUserId must be mine");
        assertFalse(otherMessage.isMine(myUserId), "Message with senderId != myUserId must not be mine");

        // Remote peer perspective (Ken)
        assertTrue(otherMessage.isMine(otherUserId), "Ken sees his own message as mine");
        assertFalse(myMessage.isMine(otherUserId), "Ken sees Ada's message as not mine");
    }

    @Test
    @DisplayName("15.5B.3: Sender display label and initials formatting")
    void testSenderLabelAndInitials() {
        UIMessage msgWithDisplayName = new UIMessage(
                "msg-1", "u1", "Ken Thompson", "kenth", null, "Unix rules", 1000L, false
        );
        assertEquals("Ken Thompson", msgWithDisplayName.senderLabel());
        assertEquals("KT", msgWithDisplayName.senderInitials());

        UIMessage msgWithUsernameOnly = new UIMessage(
                "msg-2", "u2", null, "linus", null, "Git rules", 2000L, false
        );
        assertEquals("linus", msgWithUsernameOnly.senderLabel());
        assertEquals("LI", msgWithUsernameOnly.senderInitials());

        UIMessage msgSingleName = new UIMessage(
                "msg-3", "u3", "Anh", "anh1404", null, "LANDrop", 3000L, false
        );
        assertEquals("Anh", msgSingleName.senderLabel());
        assertEquals("AN", msgSingleName.senderInitials());
    }

    @Test
    @DisplayName("15.5B.1: Backward compatibility with legacy ChatMessage and requestId")
    void testLegacyChatMessageCompatibility() {
        ChatMessage chatMessage = new ChatMessage(42L, "Alice", "Hello legacy", 1726000000000L);
        UIMessage uiMsg = UIMessage.from(chatMessage, true);

        assertEquals("42", uiMsg.id());
        assertEquals(42L, uiMsg.requestId());
        assertEquals("Alice", uiMsg.sender());
        assertEquals("Alice", uiMsg.senderLabel());
        assertEquals("Hello legacy", uiMsg.content());
        assertTrue(uiMsg.isLocal());
        assertTrue(uiMsg.isMine(null)); // fallback to isLocal when currentUserId is null
    }

    @Test
    @DisplayName("Optimistic message factory generates valid temporary correlation ID")
    void testOptimisticMessageFactory() {
        UIMessage opt = UIMessage.optimistic(null, "u-1", "Ken", "ken", null, "Draft message");

        assertNotNull(opt.id());
        assertTrue(opt.id().startsWith("opt-"));
        assertEquals("u-1", opt.senderId());
        assertEquals("Ken", opt.senderDisplayName());
        assertEquals("Draft message", opt.content());
        assertTrue(opt.isMine("u-1"));
    }
}
