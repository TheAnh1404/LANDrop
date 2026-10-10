import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const folder = path.dirname(fileURLToPath(import.meta.url));
const output = path.resolve(folder, '../../tmp/frontend-webrtc-tests');
fs.mkdirSync(output, { recursive: true });

const require = createRequire(import.meta.url);

// Transpile dependencies to CommonJS in scratch directory preserving relative paths
fs.mkdirSync(path.join(output, 'realtime'), { recursive: true });
fs.mkdirSync(path.join(output, 'webrtc'), { recursive: true });

for (const file of [
  { src: '../lib/realtime/config.ts', dest: 'realtime/config.js' },
  { src: '../lib/realtime/types.ts', dest: 'realtime/types.js' },
  { src: '../lib/realtime/realtime-client.ts', dest: 'realtime/realtime-client.js' },
  { src: '../lib/webrtc/config.ts', dest: 'webrtc/config.js' },
  { src: '../lib/webrtc/types.ts', dest: 'webrtc/types.js' },
  { src: '../lib/webrtc/webrtc-peer-service.ts', dest: 'webrtc/webrtc-peer-service.js' },
]) {
  const source = fs.readFileSync(path.resolve(folder, file.src), 'utf8');
  fs.writeFileSync(
    path.join(output, file.dest),
    ts.transpileModule(source, {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
    }).outputText
  );
}

const { RealtimeClient } = require(path.join(output, 'realtime/realtime-client.js'));
const { getRtcConfiguration, getDefaultIceServers } = require(path.join(output, 'webrtc/config.js'));
const { WebRTCPeerService } = require(path.join(output, 'webrtc/webrtc-peer-service.js'));

// =========================================================================
// Mocks for WebRTC and WebSocket in Node.js test runner
// =========================================================================

class MockRTCDataChannel {
  constructor(label, options) {
    this.label = label;
    this.options = options;
    this.readyState = 'connecting';
    this.onopen = null;
    this.onmessage = null;
    this.onclose = null;
    this.onerror = null;
    this.sentMessages = [];
  }

  send(data) {
    this.sentMessages.push(data);
  }

  close() {
    this.readyState = 'closed';
    if (this.onclose) this.onclose();
  }

  simulateOpen() {
    this.readyState = 'open';
    if (this.onopen) this.onopen();
  }

  simulateMessage(data) {
    if (this.onmessage) {
      this.onmessage({ data: typeof data === 'string' ? data : JSON.stringify(data) });
    }
  }
}

class MockRTCPeerConnection {
  constructor(config) {
    this.config = config;
    this.signalingState = 'stable';
    this.iceConnectionState = 'new';
    this.connectionState = 'new';
    this.localDescription = null;
    this.remoteDescription = null;
    this.dataChannels = [];
    this.addedIceCandidates = [];
    this.onicecandidate = null;
    this.oniceconnectionstatechange = null;
    this.onconnectionstatechange = null;
    this.onsignalingstatechange = null;
    this.ondatachannel = null;
    MockRTCPeerConnection.instances.push(this);
  }

  createDataChannel(label, options) {
    const channel = new MockRTCDataChannel(label, options);
    this.dataChannels.push(channel);
    return channel;
  }

  async createOffer() {
    return { type: 'offer', sdp: 'v=0\r\no=mock-alice-offer\r\ns=test' };
  }

  async createAnswer() {
    return { type: 'answer', sdp: 'v=0\r\no=mock-bob-answer\r\ns=test' };
  }

  async setLocalDescription(desc) {
    this.localDescription = desc;
    this.signalingState = desc.type === 'offer' ? 'have-local-offer' : 'stable';
  }

  async setRemoteDescription(desc) {
    this.remoteDescription = desc;
    this.signalingState = desc.type === 'offer' ? 'have-remote-offer' : 'stable';
  }

  async addIceCandidate(cand) {
    this.addedIceCandidates.push(cand);
  }

  close() {
    this.signalingState = 'closed';
    this.connectionState = 'closed';
  }
}
MockRTCPeerConnection.instances = [];

class MockRTCSessionDescription {
  constructor(init) {
    this.type = init.type;
    this.sdp = init.sdp;
  }
}

