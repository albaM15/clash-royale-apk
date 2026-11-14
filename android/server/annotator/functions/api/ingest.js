import { configured, json, verify } from '../_ingest-auth.js';

const MAX_BYTES = 2 * 1024 * 1024;
// Valida marcadores, longitudes, dimensiones y al menos un scan. No decodifica píxeles.
function isJpeg(bytes) {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return false;
  let offset = 2, dimensions = false, scan = false;
  while (offset < bytes.length) {
    if (bytes[offset++] !== 0xff) return false;
    while (bytes[offset] === 0xff) offset++;
    const marker = bytes[offset++];
    if (marker === 0xd9) return dimensions && scan && offset === bytes.length;
    if (marker === undefined || marker === 0 || marker === 0xd8) return false;
    if (offset + 2 > bytes.length) return false;
    const length = (bytes[offset] << 8) | bytes[offset + 1];
    if (length < 2 || offset + length > bytes.length) return false;
    if ([0xc0, 0xc1, 0xc2].includes(marker)) {
      if (length < 8 || length !== 8 + 3 * bytes[offset + 7]) return false;
      const height = (bytes[offset + 3] << 8) | bytes[offset + 4];
      const width = (bytes[offset + 5] << 8) | bytes[offset + 6];
      if (!width || !height || width > 8192 || height > 8192) return false;
      dimensions = true;
    }
    offset += length;
    if (marker === 0xda) {
      if (!dimensions || length < 6) return false;
      scan = true;
      while (offset < bytes.length) {
        if (bytes[offset] !== 0xff) { offset++; continue; }
        const next = bytes[offset + 1];
        if (next === 0 || (next >= 0xd0 && next <= 0xd7)) { offset += 2; continue; }
        if (next === 0xff) { offset++; continue; }
        break;
      }
    }
  }
  return false;
}

export async function onRequest({ request, env }) {
  if (request.method !== "POST") {
    return new Response(JSON.stringify({ error: "Usa POST" }), {
      status: 405, headers: { Allow: "POST", "Content-Type": "application/json", "Cache-Control": "no-store" },
    });
  }
  if (!configured(env)) return json(503, { error: "Ingesta no configurada" });
  const ticket = await verify(env, request.headers.get("Authorization"));
  if (!ticket) return json(401, { error: "Sesión inválida o caducada" });
  if (request.headers.get("Content-Type")?.toLowerCase() !== "image/jpeg") {
    return json(415, { error: "Se requiere Content-Type: image/jpeg" });
  }
  const session = request.headers.get("X-Session-Id") || "";
  const timestamp = request.headers.get("X-Capture-Timestamp") || "";
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(session) || !/^[1-9][0-9]{0,15}$/.test(timestamp) ||
      !Number.isSafeInteger(Number(timestamp)) || Number(timestamp) > 8640000000000000) {
    return json(400, { error: "Sesión o timestamp en milisegundos inválido" });
  }
  if (ticket.sessionId !== session) return json(403, { error: "La credencial pertenece a otra sesión" });
  const declared = request.headers.get("Content-Length");
  if (declared !== null && (!/^[0-9]+$/.test(declared) || Number(declared) > MAX_BYTES)) {
    return json(413, { error: "El JPEG supera 2 MiB o su tamaño es inválido" });
  }
  if (!request.body) return json(400, { error: "Falta el JPEG" });
  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_BYTES) {
        await reader.cancel();
        return json(413, { error: "El JPEG supera 2 MiB" });
      }
      chunks.push(value);
    }
  } catch {
    return json(400, { error: "No se pudo leer el JPEG" });
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  if (!isJpeg(bytes)) return json(400, { error: "Cuerpo JPEG inválido" });
  const key = `ingest/${session}/${timestamp}.jpg`;
  try {
    const object = await env.CR_VIDEOS.put(key, bytes, {
      httpMetadata: { contentType: "image/jpeg" },
      customMetadata: { session, captureTimestamp: timestamp },
    });
    if (!object) throw new Error("R2 no confirmó la escritura");
    return json(201, { key });
  } catch {
    return json(502, { error: "No se pudo guardar el JPEG en R2" });
  }
}
