import type { Message } from '../types';

/**
 * Extracts monotonic timestamp from all available message timestamps.
 */
export function getMessageTimestamp(m: {
  createdAt?: string;
  updatedAt?: string;
  editedAt?: string;
  deletedAt?: string;
}): number {
  const candidates = [m.deletedAt, m.editedAt, m.updatedAt, m.createdAt];
  let maxTime = 0;
  for (const c of candidates) {
    if (typeof c === 'string' && c.trim().length > 0) {
      const parsed = Date.parse(c);
      if (!isNaN(parsed) && parsed > maxTime) {
        maxTime = parsed;
      }
    }
  }
  return maxTime;
}

export function shouldUpdateMessage(existing: Message, incoming: Message): boolean {
  // Terminal deletion: if existing is marked deleted, non-deleted snapshot can never un-delete it
  if (existing.isDeleted && !incoming.isDeleted) {
    return false;
  }

  const tExisting = getMessageTimestamp(existing);
  const tIncoming = getMessageTimestamp(incoming);

  // Deletion precedence: if incoming is deleted and existing is not
  if (incoming.isDeleted && !existing.isDeleted) {
    if (tIncoming >= tExisting) {
      return true;
    }
  }

  // Strictly newer timestamp wins
  if (tIncoming > tExisting) {
    return true;
  }
  if (tIncoming < tExisting) {
    return false;
  }

  // Equal timestamps: incoming edited wins over unedited
  if (incoming.isEdited && !existing.isEdited) {
    return true;
  }
  if (!incoming.isEdited && existing.isEdited) {
    return false;
  }

  // Equal timestamps: incoming deleted wins over non-deleted
  if (incoming.isDeleted && !existing.isDeleted) {
    return true;
  }
  if (!incoming.isDeleted && existing.isDeleted) {
    return false;
  }

  // Default: accept incoming snapshot
  return true;
}

export function reconcileMessage(existing: Message, incoming: Message): Message {
  if (!shouldUpdateMessage(existing, incoming)) {
    return existing;
  }

  const isDeleted = Boolean(incoming.isDeleted || existing.isDeleted);
  const content = isDeleted ? 'Tin nhắn đã được xóa.' : (incoming.content || existing.content);

  return {
    ...existing,
    ...incoming,
    content,
    isDeleted,
    isEdited: Boolean(incoming.isEdited || existing.isEdited),
    deletedAt: incoming.deletedAt || existing.deletedAt,
    editedAt: incoming.editedAt || existing.editedAt,
    updatedAt: incoming.updatedAt || existing.updatedAt,
    clientMessageId: incoming.clientMessageId || existing.clientMessageId,
    senderName: incoming.senderName || existing.senderName,
    senderAvatar: incoming.senderAvatar || existing.senderAvatar,
    replyTo: incoming.replyTo || existing.replyTo,
    replyToId: incoming.replyToId || existing.replyToId,
  };
}

/**
 * Pure deterministic reconciliation helper for Chat messages.
 * Reconciles initial history, catch-up history, older pagination, REST POST response,
 * and live create/update/delete events without losing newer live updates.
 */
export function reconcileMessages(
  current: Message[],
  incoming: Message | Message[]
): Message[] {
  const items = Array.isArray(incoming) ? incoming : [incoming];
  if (items.length === 0) return current;

  const result: Message[] = [...current];
  const idToIndex = new Map<string, number>();
  const clientMsgIdToIndex = new Map<string, number>();

  result.forEach((m, idx) => {
    idToIndex.set(m.id, idx);
    if (m.clientMessageId) {
      clientMsgIdToIndex.set(m.clientMessageId, idx);
    }
  });

  for (const item of items) {
    let existingIndex: number | undefined;

    if (idToIndex.has(item.id)) {
      existingIndex = idToIndex.get(item.id);
    } else if (item.clientMessageId && clientMsgIdToIndex.has(item.clientMessageId)) {
      existingIndex = clientMsgIdToIndex.get(item.clientMessageId);
    } else if (item.clientMessageId && idToIndex.has(item.clientMessageId)) {
      existingIndex = idToIndex.get(item.clientMessageId);
    }

    if (existingIndex !== undefined) {
      const existing = result[existingIndex];
      const merged = reconcileMessage(existing, item);
      result[existingIndex] = merged;
      idToIndex.set(merged.id, existingIndex);
      if (merged.clientMessageId) {
        clientMsgIdToIndex.set(merged.clientMessageId, existingIndex);
      }
    } else {
      // Unknown canonical message DTO: insert
      const newIdx = result.length;
      result.push(item);
      idToIndex.set(item.id, newIdx);
      if (item.clientMessageId) {
        clientMsgIdToIndex.set(item.clientMessageId, newIdx);
      }
    }
  }

  // Monotonic chronological ordering
  return result.sort((a, b) => {
    const cmp = a.createdAt.localeCompare(b.createdAt);
    if (cmp !== 0) return cmp;
    return a.id.localeCompare(b.id);
  });
}