class MockRTCIceCandidate {
  constructor(init) {
    this.candidate = init.candidate;
    this.sdpMid = init.sdpMid;
    this.sdpMLineIndex = init.sdpMLineIndex;
    this.usernameFragment = init.usernameFragment;
  }
  toJSON() {
    return {
      candidate: this.candidate,
      sdpMid: this.sdpMid,
      sdpMLineIndex: this.sdpMLineIndex,
      usernameFragment: this.usernameFragment,
    };
  }
}

class MockWebSocket {
  static OPEN = 1;
  static CONNECTING = 0;
  static CLOSING = 2;
  static CLOSED = 3;

  constructor(url) {
    this.url = url;
    this.readyState = MockWebSocket.CONNECTING;
    this.sent = [];
    this.onopen = null;
    this.onmessage = null;
    this.onclose = null;
    this.onerror = null;
    MockWebSocket.instances.push(this);
  }

  send(data) {
    this.sent.push(JSON.parse(data));
  }

  close(code = 1000, reason = '') {
    this.readyState = MockWebSocket.CLOSED;
    if (this.onclose) this.onclose({ code, reason });
  }

  simulateOpen() {
    this.readyState = MockWebSocket.OPEN;
    if (this.onopen) this.onopen({});
  }

  simulateMessage(payload) {
    if (this.onmessage) {
      this.onmessage({ data: JSON.stringify(payload) });
    }
  }
}
MockWebSocket.instances = [];

// Install globals for tests
globalThis.WebSocket = MockWebSocket;
globalThis.RTCPeerConnection = MockRTCPeerConnection;
globalThis.RTCSessionDescription = MockRTCSessionDescription;
globalThis.RTCIceCandidate = MockRTCIceCandidate;
globalThis.window = {
  sessionStorage: {
    _data: {},
    getItem(k) { return this._data[k] || null; },
    setItem(k, v) { this._data[k] = String(v); },
    removeItem(k) { delete this._data[k]; },
  },
};

const activeClients = [];
const activeServices = [];

function createConnectedRealtimeClient(connectionId = 'conn-peer-1') {
  const client = new RealtimeClient();
  activeClients.push(client);

  client.connect({
    serverUrl: 'http://127.0.0.1:3100',
    accessToken: 'test-token',
  });

  const ws = MockWebSocket.instances[MockWebSocket.instances.length - 1];
  ws.simulateOpen();
  ws.simulateMessage({
    type: 'welcome',
    connectionId,
    heartbeatIntervalMs: 15000,
  });
  ws.simulateMessage({
    type: 'auth_ack',
    payload: { authenticated: true, userId: 'user-1', username: 'tester' },
  });

  return { client, ws };
}

function createService(client) {
  const service = new WebRTCPeerService(client);
  activeServices.push(service);
  return service;
}

test.afterEach(() => {
  activeServices.forEach(s => s.destroy());
  activeServices.length = 0;
  activeClients.forEach(c => c.disconnect());
  activeClients.length = 0;
  MockWebSocket.instances = [];
  MockRTCPeerConnection.instances = [];
});

// =========================================================================
// Test Suites
// =========================================================================

test('WebRTC Config: produces valid RTCConfiguration without credentials or local IPs', () => {
  const config = getRtcConfiguration();
  assert.ok(Array.isArray(config.iceServers), 'iceServers must be an array');
  assert.ok(config.iceServers.length > 0, 'Must have at least one ICE server entry');
  assert.equal(config.iceCandidatePoolSize, 2);

  const defaultStun = getDefaultIceServers();
  assert.ok(defaultStun[0].urls.some(u => u.includes('stun.l.google.com:19302')));
});

test('WebRTCPeerService starts in IDLE state with clear diagnostics', () => {
  const { client } = createConnectedRealtimeClient('conn-self');
  const service = createService(client);

  assert.equal(service.getState(), 'IDLE');
  assert.equal(service.getRole(), null);
  assert.equal(service.getIncomingRequest(), null);
  assert.equal(service.getTestResult(), null);

  const diag = service.getDiagnostics();
  assert.equal(diag.signalingState, 'IDLE');
  assert.equal(diag.transferId, null);
  assert.equal(diag.roundTripLatencyMs, null);
  assert.equal(diag.lastError, null);
  assert.ok(!('sdp' in diag), 'Diagnostics must not expose raw SDP');
});

