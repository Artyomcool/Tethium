const ID = /^[A-Za-z0-9_-]{32,86}$/;

async function ticket(secret, room, role) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name:"HMAC", hash:"SHA-256" }, false, ["sign"]);
  const signed = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${room}\n${role}`)));
  return btoa(String.fromCharCode(...signed)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function denied(status = 401, message = "denied") {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" }
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return Response.json({ ok: true, protocol: 1 });
    const match = url.pathname.match(/^\/v1\/connect\/([A-Za-z0-9_-]+)$/);
    if (!match || request.headers.get("upgrade")?.toLowerCase() !== "websocket") return denied(404, "not_found");
    const room = match[1], role = request.headers.get("x-tethium-role"), auth = request.headers.get("x-tethium-auth");
    if (!ID.test(room) || !ID.test(auth || "") || (role !== "host" && role !== "phone")) return denied(400, "bad_handshake");
    const supplied = request.headers.get("x-tethium-ticket") || "", expected = env.GATEWAY_ADMISSION && await ticket(env.GATEWAY_ADMISSION, room, role);
    if (!expected || supplied.length !== expected.length || supplied !== expected) return denied();
    return env.LINKS.getByName(room).fetch(request);
  }
};

export class LinkRoom {
  constructor(ctx) { this.ctx = ctx; }

  async fetch(request) {
    const role = request.headers.get("x-tethium-role"), auth = request.headers.get("x-tethium-auth");
    let expected = await this.ctx.storage.get("auth");
    if (!expected) {
      if (role !== "host") return denied(409, "host_not_registered");
      await this.ctx.storage.put("auth", auth); expected = auth;
    }
    if (auth !== expected) return denied();
    const pair = new WebSocketPair(), client = pair[0], server = pair[1];
    for (const old of this.ctx.getWebSockets(role)) old.close(4001, "replaced");
    this.ctx.acceptWebSocket(server, [role]);
    server.serializeAttachment({ role });
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(source, message) {
    const role = source.deserializeAttachment()?.role;
    const target = role === "host" ? "phone" : "host";
    for (const socket of this.ctx.getWebSockets(target)) {
      try { socket.send(message); } catch { /* closing peer */ }
    }
  }

  webSocketClose(socket, code, reason) { socket.close(code, reason); }
  webSocketError(socket) { socket.close(1011, "relay_error"); }
}
