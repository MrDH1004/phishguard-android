// PhishGuard 가족 연결 중계 서버
//
// 자녀 폰(보호자)이 PIN을 만들고, 부모 폰(보호받는 쪽)이 그 PIN을 입력해 연결한다.
// 부모 폰은 위험 앱 알림·상태를 올리고, 자녀 폰은 WebSocket으로 거의 실시간으로 받는다.
//
// 저장하는 것: 기기 이름, 토큰 해시, 알림(앱 이름·패키지명·위험도·근거), 마지막 신호 시각.
// 저장하지 않는 것: 전화번호, 연락처, 통화기록, 문자, 위치.
//
// 토큰 형식: "<familyId 32hex>.<secret 64hex>". 서버는 secret의 SHA-256만 보관한다.
// 부모 토큰의 secret은 부모 폰이 만들어 보낸다 → 응답이 유실돼 다시 보내도 같은 결과(멱등).

import { DurableObject } from "cloudflare:workers";

const HOUR = 60 * 60 * 1000;
const PIN_TTL_MS = 10 * 60 * 1000;
const PIN_USED_GRACE_MS = 5 * 60 * 1000; // 같은 부모가 응답을 못 받고 다시 보내는 경우 허용
const JOIN_FAIL_LIMIT_PER_SOURCE = 20; // 1시간당 (IPv4 주소 또는 IPv6 /64)
const START_LIMIT_PER_SOURCE = 30; // 1시간당 가족 생성
const UNPAIRED_FAMILY_TTL_MS = HOUR;
const SILENT_AFTER_MS = 24 * HOUR;
const ALARM_EVERY_MS = HOUR;
const MAX_EVENTS = 200;
const MAX_BODY = 32 * 1024;

// ---- 공용 ----

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function err(status, code, message) {
  return json({ error: code, message }, status);
}