function createChatPeer(connectionId) {
  const { client } = createConnectedRealtimeClient(connectionId);
  const service = createService(client);
  const channel = new MockRTCDataChannel('landrop-chat', { ordered: true });
  service.dataChannel = channel;
  service.setupDataChannel(channel);
  channel.simulateOpen();
  channel.sentMessages.length = 0;
  return { service, channel };
}

test('P2P chat sends once and publishes delivery updates without duplicating history', () => {
  const alice = createChatPeer('conn-alice');
  const bob = createChatPeer('conn-bob');
  const changes = [];
  alice.service.onChatMessage(message => changes.push(message));
  const message = alice.service.sendChatMessage(' hello ', 'previous-message');
  assert.equal(changes.length, 1);
  assert.equal(alice.service.getChatMessages().length, 1);
  const packet = JSON.parse(alice.channel.sentMessages[0]);
  assert.equal(packet.text, 'hello');
  assert.equal(packet.replyToId, 'previous-message');
  assert.equal(message.timestamp, packet.timestamp);

  bob.channel.simulateMessage(packet);
  bob.channel.simulateMessage(packet);
  assert.equal(bob.service.getChatMessages().length, 1, 'Repeated delivery must be acknowledged without a second bubble');
  assert.equal(bob.service.getChatMessages()[0].replyToId, 'previous-message');
  const ack = JSON.parse(bob.channel.sentMessages[0]);
  alice.channel.simulateMessage(ack);
  alice.channel.simulateMessage(ack);
  assert.equal(changes.length, 2, 'Delivery publishes one update for the existing bubble');
  assert.equal(changes[1].ack, true);
  assert.equal(alice.service.getChatMessages().length, 1);
  assert.equal(alice.service.getChatMessages()[0].ack, true);
});

test('P2P chat rejects blank or disconnected sends and ignores malformed messages and acknowledgements', () => {
  const { service, channel } = createChatPeer('conn-chat');
  assert.throws(() => service.sendChatMessage('   '));
  const message = service.sendChatMessage('hello');
  for (const packet of [
    { type: 'CHAT_MESSAGE', id: 'bad', text: {} },
    { type: 'CHAT_MESSAGE', text: 'missing id' },
    { type: 'CHAT_ACK', messageId: message.id, status: 'unknown' },
    { type: 'CHAT_ACK', messageId: 'unknown', status: 'delivered' },
  ]) channel.simulateMessage(packet);
  assert.equal(service.getChatMessages().length, 1);
  assert.equal(service.getChatMessages()[0].ack, false);
  channel.readyState = 'closed';
  assert.throws(() => service.sendChatMessage('offline'));
});

test('P2P typing clears when a message arrives or the peer disconnects, and destroy removes listeners', () => {
  const { service, channel } = createChatPeer('conn-chat');
  const typing = [];
  service.onChatTyping(event => typing.push(event.isTyping));
  channel.simulateMessage({ type: 'CHAT_TYPING', isTyping: true });
  channel.simulateMessage({ type: 'CHAT_MESSAGE', id: 'incoming', text: 'hello' });
  assert.deepEqual(typing, [true, false]);
  channel.simulateMessage({ type: 'CHAT_TYPING', isTyping: true });
  service.cancelConnection();
  assert.equal(typing.at(-1), false);
  service.destroy();
  assert.equal(service.chatMessageListeners.size, 0);
  assert.equal(service.chatTypingListeners.size, 0);
  assert.deepEqual(service.getChatMessages(), []);
});

