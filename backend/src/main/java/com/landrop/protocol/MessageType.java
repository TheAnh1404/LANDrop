package com.landrop.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Message types supported by the LANDrop Phase 2 custom TCP protocol.
 * Each type maps to a stable 1-byte code.
 */
public enum MessageType {
    HELLO((byte) 1),
    CHAT_MESSAGE((byte) 2),
    PING((byte) 3),
    PONG((byte) 4),
    FILE_OFFER((byte) 5),
    FILE_REQUEST((byte) 6),
    CHUNK_REQUEST((byte) 7),
    CHUNK_RESPONSE((byte) 8),
    TRANSFER_ACK((byte) 9);

    private final byte code;

    private static final Map<Byte, MessageType> CODE_MAP = new HashMap<>();

    static {
        for (MessageType type : values()) {
            CODE_MAP.put(type.code, type);
        }
    }

    MessageType(byte code) {
        this.code = code;
    }

    /**
     * Gets the 1-byte code representing this message type.
     *
     * @return the byte code
     */
    public byte getCode() {
        return code;
    }

    /**
     * Resolves a MessageType from its 1-byte code.
     *
     * @param code the byte code to resolve
     * @return the corresponding MessageType
     * @throws ProtocolException if the code does not correspond to any known MessageType
     */
    public static MessageType fromCode(byte code) throws ProtocolException {
        MessageType type = CODE_MAP.get(code);
        if (type == null) {
            throw new ProtocolException("Unknown message type code: " + code);
        }
        return type;
    }

    /**
     * Resolves a MessageType from an integer value representing a byte code.
     *
     * @param code the integer code to resolve
     * @return the corresponding MessageType
     * @throws ProtocolException if the code is out of byte range or unknown
     */
    public static MessageType fromCode(int code) throws ProtocolException {
        if (code < Byte.MIN_VALUE || code > Byte.MAX_VALUE) {
            throw new ProtocolException("Message type code out of byte range: " + code);
        }
        return fromCode((byte) code);
    }
}