function hex(bytes) {
  return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomHex(n) {
  return hex(crypto.getRandomValues(new Uint8Array(n)));
}

async function sha256Hex(text) {
  return hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
}

/** 길이가 같은 두 hex 문자열을 일정한 시간에 비교 */
function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** 제어 문자와 양방향(RTL) 제어 문자를 지워 알림 문구가 조작돼 보이지 않게 한다 */
function cleanText(v, max) {
  if (typeof v !== "string") return "";
  return v
    .replace(/[\u0000-\u001f\u007f-\u009f؜‎‏‪-‮⁦-⁩]/g, " ")
    .trim()
    .slice(0, max);
}

async function readJson(request) {
  const len = Number(request.headers.get("content-length") || "0");
  if (len > MAX_BODY) throw new HttpError(413, "too_large", "요청이 너무 큽니다");
  const text = await request.text();
  if (text.length > MAX_BODY) throw new HttpError(413, "too_large", "요청이 너무 큽니다");
  if (!text) return {};
  try {
    const v = JSON.parse(text);
    return v && typeof v === "object" ? v : {};
  } catch {
    throw new HttpError(400, "bad_json", "JSON 형식이 아닙니다");
  }
}

class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function parseToken(request) {
  const auth = request.headers.get("authorization") || "";
  const m = /^Bearer ([0-9a-f]{32})\.([0-9a-f]{64})$/.exec(auth.trim());
  if (!m) throw new HttpError(401, "unknown_token", "연결 정보가 없습니다");
  return { familyId: m[1], secret: m[2] };
}

/** 속도 제한 단위: IPv4는 주소, IPv6는 /64 (한 사용자가 주소를 무한히 바꿀 수 있으므로) */
function sourceKey(request) {
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  if (!ip.includes(":")) return ip;
  const full = expandIpv6(ip);
  return full ? full.split(":").slice(0, 4).join(":") + "::/64" : ip;
}

function expandIpv6(ip) {
  const parts = ip.split("::");
  if (parts.length > 2) return null;
  const head = parts[0] ? parts[0].split(":") : [];
  const tail = parts.length === 2 && parts[1] ? parts[1].split(":") : [];
  const fill = parts.length === 2 ? 8 - head.length - tail.length : 0;
  if (fill < 0) return null;
  return [...head, ...Array(fill).fill("0"), ...tail].map((h) => h.padStart(4, "0")).join(":");
}

function familyStub(env, familyId) {
  return env.FAMILY.get(env.FAMILY.idFromName(familyId));
}

function pinStub(env) {
  return env.PINS.get(env.PINS.idFromName("pins-v2"));
}

// ---- Worker 진입점 ----

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (e) {
      if (e instanceof HttpError) return err(e.status, e.code, e.message);
      console.error("unhandled", e && e.stack ? e.stack : e);
      return err(500, "server_error", "서버 오류");
    }
  },
};

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;

  if (path === "/" || path === "/v1/health") return json({ ok: true, service: "phishguard-family" });

  // 자녀: 새 가족 + PIN 만들기
  if (path === "/v1/pair/start" && method === "POST") {
    const body = await readJson(request);
    const pins = pinStub(env);
    if (!(await pins.allowStart(sourceKey(request)))) throw new HttpError(429, "rate_limited", "잠시 후 다시 시도하세요");
    const childName = cleanText(body.name, 40) || "자녀 폰";
    const familyId = randomHex(16);
    const secret = randomHex(32);
    const init = await familyStub(env, familyId).init(await sha256Hex(secret), childName);
    if (!init.ok) throw new HttpError(500, "init_failed", "가족 생성 실패");
    const { pin, expiresAt } = await pins.create(familyId);
    return json({ familyId, token: `${familyId}.${secret}`, pin, expiresAt });
  }

  // 자녀: PIN 다시 만들기 (아직 연결 전). 이전 PIN은 바로 무효, 가족 만료도 연장
  if (path === "/v1/pair/pin" && method === "POST") {
    const t = parseToken(request);
    const fam = familyStub(env, t.familyId);
    const who = await fam.auth(await sha256Hex(t.secret));
    if (!who.role) throw new HttpError(401, "unknown_token", "연결이 끊겼거나 없는 연결입니다");
    if (who.role !== "child") throw new HttpError(403, "forbidden", "권한이 없습니다");
    if (who.paired) throw new HttpError(409, "already_paired", "이미 연결됐습니다");
    const { pin, expiresAt } = await pinStub(env).create(t.familyId);
    await fam.extendUnpaired(expiresAt + PIN_USED_GRACE_MS);
    return json({ pin, expiresAt });
  }

  // 부모: PIN 입력해 연결. secret은 부모 폰이 만든 64hex
  if (path === "/v1/pair/join" && method === "POST") {
    const body = await readJson(request);
    const pin = typeof body.pin === "string" ? body.pin.trim() : "";
    if (!/^\d{6}$/.test(pin)) throw new HttpError(400, "bad_pin", "PIN은 숫자 6자리입니다");
    const secret = typeof body.secret === "string" ? body.secret : "";
    if (!/^[0-9a-f]{64}$/.test(secret)) throw new HttpError(400, "bad_secret", "앱을 최신 버전으로 업데이트하세요");
    const parentHash = await sha256Hex(secret);
    const r = await pinStub(env).consume(pin, sourceKey(request), parentHash);
    if (!r.ok) {
      const status = r.code === "rate_limited" ? 429 : 404;
      throw new HttpError(status, r.code, r.code === "rate_limited" ? "PIN을 여러 번 틀렸습니다. 1시간 뒤에 다시 시도하세요" : "PIN이 맞지 않거나 만료됐습니다");
    }
    const parentName = cleanText(body.name, 40) || "부모 폰";
    const joined = await familyStub(env, r.familyId).join(parentHash, parentName);
    if (!joined.ok) {
      if (joined.code === "gone") throw new HttpError(410, "gone", "PIN이 만료됐습니다. 자녀 폰에서 새 PIN을 만드세요");
      throw new HttpError(409, joined.code, "이미 다른 폰과 연결된 PIN입니다");
    }
    return json({ familyId: r.familyId, token: `${r.familyId}.${secret}`, childName: joined.childName });
  }

  // 그 외는 모두 토큰 필요 → 가족 DO로 전달 (본문은 여기서 다 읽어 DO 안에서 기다리지 않게 한다)
  if (path === "/v1/state" || path === "/v1/events" || path === "/v1/family" || path === "/v1/ws") {
    const t = parseToken(request);
    const fam = familyStub(env, t.familyId);
    const headers = new Headers(request.headers);
    headers.set("x-secret-hash", await sha256Hex(t.secret));
    headers.delete("authorization");
    let body;
    if (method === "POST") body = JSON.stringify(await readJson(request));
    return fam.fetch(new Request(request.url, { method, headers, body }));
  }

  return err(404, "not_found", "없는 주소입니다");
}