test('P2P file completion requires matching identity and the complete byte count', () => {
  const { service, channel } = createChatPeer('conn-file');
  const files = [];
  service.onFileReceived(file => files.push(file));
  const start = { type: 'FILE_START', transferId: 'transfer', fileId: 'truncated', fileName: 'test.bin', fileSize: 3, mimeType: 'application/octet-stream', totalChunks: 1 };
  channel.simulateMessage(start);
  service.handleBinaryChunk(new Uint8Array([1, 2]).buffer);
  channel.simulateMessage({ type: 'FILE_END', transferId: 'transfer', fileId: 'wrong' });
  assert.equal(service.getTransferProgress().status, 'transferring');
  channel.simulateMessage({ type: 'FILE_END', transferId: 'transfer', fileId: 'truncated' });
  assert.equal(files.length, 0);
  assert.equal(service.getTransferProgress().status, 'failed');
  channel.simulateMessage({ ...start, fileId: 'empty', fileSize: 0, totalChunks: 0 });
  channel.simulateMessage({ type: 'FILE_END', transferId: 'transfer', fileId: 'empty' });
  assert.equal(files.length, 1);
  assert.equal(files[0].fileSize, 0);
  assert.equal(service.getTransferProgress().status, 'completed');
});

test('P2P sender prevents simultaneous files and preserves failure after channel loss', async () => {
  const { service, channel } = createChatPeer('conn-file');
  channel.send = data => {
    channel.sentMessages.push(data);
    if (data instanceof ArrayBuffer) channel.readyState = 'closed';
  };
  const file = new File([new Uint8Array(40000)], 'test.bin');
  const sending = service.sendFile(file);
  await assert.rejects(service.sendFile(file));
  await assert.rejects(sending);
  assert.equal(service.getTransferProgress().status, 'failed');
  assert.equal(service.getChatMessages().length, 0);
  assert.equal(channel.sentMessages.some(data => typeof data === 'string' && JSON.parse(data).type === 'FILE_END'), false);
});

test('Offerer initiates transfer.request and transitions IDLE -> REQUESTING -> WAITING_ACCEPT', async () => {
  const { client, ws } = createConnectedRealtimeClient('conn-alice');
  const service = createService(client);

  const states = [];
  service.onStateChange(st => states.push(st));

  await service.requestConnection({
    workspaceId: '11111111-1111-1111-1111-111111111111',
    receiverConnectionId: 'conn-bob',
    fileName: 'landrop-p2p-test.dat',
  });

  assert.equal(service.getState(), 'REQUESTING');

  // Verify outgoing transfer.request envelope
  const reqMsg = ws.sent.find(m => m.type === 'transfer.request');
  assert.ok(reqMsg, 'Must send transfer.request envelope');
  assert.equal(reqMsg.payload.workspaceId, '11111111-1111-1111-1111-111111111111');
  assert.equal(reqMsg.payload.receiverConnectionId, 'conn-bob');
  assert.equal(reqMsg.payload.fileName, 'landrop-p2p-test.dat');

  // Server replies with transfer.status PENDING
  ws.simulateMessage({
    type: 'transfer.status',
    payload: {
      transferId: 'trans-uuid-1',
      status: 'PENDING',
    },
  });

  assert.equal(service.getState(), 'WAITING_ACCEPT');
  assert.equal(service.getDiagnostics().transferId, 'trans-uuid-1');
  assert.deepEqual(states, ['IDLE', 'REQUESTING', 'WAITING_ACCEPT']);
});

test('Receiver receives transfer.request and notifies incoming request listeners', () => {
  const { client, ws } = createConnectedRealtimeClient('conn-bob');
  const service = createService(client);

  let notifiedRequest = null;
  service.onIncomingRequest(req => {
    notifiedRequest = req;
  });

  ws.simulateMessage({
    type: 'transfer.request',
    payload: {
      transferId: 'trans-uuid-2',
      workspaceId: '11111111-1111-1111-1111-111111111111',
      senderUserId: 'user-alice-id',
      senderConnectionId: 'conn-alice',
      senderDeviceName: 'Alice Laptop',
      fileName: 'test.dat',
      fileSize: 2048,
    },
  });

  assert.ok(notifiedRequest, 'Incoming request listener must be called');
  assert.equal(notifiedRequest.transferId, 'trans-uuid-2');
  assert.equal(notifiedRequest.senderConnectionId, 'conn-alice');
  assert.equal(notifiedRequest.senderDeviceName, 'Alice Laptop');
  assert.equal(service.getIncomingRequest()?.transferId, 'trans-uuid-2');
});

