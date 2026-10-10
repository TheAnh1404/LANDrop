import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(new URL('../backend/src/main/resources/client/index.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function client() {
  const elements = new Map();
  function element() {
    return {
      style: {}, classList: { add() {}, remove() {} }, value: '', textContent: '', innerHTML: '', options: [],
      appendChild() {}, removeChild() {}, querySelector() { return null; }, querySelectorAll() { return []; },
      click() {}, setAttribute() {}, addEventListener() {},
    };
  }
  const packets = [];
  const blobs = [];
  const context = vm.createContext({
    window: { addEventListener() {}, location: { protocol: 'http:', hostname: 'localhost', port: '3100', href: 'http://localhost:3100/client', search: '' } },
    document: {
      getElementById(id) { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); },
      querySelector() { return element(); }, createElement: element, body: element(),
    },
    navigator: { userAgent: 'Windows', clipboard: { writeText() {} } },
    localStorage: { getItem() { return null; }, setItem() {} },
    WebSocket: { OPEN: 1, CONNECTING: 0 },
    RTCSessionDescription: class { constructor(value) { Object.assign(this, value); } },
    RTCIceCandidate: class { constructor(value) { Object.assign(this, value); } },
    Blob, ArrayBuffer, Date, Math, JSON, URLSearchParams, setTimeout, clearTimeout, setInterval, clearInterval,
    URL: { createObjectURL(blob) { blobs.push(blob); return 'blob:test'; } },
    console, alert() {}, prompt() {},
    fetch: async () => ({ ok: true, status: 200, json: async () => ({ data: [{ id: 'team', name: 'Team', role: 'MEMBER' }] }) }),
    packets,
  });
  const run = code => vm.runInContext(code, context);
  run(script);
  run(`ws = { readyState: 1, send(value) { packets.push(JSON.parse(value)); } };
    authenticated = true; authToken = 'test-token'; currentUser = { id: 'alice' }; workspaceId = 'team';
    selectedPeer = { connectionId: 'bob', displayName: 'Bob', platform: 'browser' };
    peersList = [selectedPeer];`);
  return { run, context, packets, blobs, elements };
}

test('bundled client authenticates after welcome and uses the server-issued transfer ID', async () => {
  const { run, packets } = client();
  run(`handleGatewayMessage({ type: 'welcome', connectionId: 'alice-connection' });`);
  assert.equal(packets[0].type, 'auth');
  assert.equal(packets[0].payload.accessToken, 'test-token');
  run(`initiateP2PConnectionToPeer('bob');`);
  assert.equal(packets[1].payload.workspaceId, 'team');
  assert.equal(run('activeTransferId'), null);
  run(`handleGatewayMessage({ type: 'transfer.status', payload: { status: 'REQUESTED', transferId: 'server-id', receiverConnectionId: 'bob' } });`);
  assert.equal(run('activeTransferId'), 'server-id');
});

test('bundled client accepts incoming chat without replacing the server session', () => {
  const { run, context, packets } = client();
  context.RTCPeerConnection = class { close() {} };
  run(`handleIncomingTransferRequest({ transferId: 'incoming-server-id', senderConnectionId: 'bob', fileName: 'p2p-chat-session' });`);
  assert.equal(run('activeTransferId'), 'incoming-server-id');
  assert.equal(run('activeRole'), 'answerer');
  assert.equal(run('activePeerConnectionId'), 'bob');
  assert.deepEqual(packets.map(packet => packet.type), ['transfer.accept']);
  assert.equal(packets[0].payload.transferId, 'incoming-server-id');
});

test('bundled client reuses pending negotiation when reselecting or sending queued chat', () => {
  const { run, packets } = client();
  run(`initiateP2PConnectionToPeer('bob');
    handleGatewayMessage({ type: 'transfer.status', payload: { status: 'REQUESTED', transferId: 'server-id', receiverConnectionId: 'bob' } });
    selectPeerConversation(selectedPeer);
    document.getElementById('composer-textarea').value = 'Queued during negotiation';
    sendCurrentMessage();`);
  assert.equal(packets.filter(packet => packet.type === 'transfer.request').length, 1);
  assert.equal(run('activeTransferId'), 'server-id');
  assert.equal(run('activeRole'), 'offerer');
  assert.equal(run('pendingOutgoingMessages.length'), 1);
});

test('bundled client buffers early ICE, keeps SDP objects, and ignores signals from old sessions', async () => {
  const { run, context, packets } = client();
  const candidates = [];
  const peer = {
    remoteDescription: null,
    async setRemoteDescription(description) { this.remoteDescription = description; },
    async addIceCandidate(candidate) { candidates.push(candidate); },
    async createAnswer() { return { type: 'answer', sdp: 'answer-sdp' }; },
    async setLocalDescription() {},
  };
  context.peer = peer;
  run(`rtcPeer = peer; activeRole = 'answerer'; activeTransferId = 'server-id'; activePeerConnectionId = 'bob';`);
  await run(`handleIncomingIceCandidate({ payload: { transferId: 'server-id', candidate: { candidate: 'early' } } });`);
  await run(`handleIncomingIceCandidate({ payload: { transferId: 'old-id', candidate: { candidate: 'stale' } } });`);
  await run(`handleIncomingIceCandidate({ payload: { transferId: 'server-id', candidate: null } });`);
  assert.equal(candidates.length, 0);
  await run(`handleIncomingWebRTCOffer({ payload: { transferId: 'server-id', sdp: { type: 'offer', sdp: 'offer-sdp' } } });`);
  assert.equal(peer.remoteDescription.sdp, 'offer-sdp');
  assert.equal(candidates.length, 2);
  assert.equal(candidates[0].candidate, 'early');
  assert.equal(candidates[1], null);
  assert.deepEqual(JSON.parse(JSON.stringify(packets.at(-1).payload.sdp)), { type: 'answer', sdp: 'answer-sdp' });
  assert.equal(packets.at(-1).payload.toConnectionId, 'bob');
});