// ---- PIN 목록 (전역 하나) ----

export class PinRegistry extends DurableObject {
  async allowStart(source) {
    const now = Date.now();
    const key = `start:${source}`;
    const cur = (await this.ctx.storage.get(key)) || { count: 0, since: now };
    if (now - cur.since > HOUR) Object.assign(cur, { count: 0, since: now });
    if (cur.count >= START_LIMIT_PER_SOURCE) return false;
    cur.count++;
    await this.ctx.storage.put(key, cur);
    await this.ensureAlarm();
    return true;
  }

  async create(familyId) {
    const now = Date.now();
    // 같은 가족의 이전 PIN은 바로 무효
    const prev = await this.ctx.storage.get(`fam:${familyId}`);
    if (prev) await this.ctx.storage.delete(`pin:${prev}`);
    for (let i = 0; i < 30; i++) {
      const n = crypto.getRandomValues(new Uint32Array(1))[0] % 1000000;
      const pin = String(n).padStart(6, "0");
      const cur = await this.ctx.storage.get(`pin:${pin}`);
      if (cur && cur.expiresAt > now) continue;
      const expiresAt = now + PIN_TTL_MS;
      await this.ctx.storage.put({ [`pin:${pin}`]: { familyId, expiresAt }, [`fam:${familyId}`]: pin });
      await this.ensureAlarm();
      return { pin, expiresAt };
    }
    throw new Error("pin space exhausted");
  }

  async consume(pin, source, parentHash) {
    const now = Date.now();
    // 응답을 못 받은 같은 부모의 재시도 (같은 secret 해시) → 같은 결과
    const used = await this.ctx.storage.get(`used:${pin}`);
    if (used && used.until > now && safeEqual(used.parentHash, parentHash)) return { ok: true, familyId: used.familyId };

    const failKey = `fail:${source}`;
    const fail = (await this.ctx.storage.get(failKey)) || { count: 0, since: now };
    if (now - fail.since > HOUR) Object.assign(fail, { count: 0, since: now });
    if (fail.count >= JOIN_FAIL_LIMIT_PER_SOURCE) return { ok: false, code: "rate_limited" };

    const entry = await this.ctx.storage.get(`pin:${pin}`);
    if (!entry || entry.expiresAt <= now) {
      fail.count++;
      await this.ctx.storage.put(failKey, fail);
      if (entry) await this.ctx.storage.delete(`pin:${pin}`);
      await this.ensureAlarm();
      return { ok: false, code: "pin_not_found" };
    }
    await this.ctx.storage.delete([`pin:${pin}`, `fam:${entry.familyId}`]);
    await this.ctx.storage.put(`used:${pin}`, { familyId: entry.familyId, parentHash, until: now + PIN_USED_GRACE_MS });
    await this.ensureAlarm();
    return { ok: true, familyId: entry.familyId };
  }

  async ensureAlarm() {
    if ((await this.ctx.storage.getAlarm()) == null) await this.ctx.storage.setAlarm(Date.now() + PIN_TTL_MS);
  }