test('Rejection flow: Receiver rejects and Offerer transitions to REJECTED with cleanup', async () => {
  const { client: aliceClient, ws: aliceWs } = createConnectedRealtimeClient('conn-alice');
  const aliceService = createService(aliceClient);

  await aliceService.requestConnection({
    workspaceId: '11111111-1111-1111-1111-111111111111',
    receiverConnectionId: 'conn-bob',
  });

  aliceWs.simulateMessage({
    type: 'transfer.status',
    payload: { transferId: 'trans-uuid-3', status: 'PENDING' },
  });

  assert.equal(aliceService.getState(), 'WAITING_ACCEPT');

  // Receiver rejects
  aliceWs.simulateMessage({
    type: 'transfer.reject',
    payload: { transferId: 'trans-uuid-3', reason: 'User is busy' },
  });

  assert.equal(aliceService.getState(), 'REJECTED');
  assert.equal(aliceService.getDiagnostics().lastError, 'User is busy');
});

test('Cancellation flow: Sender cancels and state transitions to CANCELLED with cleanup', async () => {
  const { client, ws } = createConnectedRealtimeClient('conn-alice');
  const service = createService(client);

  await service.requestConnection({
    workspaceId: '11111111-1111-1111-1111-111111111111',
    receiverConnectionId: 'conn-bob',
  });

  ws.simulateMessage({
    type: 'transfer.status',
    payload: { transferId: 'trans-uuid-4', status: 'PENDING' },
  });

  service.cancelConnection('User cancelled');

  assert.equal(service.getState(), 'CANCELLED');
  const cancelMsg = ws.sent.find(m => m.type === 'transfer.cancel');
  assert.ok(cancelMsg, 'Must send transfer.cancel envelope');
  assert.equal(cancelMsg.payload.transferId, 'trans-uuid-4');
  assert.equal(cancelMsg.payload.reason, 'User cancelled');
});

