import { sha256 } from '@noble/hashes/sha2.js';
import { bytesToHex } from '@noble/hashes/utils.js';

export const VERSION = 2;
export const BLOCK_SIZE = 1024 * 1024;
export const FRAME_SIZE = 16 * 1024;
export const MEMORY_LIMIT = 64 * 1024 * 1024;
export const MAX_FILE_SIZE = 5 * 1024 ** 4;
export const hashBytes = bytes => bytesToHex(sha256(bytes));
export const uuid = () => {
  if (crypto.randomUUID) return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16)); bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
  const hex = bytesToHex(bytes); return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};
export const diskKey = (scope, id) => `${hashBytes(new TextEncoder().encode(scope)).slice(0, 32)}-${id}`;
export const validId = id => typeof id === 'string' && /^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(id);
export const validHash = hash => typeof hash === 'string' && /^[a-f\d]{64}$/i.test(hash);
export const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

// Fixed 32-byte header: magic/version, UUID, uint64 offset, uint32 length.
export function encodeFrame(id, offset, bytes) {
  if (!validId(id) || !Number.isSafeInteger(offset) || offset < 0 || bytes.length > FRAME_SIZE) throw new Error('Invalid file frame.');
  const result = new Uint8Array(32 + bytes.length);
  result.set([76, 68, 2, 0]);
  result.set(id.replaceAll('-', '').match(/../g).map(value => parseInt(value, 16)), 4);
  const view = new DataView(result.buffer);
  view.setBigUint64(20, BigInt(offset)); view.setUint32(28, bytes.length);
  result.set(bytes, 32);
  return result.buffer;
}
export function decodeFrame(buffer) {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < 32 || buffer.byteLength > FRAME_SIZE + 32) throw new Error('Invalid file frame size.');
  const bytes = new Uint8Array(buffer);
  if (bytes[0] !== 76 || bytes[1] !== 68 || bytes[2] !== 2 || bytes[3] !== 0) throw new Error('Unsupported file frame.');
  const hex = bytesToHex(bytes.subarray(4, 20));
  const id = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  const view = new DataView(buffer);
  const offset = Number(view.getBigUint64(20));
  if (!Number.isSafeInteger(offset) || view.getUint32(28) !== bytes.length - 32) throw new Error('Invalid file frame bounds.');
  return { id, offset, bytes: bytes.subarray(32) };
}
export async function hashFile(file, progress = () => {}) {
  const hash = sha256.create();
  for (let offset = 0; offset < file.size; offset += BLOCK_SIZE) {
    hash.update(new Uint8Array(await file.slice(offset, offset + BLOCK_SIZE).arrayBuffer()));
    progress(Math.min(file.size, offset + BLOCK_SIZE));
    await delay(0);
  }
  return bytesToHex(hash.digest());
}
export function validateOffer(offer) {
  if (!validId(offer.id) || typeof offer.name !== 'string' || !offer.name.trim() || offer.name.length > 255
      || /[\x00-\x1f/\\]/.test(offer.name) || !Number.isSafeInteger(offer.size) || offer.size < 0
      || offer.size > MAX_FILE_SIZE || !validHash(offer.hash) || typeof offer.mime !== 'string' || offer.mime.length > 255) {
    throw new Error('Invalid file offer.');
  }
}
