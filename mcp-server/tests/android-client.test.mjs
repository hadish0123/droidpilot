import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocketServer } from 'ws';
import { AndroidClient } from '../dist/android-client.js';

const TEST_TOKEN = 'prime-test-token-0123456789-abcdefghijklmnopqrstuvwxyz';

async function device(t, handler) {
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  await once(server, 'listening');
  server.on('connection', (socket, request) => {
    assert.equal(request.headers.authorization, `Bearer ${TEST_TOKEN}`);
    socket.on('message', bytes => handler(socket, JSON.parse(bytes)));
  });
  const client = new AndroidClient('127.0.0.1', server.address().port, TEST_TOKEN);
  t.after(async () => {
    client.disconnect();
    for (const socket of server.clients) socket.terminate();
    await new Promise(resolve => server.close(resolve));
  });
  return { server, client };
}

test('round trip preserves Persian text', async t => {
  const { client } = await device(t, (socket, req) => socket.send(JSON.stringify({ id: req.id, success: true, data: req.params })));
  await client.connect();
  const response = await client.sendCommand('type_text', { text: 'سلام دنیا' });
  assert.equal(response.data.text, 'سلام دنیا');
});

test('concurrent connects use one socket', async t => {
  const { client, server } = await device(t, () => {});
  let connections = 0;
  server.on('connection', () => connections++);
  await Promise.all([client.connect(), client.connect(), client.connect()]);
  assert.equal(connections, 1);
});

test('closed connection rejects pending commands', async t => {
  const { client } = await device(t, socket => socket.close());
  await client.connect();
  await assert.rejects(client.sendCommand('tap'), /Connection closed/);
  assert.equal(client.connected, false);
});

test('malformed correlated response fails promptly', async t => {
  const { client } = await device(t, (socket, req) => socket.send(JSON.stringify({ id: req.id })));
  await client.connect();
  await assert.rejects(client.sendCommand('tap'), /Invalid Android command response/);
});

test('timeout is reported without retaining a request', async t => {
  const { client } = await device(t, () => {});
  await client.connect();
  await assert.rejects(client.sendCommand('ping', {}, 20), /timed out/);
});

test('refused connection rejects without requiring an error listener', async t => {
  const { client, server } = await device(t, () => {});
  await new Promise(resolve => server.close(resolve));
  await assert.rejects(client.connect());
});

test('host validation, authentication and IPv6 formatting', () => {
  assert.throws(() => new AndroidClient('example.com/path', 8765, TEST_TOKEN), /Invalid/);
  assert.throws(() => new AndroidClient('localhost', 99999, TEST_TOKEN), /Invalid/);
  assert.throws(() => new AndroidClient('localhost', 8765, ''), /authentication token/);
  assert.equal(new AndroidClient('::1', 8765, TEST_TOKEN).url, 'ws://[::1]:8765');
});