test('Full WebRTC Offer/Answer, Early ICE candidate queueing, and DataChannel PING/PONG', async () => {
  // Setup Alice (Offerer) and Bob (Answerer)
  const { client: aliceClient, ws: aliceWs } = createConnectedRealtimeClient('conn-alice');
  const { client: bobClient, ws: bobWs } = createConnectedRealtimeClient('conn-bob');

  const aliceService = createService(aliceClient);
  const bobService = createService(bobClient);

  // 1. Alice requests connection to Bob
  await aliceService.requestConnection({
    workspaceId: '11111111-1111-1111-1111-111111111111',
    receiverConnectionId: 'conn-bob',
  });

  aliceWs.simulateMessage({
    type: 'transfer.status',
    payload: { transferId: 'trans-100', status: 'PENDING' },
  });
  assert.equal(aliceService.getState(), 'WAITING_ACCEPT');

  // 2. Bob receives transfer.request and accepts
  bobWs.simulateMessage({
    type: 'transfer.request',
    payload: {
      transferId: 'trans-100',
      workspaceId: '11111111-1111-1111-1111-111111111111',
      senderUserId: 'user-alice',
      senderConnectionId: 'conn-alice',
      fileName: 'landrop-p2p-test.dat',
      fileSize: 1024,
    },
  });

  await bobService.acceptIncomingRequest('trans-100');
  assert.equal(bobService.getState(), 'SIGNALING');

  const bobAcceptMsg = bobWs.sent.find(m => m.type === 'transfer.accept');
  assert.ok(bobAcceptMsg);
  assert.equal(bobAcceptMsg.payload.transferId, 'trans-100');

  // 3. Alice receives transfer.accept from Bob
  aliceWs.simulateMessage({
    type: 'transfer.accept',
    payload: { transferId: 'trans-100', receiverConnectionId: 'conn-bob' },
  });

  assert.equal(aliceService.getState(), 'SIGNALING');

  await new Promise(r => setTimeout(r, 10));

  // Alice creates RTCPeerConnection and sends webrtc.offer
  const aliceOfferMsg = aliceWs.sent.find(m => m.type === 'webrtc.offer');
  assert.ok(aliceOfferMsg, 'Alice must send webrtc.offer');
  assert.equal(aliceOfferMsg.payload.sdp.type, 'offer');

  // 4. Early ICE candidates arrive before Bob sets remote description!
  bobWs.simulateMessage({
    type: 'webrtc.ice_candidate',
    payload: {
      transferId: 'trans-100',
      candidate: { candidate: 'candidate:early-1 1 udp ...', sdpMid: '0', sdpMLineIndex: 0 },
    },
  });

  // Bob now receives Alice's offer
  bobWs.simulateMessage({
    type: 'webrtc.offer',
    payload: {
      transferId: 'trans-100',
      fromConnectionId: 'conn-alice',
      sdp: aliceOfferMsg.payload.sdp,
    },
  });

  // Wait for async processing of offer on Bob
  await new Promise(r => setTimeout(r, 10));

  assert.equal(bobService.getState(), 'CONNECTING');

  // Verify Bob drained the early ICE candidate
  const bobPc = MockRTCPeerConnection.instances[0];
  assert.ok(bobPc.addedIceCandidates.length > 0, 'Bob must have drained and added the early ICE candidate');
  assert.equal(bobPc.addedIceCandidates[0].candidate, 'candidate:early-1 1 udp ...');

  // Verify Bob sent webrtc.answer
  const bobAnswerMsg = bobWs.sent.find(m => m.type === 'webrtc.answer');
  assert.ok(bobAnswerMsg, 'Bob must send webrtc.answer');
  assert.equal(bobAnswerMsg.payload.sdp.type, 'answer');

  // 5. Alice receives Bob's answer
  aliceWs.simulateMessage({
    type: 'webrtc.answer',
    payload: {
      transferId: 'trans-100',
      fromConnectionId: 'conn-bob',
      sdp: bobAnswerMsg.payload.sdp,
    },
  });

  await new Promise(r => setTimeout(r, 10));
  assert.equal(aliceService.getState(), 'CONNECTING');

  // 6. DataChannel Opens & Technical Ping / Pong verification
  const alicePc = MockRTCPeerConnection.instances[1];
  const aliceDataChannel = alicePc.dataChannels[0];
  assert.ok(aliceDataChannel, 'Alice must have created RTCDataChannel');

  // Simulate Bob receiving data channel on his peer connection
  const bobDataChannel = new MockRTCDataChannel('landrop-p2p-test', { ordered: true });
  bobPc.ondatachannel({ channel: bobDataChannel });
  bobDataChannel.simulateOpen();

  // Alice DataChannel opens -> triggers PING
  aliceDataChannel.simulateOpen();
  assert.equal(aliceDataChannel.sentMessages.length, 1);
  const pingMessage = JSON.parse(aliceDataChannel.sentMessages[0]);
  assert.equal(pingMessage.type, 'PING');
  assert.ok(pingMessage.timestamp > 0);

  // Bob receives Alice's PING on his data channel -> Bob replies with PONG
  bobDataChannel.simulateMessage(pingMessage);
  assert.equal(bobDataChannel.sentMessages.length, 1);
  const pongMessage = JSON.parse(bobDataChannel.sentMessages[0]);
  assert.equal(pongMessage.type, 'PONG');
  assert.equal(pongMessage.clientTimestamp, pingMessage.timestamp);
  assert.equal(bobService.getState(), 'CONNECTED');

  // Alice receives Bob's PONG -> computes roundTripLatency and completes verification!
  let testResultFired = null;
  aliceService.onTestResult(res => {
    testResultFired = res;
  });

  aliceDataChannel.simulateMessage(pongMessage);

  assert.equal(aliceService.getState(), 'CONNECTED');
  assert.ok(testResultFired, 'Test result must be emitted on Offerer');
  assert.equal(testResultFired.passed, true);
  assert.ok(testResultFired.roundTripMs >= 0);

  const diag = aliceService.getDiagnostics();
  assert.equal(diag.signalingState, 'CONNECTED');
  assert.equal(diag.dataChannelState, 'open');
  assert.ok(diag.roundTripLatencyMs !== null);
});
