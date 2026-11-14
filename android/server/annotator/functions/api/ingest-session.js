import { configured, issue, json, verify } from '../_ingest-auth.js';

// Sesiones anónimas: el usuario no introduce contraseñas ni crea una cuenta.
// Aplicar límites de peticiones en Cloudflare a esta ruta pública y a /api/ingest.
export async function onRequest({ request, env }) {
  if (request.method !== 'POST') return new Response(null, { status: 405, headers: { Allow: 'POST' } });
  if (!configured(env)) return json(503, { error: 'Ingesta no configurada' });
  try {
    const authorization = request.headers.get('Authorization');
    if (authorization !== null) {
      const previous = await verify(env, authorization);
      if (!previous) return json(401, { error: 'Sesión inválida o caducada' });
      return json(201, await issue(env, previous.sessionId));
    }
    // Nunca emitir una credencial para un ID elegido por un tercero.
    return json(201, await issue(env, crypto.randomUUID()));
  } catch { return json(503, { error: 'No se pudo iniciar la sesión' }); }
}