test('bundled client switches peers by cancelling the previous channel', () => {
  const { run, context, packets } = client();
  let channelClosed = false;
  context.channel = { readyState: 'open', close() { channelClosed = true; } };
  run(`rtcDataChannel = channel; activePeerConnectionId = 'bob'; activeTransferId = 'old-session';
    selectPeerConversation({ connectionId: 'charlie', displayName: 'Charlie' });`);
  assert.equal(channelClosed, true);
  assert.equal(packets[0].type, 'transfer.cancel');
  assert.equal(packets[0].payload.transferId, 'old-session');
  assert.equal(packets.at(-1).payload.receiverConnectionId, 'charlie');
  assert.equal(run('rtcDataChannel'), null);
});

test('bundled client validates file completion and counts bytes without completing truncated files', async () => {
  const { run, context, blobs } = client();
  context.chunk = new Uint8Array([1, 2, 3]).buffer;
  run(`handleIncomingFileStart({ fileId: 'complete', fileName: 'test.bin', fileSize: 3 });
    handleIncomingBinaryChunk(chunk);
    handleIncomingFileEnd({ fileId: 'wrong-id' });`);
  assert.equal(blobs.length, 0);
  run(`handleIncomingFileEnd({ fileId: 'complete' });`);
  assert.equal(blobs.length, 1);
  assert.deepEqual(new Uint8Array(await blobs[0].arrayBuffer()), new Uint8Array([1, 2, 3]));
  run(`handleIncomingFileStart({ fileId: 'truncated', fileName: 'test.bin', fileSize: 5 });
    handleIncomingBinaryChunk(chunk);
    handleIncomingFileEnd({ fileId: 'truncated' });`);
  assert.equal(blobs.length, 1, 'Truncated bytes must not become a download');
  assert.equal(run(`conversations.bob.find(item => item.id === 'truncated').transferStatus`), 'failed');
});

test('bundled client stops interrupted sends without FILE_END or a success state', async () => {
  const { run, context } = client();
  const sent = [];
  const channel = {
    readyState: 'open', bufferedAmount: 0,
    send(packet) { sent.push(packet); if (packet instanceof ArrayBuffer) this.readyState = 'closed'; },
  };
  context.channel = channel;
  context.file = new File([new Uint8Array(40000)], 'interrupted.bin');
  run('rtcDataChannel = channel;');
  await run('sendFileOverDataChannel(file)');
  assert.equal(sent.some(packet => typeof packet === 'string' && JSON.parse(packet).type === 'FILE_END'), false);
  assert.equal(run('conversations.bob[0].transferStatus'), 'failed');
  assert.equal(run('sendingFile'), false);
});

test('bundled client queues messages when P2P channel is not yet open and auto-sends on open', () => {
  const { run, context, packets } = client();
  const sent = [];
  const channel = {
    readyState: 'connecting',
    bufferedAmount: 0,
    send(packet) { sent.push(packet); },
  };
  context.channel = channel;
  run(`rtcDataChannel = channel; activePeerConnectionId = 'bob';
    document.getElementById('composer-textarea').value = 'Xin chao peer!';
    sendCurrentMessage();`);
  
  // Verify message is in local conversation with pending: true
  assert.equal(run('conversations.bob.length'), 1);
  assert.equal(run('conversations.bob[0].text'), 'Xin chao peer!');
  assert.equal(run('conversations.bob[0].pending'), true);
  assert.equal(sent.length, 0, 'Must not send over connecting channel');
  assert.equal(run('pendingOutgoingMessages.length'), 1);

  // Now simulate channel opening and setupDataChannel
  channel.readyState = 'open';
  run('setupDataChannel(channel); channel.onopen();');
  assert.equal(sent.length, 1);
  const sentPkt = JSON.parse(sent[0]);
  assert.equal(sentPkt.type, 'CHAT_MESSAGE');
  assert.equal(sentPkt.text, 'Xin chao peer!');
  assert.equal(run('conversations.bob[0].pending'), false);
  assert.equal(run('pendingOutgoingMessages.length'), 0);
});

test('bundled client auto-creates default workspace if none exist', async () => {
  const { run, context } = client();
  let createdWorkspace = false;
  context.fetch = async (url, options) => {
    if (options && options.method === 'POST') {
      createdWorkspace = true;
      return { ok: true, status: 201, json: async () => ({ id: 'default-ws', name: 'Nhóm LAN Chung' }) };
    }
    return { ok: true, status: 200, json: async () => [] };
  };
  await run('loadWorkspaces();');
  assert.equal(createdWorkspace, true);
});