  async alarm() {
    const now = Date.now();
    const all = await this.ctx.storage.list();
    const dead = [];
    let alive = 0;
    for (const [k, v] of all) {
      if (k.startsWith("pin:") && v.expiresAt <= now) {
        dead.push(k);
        // 가족이 그 사이 새 PIN을 받았으면 새 연결 정보는 남긴다
        if (all.get(`fam:${v.familyId}`) === k.slice(4)) dead.push(`fam:${v.familyId}`);
      } else if (k.startsWith("used:") && v.until <= now) dead.push(k);
      else if ((k.startsWith("fail:") || k.startsWith("start:")) && now - v.since > HOUR) dead.push(k);
      else if (!k.startsWith("fam:")) alive++;
    }
    for (let i = 0; i < dead.length; i += 128) await this.ctx.storage.delete(dead.slice(i, i + 128));
    if (alive) await this.ctx.storage.setAlarm(now + PIN_TTL_MS);
  }
}

// ---- 가족 하나 (자녀 1 + 부모 1) ----
// 저장 키: meta, seq, ev:<12자리 seq> (사건마다 키 하나), gone (끊긴 뒤 되살아남 방지 표식)

const evKey = (seq) => `ev:${String(seq).padStart(12, "0")}`;

export class Family extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    // 앱 수준 ping은 DO를 깨우지 않고 바로 응답 (WebSocket 절전 유지)
    this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async meta() {
    if (await this.ctx.storage.get("gone")) return null;
    return (await this.ctx.storage.get("meta")) || null;
  }

  async init(childHash, childName) {
    if ((await this.ctx.storage.get("meta")) || (await this.ctx.storage.get("gone"))) return { ok: false };
    const now = Date.now();
    await this.ctx.storage.put({
      meta: {
        childHash, childName, parentHash: null, parentName: null,
        createdAt: now, expiresAt: now + UNPAIRED_FAMILY_TTL_MS,
        pairedAt: null, lastHeartbeat: null, heartbeat: null, silentNotified: false,
      },
      seq: 0,
    });
    await this.ctx.storage.setAlarm(now + UNPAIRED_FAMILY_TTL_MS);
    return { ok: true };
  }

  async extendUnpaired(until) {
    const m = await this.meta();
    if (!m || m.parentHash) return;
    m.expiresAt = Math.max(m.expiresAt || 0, until);
    await this.ctx.storage.put("meta", m);
    await this.ctx.storage.setAlarm(m.expiresAt);
  }

  async auth(hash) {
    const m = await this.meta();
    if (!m) return { role: null };
    if (safeEqual(hash, m.childHash)) return { role: "child", paired: !!m.parentHash };
    if (m.parentHash && safeEqual(hash, m.parentHash)) return { role: "parent", paired: true };
    return { role: null };
  }

  async join(parentHash, parentName) {
    const m = await this.meta();
    if (!m) return { ok: false, code: "gone" };
    if (m.parentHash) {
      // 같은 부모의 재시도면 성공으로 처리
      if (safeEqual(m.parentHash, parentHash)) return { ok: true, childName: m.childName };
      return { ok: false, code: "already_paired" };
    }
    const now = Date.now();
    m.parentHash = parentHash;
    m.parentName = parentName;
    m.pairedAt = now;
    m.lastHeartbeat = now;
    await this.ctx.storage.put("meta", m);
    await this.addEvent("paired", { parentName });
    await this.ctx.storage.setAlarm(now + ALARM_EVERY_MS);
    return { ok: true, childName: m.childName };
  }

  async addEvent(type, data) {
    const seq = ((await this.ctx.storage.get("seq")) || 0) + 1;
    const ev = { id: seq, ts: Date.now(), type, data: data || {} };
    await this.ctx.storage.put({ seq, [evKey(seq)]: ev });
    if (seq > MAX_EVENTS) await this.ctx.storage.delete(evKey(seq - MAX_EVENTS));
    this.broadcast({ kind: "event", event: ev });
    return ev;
  }

  async eventsSince(sinceId) {
    const list = await this.ctx.storage.list({ prefix: "ev:", start: evKey(sinceId + 1) });
    return [...list.values()];
  }

  broadcast(msg) {
    const text = JSON.stringify(msg);
    for (const ws of this.ctx.getWebSockets("child")) {
      try {
        ws.send(text);
      } catch (e) {
        console.warn("ws send failed", e);
      }
    }
  }

  closeAll(code, reason) {
    for (const ws of this.ctx.getWebSockets()) {
      try {
        ws.close(code, reason);
      } catch (e) {
        console.warn("ws close failed", e);
      }
    }
  }

  async wipe() {
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
    // 끊긴 뒤 늦게 도착한 요청이 옛 정보를 되살리지 못하게 표식만 남긴다 (식별 정보 없음)
    await this.ctx.storage.put("gone", true);
  }

  async stateFor(m, sinceId) {
    return {
      paired: !!m.parentHash,
      childName: m.childName,
      parentName: m.parentName,
      pairedAt: m.pairedAt,
      lastHeartbeat: m.lastHeartbeat,
      heartbeat: m.heartbeat,
      events: await this.eventsSince(sinceId),
    };
  }

  async fetch(request) {
    try {
      return await this.handle(request);
    } catch (e) {
      if (e instanceof HttpError) return err(e.status, e.code, e.message);
      console.error("family error", e && e.stack ? e.stack : e);
      return err(500, "server_error", "서버 오류");
    }
  }

  async handle(request) {
    const url = new URL(request.url);
    // 본문을 먼저 다 읽는다: 이후는 저장소 작업만 이어지므로 중간에 연결 끊기 요청이 끼어들지 못한다
    const body = request.method === "POST" ? await readJson(request) : null;
    const who = await this.auth(request.headers.get("x-secret-hash") || "");
    if (!who.role) throw new HttpError(401, "unknown_token", "연결이 끊겼거나 없는 연결입니다");
    const m = await this.meta();
    if (!m) throw new HttpError(401, "unknown_token", "연결이 끊겼거나 없는 연결입니다");
    const sinceId = Number(url.searchParams.get("since") || "0") || 0;

    if (url.pathname === "/v1/ws") {
      if (who.role !== "child") throw new HttpError(403, "forbidden", "자녀 폰만 연결할 수 있습니다");
      if ((request.headers.get("upgrade") || "").toLowerCase() !== "websocket") {
        throw new HttpError(426, "upgrade_required", "WebSocket 필요");
      }
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.ctx.acceptWebSocket(server, ["child"]);
      server.send(JSON.stringify({ kind: "state", state: await this.stateFor(m, sinceId) }));
      return new Response(null, { status: 101, webSocket: client });
    }

    if (url.pathname === "/v1/state" && request.method === "GET") {
      return json({ role: who.role, ...(await this.stateFor(m, sinceId)) });
    }

    if (url.pathname === "/v1/events" && request.method === "POST") {
      if (who.role !== "parent") throw new HttpError(403, "forbidden", "부모 폰만 보낼 수 있습니다");
      const type = body.type;
      const now = Date.now();
      if (type === "heartbeat") {
        const hb = sanitizeHeartbeat(body.data);
        const wasSilent = m.silentNotified;
        m.lastHeartbeat = now;
        m.heartbeat = hb;
        m.silentNotified = false;
        await this.ctx.storage.put("meta", m);
        if (wasSilent) await this.addEvent("parent_back", {});
        this.broadcast({ kind: "heartbeat", lastHeartbeat: now, heartbeat: hb });
        return json({ ok: true });
      }
      if (type === "alert" || type === "snapshot") {
        const data = type === "alert" ? sanitizeAlert(body.data) : sanitizeSnapshot(body.data);
        const wasSilent = m.silentNotified;
        m.lastHeartbeat = now;
        m.silentNotified = false;
        await this.ctx.storage.put("meta", m);
        if (wasSilent) await this.addEvent("parent_back", {});
        const ev = await this.addEvent(type, data);
        return json({ ok: true, id: ev.id });
      }
      throw new HttpError(400, "bad_type", "알 수 없는 종류");
    }

    if (url.pathname === "/v1/family" && request.method === "DELETE") {
      // 어느 쪽이든 연결 끊기. 남은 쪽(자녀)에게 먼저 알린 뒤 모두 지운다.
      this.broadcast({ kind: "event", event: { id: 0, ts: Date.now(), type: "unlinked", data: { by: who.role } } });
      this.closeAll(4000, "unlinked");
      await this.wipe();
      return json({ ok: true });
    }

    throw new HttpError(404, "not_found", "없는 주소입니다");
  }

  async alarm() {
    const m = await this.meta();
    if (!m) return;
    const now = Date.now();
    if (!m.parentHash) {
      const exp = m.expiresAt || m.createdAt + UNPAIRED_FAMILY_TTL_MS;
      if (now >= exp) {
        this.closeAll(4001, "expired");
        await this.wipe();
        return;
      }
      await this.ctx.storage.setAlarm(exp);
      return;
    }
    if (!m.silentNotified && m.lastHeartbeat && now - m.lastHeartbeat > SILENT_AFTER_MS) {
      m.silentNotified = true;
      await this.ctx.storage.put("meta", m);
      await this.addEvent("parent_silent", { lastHeartbeat: m.lastHeartbeat });
    }
    await this.ctx.storage.setAlarm(now + ALARM_EVERY_MS);
  }

  async webSocketMessage(ws, message) {
    // 자녀 폰은 "ping" 외에 보낼 것이 없다 (ping은 자동 응답). 그 밖의 메시지는 무시.
  }

  async webSocketClose(ws, code, reason, wasClean) {
    try {
      ws.close(code, reason);
    } catch {
      // 이미 닫힘
    }
  }
}

