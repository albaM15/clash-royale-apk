import { test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { onRequest as createSession } from '../annotator/functions/api/ingest-session.js';
import { issue, TTL_MS } from '../annotator/functions/_ingest-auth.js';
import { onRequest } from '../annotator/functions/api/ingest.js';
globalThis.crypto ??= webcrypto;
const jpeg = await readFile(new URL('./fixture.jpg', import.meta.url));
const timestamp = '1794580000000';
async function setup() {
  const writes = [];
  const env = { INGEST_SIGNING_KEY: 'test-only-signing-key-at-least-32-characters', CR_VIDEOS: {
    async put(...args) { writes.push(args); return { key: args[0] }; },
  } };
  const ticket = await issue(env, 'session_01-test');
  async function send({ method = 'POST', headers = {}, body = jpeg, ...options } = {}) {
    return onRequest({ env, request: new Request('https://example.test/api/ingest', {
      method, headers: { Authorization: `Bearer ${ticket.token}`, 'Content-Type': 'image/jpeg',
        'X-Session-Id': 'session_01-test', 'X-Capture-Timestamp': timestamp, ...headers },
      ...(method === 'POST' ? { body } : {}), ...options,
    }) });
  }
  return { writes, env, send, ticket };
}
test('JPEG válido, contrato exacto y reintentos usan la misma clave', async () => {
  const { writes, send } = await setup();
  for (let i = 0; i < 2; i++) {
    const response = await send();
    assert.equal(response.status, 201);
    assert.deepEqual(await response.json(), { key: `ingest/session_01-test/${timestamp}.jpg` });
  }
  assert.equal(writes.length, 2);
  assert.equal(writes[0][0], writes[1][0]);
  assert.deepEqual(Buffer.from(writes[0][1]), jpeg);
  assert.equal(writes[0][2].httpMetadata.contentType, 'image/jpeg');
});
for (const value of ['', 'Bearer wrong', 'Basic test-only-token']) {
  test(`autenticación rechazada: ${value || 'ausente'}`, async () => {
    const { writes, send } = await setup();
    assert.equal((await send({ headers: { Authorization: value } })).status, 401);
    assert.equal(writes.length, 0);
  });
}
for (const headers of [
  { 'X-Session-Id': '../escape' }, { 'X-Session-Id': 'a'.repeat(129) },
  { 'X-Capture-Timestamp': '1.2' }, { 'X-Capture-Timestamp': '-1' },
  { 'X-Capture-Timestamp': '9007199254740992' }, { 'X-Capture-Timestamp': '' },
]) {
  test(`identificación inválida: ${JSON.stringify(headers)}`, async () => {
    const { writes, send } = await setup();
    assert.equal((await send({ headers })).status, 400);
    assert.equal(writes.length, 0);
  });
}
test('rechaza tipo, JPEG falso y truncado sin escribir', async () => {
  const { writes, send } = await setup();
  assert.equal((await send({ headers: { 'Content-Type': 'text/plain' } })).status, 415);
  for (const body of ['fake', jpeg.subarray(0, jpeg.length - 2), new Uint8Array([255,216,255,217])]) {
    assert.equal((await send({ body })).status, 400);
  }
  assert.equal(writes.length, 0);
});
test('limita tamaño declarado y real incluso sin Content-Length', async () => {
  const { writes, send } = await setup();
  assert.equal((await send({ headers: { 'Content-Length': '2097153' } })).status, 413);
  const body = new ReadableStream({ start(controller) {
    controller.enqueue(new Uint8Array(2 * 1024 * 1024));
    controller.enqueue(new Uint8Array(1)); controller.close();
  } });
  assert.equal((await send({ body, duplex: 'half' })).status, 413);
  assert.equal(writes.length, 0);
});
test('método, configuración y error R2', async () => {
  const { env, send } = await setup();
  assert.equal((await send({ method: 'GET' })).status, 405);
  env.CR_VIDEOS.put = async () => { throw new Error('private details'); };
  const response = await send();
  assert.equal(response.status, 502);
  assert.doesNotMatch(await response.text(), /private details/);
  env.CR_VIDEOS.put = async () => null;
  assert.equal((await send()).status, 502);
  delete env.INGEST_SIGNING_KEY;
  assert.equal((await send()).status, 503);
});

test('sesión pública automática, IDs únicos y renovación autenticada', async () => {
  const { env } = await setup();
  const request = authorization => new Request('https://example.test/api/ingest-session', {
    method: 'POST', headers: authorization ? { Authorization: authorization } : {},
  });
  const response = await createSession({ env, request: request() });
  assert.equal(response.status, 201);
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  const first = await response.json();
  assert.ok(first.token);
  assert.ok(first.expiresAt > Date.now());
  const second = await (await createSession({ env, request: request() })).json();
  assert.notEqual(first.sessionId, second.sessionId);
  const renewal = await createSession({ env, request: request(`Bearer ${first.token}`) });
  assert.equal(renewal.status, 201);
  assert.equal((await renewal.json()).sessionId, first.sessionId);
  assert.equal((await createSession({ env, request: request('Bearer forged') })).status, 401);
});
test('la credencial no autoriza otra sesión ni acepta firmas alteradas o caducadas', async () => {
  const { env, send, writes, ticket } = await setup();
  assert.equal((await send({ headers: { 'X-Session-Id': 'another-session' } })).status, 403);
  const [payload, signature] = ticket.token.split('.');
  const altered = (signature[0] === 'A' ? 'B' : 'A') + signature.slice(1);
  assert.equal((await send({ headers: { Authorization: `Bearer ${payload}.${altered}` } })).status, 401);
  const realNow = Date.now;
  try {
    Date.now = () => realNow() - TTL_MS - 1000;
    const expired = await issue(env, 'session_01-test');
    Date.now = realNow;
    assert.equal((await send({ headers: { Authorization: `Bearer ${expired.token}` } })).status, 401);
  } finally { Date.now = realNow; }
  assert.equal(writes.length, 0);
});

test('la sesión emitida automáticamente permite subir el JPEG', async () => {
  const { env, send, writes } = await setup();
  const response = await createSession({ env, request: new Request('https://example.test/api/ingest-session', { method: 'POST' }) });
  const ticket = await response.json();
  assert.equal(ticket.expiresIn, 3600);
  const upload = await send({ headers: { Authorization: `Bearer ${ticket.token}`, 'X-Session-Id': ticket.sessionId } });
  assert.equal(upload.status, 201);
  assert.equal(writes[0][0], `ingest/${ticket.sessionId}/${timestamp}.jpg`);
});
