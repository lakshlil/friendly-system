// Med Reminder cloud sync. Stores one end-to-end encrypted blob per sync ID.
// The phone encrypts with a key derived from its sync code; this server only ever sees
//   id   = SHA-256("medreminder-sync-id:" + code)   (64 hex chars)
//   body = {"v":1,"iv":"…","data":"…"}             (AES-GCM ciphertext, base64)
// so it can't read medicines or doses, and can't derive the code from the id.
import { get, put } from "@vercel/blob";

const MAX_BYTES = 1_000_000;
const ID = /^[0-9a-f]{64}$/;

async function readBody(req) {
  const chunks = []; let size = 0;
  for await (const c of req) { size += c.length; if (size > MAX_BYTES) throw Object.assign(new Error("too large"), { status: 413 }); chunks.push(c); }
  return Buffer.concat(chunks).toString("utf8");
}

export default async function handler(req, res) {
  res.setHeader("Cache-Control", "no-store");
  const id = String(req.query.id || "");
  if (!ID.test(id)) return res.status(400).json({ error: "bad id" });
  const path = `sync/${id}.json`;
  try {
    if (req.method === "GET") {
      const b = await get(path, { access: "private", useCache: false });
      if (!b || b.statusCode !== 200) return res.status(404).json({ error: "none" });
      const text = await new Response(b.stream).text();
      return res.status(200).setHeader("Content-Type", "application/json").send(text);
    }
    if (req.method === "PUT") {
      const text = await readBody(req);
      const body = JSON.parse(text);
      if (body.v !== 1 || typeof body.iv !== "string" || typeof body.data !== "string") return res.status(400).json({ error: "bad body" });
      await put(path, text, { access: "private", allowOverwrite: true, addRandomSuffix: false, contentType: "application/json" });
      return res.status(200).json({ ok: true, at: Date.now() });
    }
    res.setHeader("Allow", "GET, PUT");
    return res.status(405).json({ error: "method" });
  } catch (e) {
    if (e.status === 413) return res.status(413).json({ error: "too large" });
    if (e instanceof SyntaxError) return res.status(400).json({ error: "bad json" });
    if (/not found/i.test(String(e && e.message))) return res.status(404).json({ error: "none" });
    console.error(e);
    return res.status(500).json({ error: "server" });
  }
}
