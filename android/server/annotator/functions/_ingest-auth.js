// La clave de firma existe solo como secreto del servidor, nunca en la app.
export const TTL_MS = 60 * 60 * 1000;
export const configured = env => typeof env.INGEST_SIGNING_KEY === 'string' &&
  env.INGEST_SIGNING_KEY.length >= 32 && !!env.CR_VIDEOS;
export const json = (status, body) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store' },
});
const encoder = new TextEncoder();
const encode = bytes => btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
const decode = value => Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0));
async function signingKey(env) {
  return crypto.subtle.importKey('raw', encoder.encode(env.INGEST_SIGNING_KEY),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}
export async function issue(env, sessionId) {
  const expiresAt = Date.now() + TTL_MS;
  const payload = encode(encoder.encode(JSON.stringify({ v: 1, sessionId, expiresAt })));
  const signature = await crypto.subtle.sign('HMAC', await signingKey(env), encoder.encode(payload));
  return { sessionId, expiresAt, expiresIn: TTL_MS / 1000, token: `${payload}.${encode(new Uint8Array(signature))}` };
}
export async function verify(env, authorization) {
  try {
    if (!authorization?.startsWith('Bearer ') || authorization.length > 2048) return null;
    const parts = authorization.slice(7).split('.');
    if (parts.length !== 2 || parts.some(p => !/^[A-Za-z0-9_-]+$/.test(p))) return null;
    const [payload, signature] = parts;
    if (!await crypto.subtle.verify('HMAC', await signingKey(env), decode(signature), encoder.encode(payload))) return null;
    const data = JSON.parse(new TextDecoder().decode(decode(payload)));
    if (data.v !== 1 || !/^[A-Za-z0-9_-]{1,128}$/.test(data.sessionId) ||
        !Number.isSafeInteger(data.expiresAt) || data.expiresAt <= Date.now() ||
        data.expiresAt > Date.now() + TTL_MS) return null;
    return data;
  } catch { return null; }
}