// ---- 입력 정리 ----

const LEVELS = new Set(["HIGH", "MEDIUM", "LOW"]);

function sanitizeApp(a) {
  if (!a || typeof a !== "object") return null;
  return {
    packageName: cleanText(a.packageName, 200),
    label: cleanText(a.label, 80),
    riskLevel: LEVELS.has(a.riskLevel) ? a.riskLevel : "LOW",
    riskScore: Number.isFinite(a.riskScore) ? Math.max(0, Math.min(9999, Math.round(a.riskScore))) : 0,
    installSource: cleanText(a.installSource, 40),
    firstInstallTime: Number.isFinite(a.firstInstallTime) ? a.firstInstallTime : 0,
    reasons: Array.isArray(a.reasons) ? a.reasons.slice(0, 5).map((r) => cleanText(r, 120)).filter(Boolean) : [],
    remoteControl: a.remoteControl === true,
  };
}

function sanitizeAlert(d) {
  const app = sanitizeApp(d && d.app);
  if (!app || !app.packageName) throw new HttpError(400, "bad_alert", "앱 정보가 없습니다");
  const kind = d.kind === "installed" || d.kind === "risk_up" ? d.kind : "installed";
  return { kind, app };
}

function sanitizeSnapshot(d) {
  const apps = Array.isArray(d && d.apps) ? d.apps.slice(0, 50).map(sanitizeApp).filter((a) => a && a.packageName) : [];
  return { apps, scannedAt: Number.isFinite(d && d.scannedAt) ? d.scannedAt : Date.now() };
}

function sanitizeHeartbeat(d) {
  const o = d && typeof d === "object" ? d : {};
  const n = (v) => (Number.isFinite(v) ? Math.max(0, Math.min(9999, Math.round(v))) : 0);
  return {
    high: n(o.high),
    medium: n(o.medium),
    appVersion: cleanText(o.appVersion, 20),
    guardOn: o.guardOn !== false,
  };
}
