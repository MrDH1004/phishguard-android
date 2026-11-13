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
const APPROVAL_TTL_MS = 30 * 60 * 1000; // 부모가 PIN을 넣은 뒤 자녀가 승인할 시간
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
      if (joined.code === "rejected") throw new HttpError(403, "rejected", "자녀 폰이 이 연결을 거절했습니다");
      throw new HttpError(409, joined.code, "이미 다른 폰과 연결된 PIN입니다");
    }
    return json({ familyId: r.familyId, token: `${r.familyId}.${secret}`, childName: joined.childName });
  }

  // 그 외는 모두 토큰 필요 → 가족 DO로 전달 (본문은 여기서 다 읽어 DO 안에서 기다리지 않게 한다)
  if (["/v1/state", "/v1/events", "/v1/family", "/v1/ws", "/v1/pair/approve", "/v1/commands"].includes(path)) {
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
// 저장 키: meta, seq/ev:<seq> (자녀가 받는 사건), cseq/cmd:<seq> (부모가 받는 명령), gone (끊긴 뒤 되살아남 방지 표식)

const evKey = (seq) => `ev:${String(seq).padStart(12, "0")}`;
const cmdKey = (seq) => `cmd:${String(seq).padStart(12, "0")}`;

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
        pairedAt: null, approved: false, lastHeartbeat: null, heartbeat: null, silentNotified: false,
      },
      seq: 0,
    });
    await this.ctx.storage.setAlarm(now + UNPAIRED_FAMILY_TTL_MS);
    return { ok: true };
  }

  async extendUnpaired(until) {
    const m = await this.meta();
    if (!m || m.approved) return;
    m.expiresAt = Math.max(m.expiresAt || 0, until);
    await this.ctx.storage.put("meta", m);
    await this.ctx.storage.setAlarm(m.expiresAt);
  }

  async auth(hash) {
    const m = await this.meta();
    if (!m) return { role: null };
    if (safeEqual(hash, m.childHash)) return { role: "child", paired: !!m.approved };
    if (m.parentHash && safeEqual(hash, m.parentHash)) return { role: "parent", paired: !!m.approved };
    return { role: null };
  }

  /** 부모가 PIN을 입력함 → 자녀 승인 대기. 자녀가 승인해야 알림·명령이 오간다 */
  async join(parentHash, parentName) {
    const m = await this.meta();
    if (!m) return { ok: false, code: "gone" };
    if (m.parentHash) {
      // 같은 부모의 재시도면 성공으로 처리
      if (safeEqual(m.parentHash, parentHash)) return { ok: true, childName: m.childName };
      return { ok: false, code: "already_paired" };
    }
    // 자녀가 거절한 폰은 같은 비밀값으로 다시 요청할 수 없다 (PIN 재시도 유예 시간 악용 방지)
    if ((m.rejectedHashes || []).some((h) => safeEqual(h, parentHash))) return { ok: false, code: "rejected" };
    const now = Date.now();
    m.parentHash = parentHash;
    m.parentName = parentName;
    m.approved = false;
    m.joinRequestedAt = now;
    // 자녀가 승인할 시간을 준다
    m.expiresAt = Math.max(m.expiresAt || 0, now + APPROVAL_TTL_MS);
    await this.ctx.storage.put("meta", m);
    await this.addEvent("join_request", { parentName });
    await this.ctx.storage.setAlarm(m.expiresAt);
    return { ok: true, childName: m.childName };
  }

  async addEvent(type, data) {
    const seq = ((await this.ctx.storage.get("seq")) || 0) + 1;
    const ev = { id: seq, ts: Date.now(), type, data: data || {} };
    await this.ctx.storage.put({ seq, [evKey(seq)]: ev });
    if (seq > MAX_EVENTS) await this.ctx.storage.delete(evKey(seq - MAX_EVENTS));
    this.send("child", { kind: "event", event: ev });
    return ev;
  }

  async addCommand(type, data) {
    const seq = ((await this.ctx.storage.get("cseq")) || 0) + 1;
    const cmd = { id: seq, ts: Date.now(), type, data: data || {} };
    await this.ctx.storage.put({ cseq: seq, [cmdKey(seq)]: cmd });
    if (seq > MAX_EVENTS) await this.ctx.storage.delete(cmdKey(seq - MAX_EVENTS));
    this.send("parent", { kind: "command", command: cmd });
    return cmd;
  }

  async eventsSince(sinceId) {
    const list = await this.ctx.storage.list({ prefix: "ev:", start: evKey(sinceId + 1) });
    return [...list.values()];
  }

  async commandsSince(sinceId) {
    const list = await this.ctx.storage.list({ prefix: "cmd:", start: cmdKey(sinceId + 1) });
    return [...list.values()];
  }

  send(tag, msg) {
    const text = JSON.stringify(msg);
    for (const ws of this.ctx.getWebSockets(tag)) {
      try {
        ws.send(text);
      } catch (e) {
        console.warn("ws send failed", e);
      }
    }
  }

  closeTag(tag, code, reason) {
    for (const ws of this.ctx.getWebSockets(tag)) {
      try {
        ws.close(code, reason);
      } catch (e) {
        console.warn("ws close failed", e);
      }
    }
  }

  closeAll(code, reason) {
    this.closeTag("child", code, reason);
    this.closeTag("parent", code, reason);
  }

  async wipe() {
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
    // 끊긴 뒤 늦게 도착한 요청이 옛 정보를 되살리지 못하게 표식만 남긴다 (식별 정보 없음)
    await this.ctx.storage.put("gone", true);
  }

  /**
   * 부모 폰이 마지막으로 살아 있던 시각: HTTP 생존 신호와, 부모 WebSocket의 ping 자동 응답 시각 중 늦은 것.
   * 자동 응답은 DO를 깨우지 않으므로 ping마다 비용이 들지 않는다 (getWebSocketAutoResponseTimestamp).
   */
  parentSeenAt(m) {
    let t = m.lastHeartbeat || 0;
    for (const ws of this.ctx.getWebSockets("parent")) {
      const d = this.ctx.getWebSocketAutoResponseTimestamp(ws);
      if (d && d.getTime() > t) t = d.getTime();
    }
    return t || null;
  }

  async stateFor(m, role, sinceId) {
    const base = {
      paired: !!m.approved,
      pendingJoin: !!m.parentHash && !m.approved,
      childName: m.childName,
      parentName: m.parentName,
      pairedAt: m.pairedAt,
    };
    if (role === "child") {
      return { ...base, lastHeartbeat: this.parentSeenAt(m), heartbeat: m.heartbeat, events: await this.eventsSince(sinceId) };
    }
    return { ...base, commands: m.approved ? await this.commandsSince(sinceId) : [] };
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
    const now = Date.now();

    // 실시간 연결: 자녀는 사건을, 부모는 명령을 받는다
    if (url.pathname === "/v1/ws") {
      if ((request.headers.get("upgrade") || "").toLowerCase() !== "websocket") {
        throw new HttpError(426, "upgrade_required", "WebSocket 필요");
      }
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.ctx.acceptWebSocket(server, [who.role]);
      server.send(JSON.stringify({ kind: "state", state: await this.stateFor(m, who.role, sinceId) }));
      return new Response(null, { status: 101, webSocket: client });
    }

    if (url.pathname === "/v1/state" && request.method === "GET") {
      return json({ role: who.role, ...(await this.stateFor(m, who.role, sinceId)) });
    }

    // 자녀: 연결 요청 승인/거절
    if (url.pathname === "/v1/pair/approve" && request.method === "POST") {
      if (who.role !== "child") throw new HttpError(403, "forbidden", "자녀 폰만 승인할 수 있습니다");
      if (!m.parentHash) throw new HttpError(409, "no_request", "연결 요청이 없습니다");
      if (m.approved) return json({ ok: true, approved: true });
      if (body.approve === true) {
        m.approved = true;
        m.pairedAt = now;
        m.lastHeartbeat = now;
        await this.ctx.storage.put("meta", m);
        await this.addEvent("paired", { parentName: m.parentName });
        this.send("parent", { kind: "approved", childName: m.childName });
        await this.ctx.storage.setAlarm(now + ALARM_EVERY_MS);
        return json({ ok: true, approved: true });
      }
      // 거절: 부모 연결 정보만 지우고 자녀는 새 PIN으로 다시 시작할 수 있다
      this.closeTag("parent", 4002, "rejected");
      m.rejectedHashes = [...(m.rejectedHashes || []), m.parentHash].slice(-20);
      m.parentHash = null;
      m.parentName = null;
      m.approved = false;
      await this.ctx.storage.put("meta", m);
      await this.addEvent("join_rejected", {});
      return json({ ok: true, approved: false });
    }

    // 자녀: 부모 폰에 명령 (잠금 / 차단 / 무시)
    if (url.pathname === "/v1/commands" && request.method === "POST") {
      if (who.role !== "child") throw new HttpError(403, "forbidden", "자녀 폰만 보낼 수 있습니다");
      if (!m.approved) throw new HttpError(403, "not_approved", "연결이 끝나지 않았습니다");
      const c = sanitizeCommand(body);
      // 같은 결정 요청에는 첫 명령만 (재시도·중복 전송으로 잠금이 계속 연장되지 않게)
      if (c.data.requestId) {
        const prev = await this.ctx.storage.get(`cmdreq:${c.data.requestId}`);
        if (prev) return json({ ok: true, id: prev, duplicate: true });
      }
      const cmd = await this.addCommand(c.type, c.data);
      if (c.data.requestId) await this.ctx.storage.put(`cmdreq:${c.data.requestId}`, cmd.id);
      await this.addEvent("command_sent", { commandId: cmd.id, type: c.type, ...c.data });
      return json({ ok: true, id: cmd.id });
    }

    if (url.pathname === "/v1/events" && request.method === "POST") {
      if (who.role !== "parent") throw new HttpError(403, "forbidden", "부모 폰만 보낼 수 있습니다");
      if (!m.approved) throw new HttpError(403, "not_approved", "자녀 폰의 승인을 기다리는 중입니다");
      const type = body.type;
      const handlers = {
        heartbeat: null,
        alert: sanitizeAlert,
        snapshot: sanitizeSnapshot,
        decision: sanitizeDecision,
        command_result: sanitizeCommandResult,
        alerts: sanitizeAlerts,
      };
      if (!Object.prototype.hasOwnProperty.call(handlers, type)) throw new HttpError(400, "bad_type", "알 수 없는 종류");
      const data = handlers[type] ? handlers[type](body.data) : null;
      // 같은 결정 요청은 한 번만 (부모 폰이 응답을 못 받고 다시 보낸 경우)
      if (type === "decision" && data.requestId) {
        const prev = await this.ctx.storage.get(`decreq:${data.requestId}`);
        if (prev) return json({ ok: true, id: prev, duplicate: true });
      }
      const wasSilent = m.silentNotified;
      m.lastHeartbeat = now;
      m.silentNotified = false;
      if (type === "heartbeat") m.heartbeat = sanitizeHeartbeat(body.data);
      await this.ctx.storage.put("meta", m);
      if (wasSilent) await this.addEvent("parent_back", {});
      if (type === "heartbeat") {
        this.send("child", { kind: "heartbeat", lastHeartbeat: now, heartbeat: m.heartbeat });
        return json({ ok: true });
      }
      if (type === "alerts") {
        // 여러 알림을 한 번에 받아 사건을 하나씩 만든다 (요청·TLS 연결 수를 줄이기 위함)
        const ids = [];
        for (const a of data) ids.push((await this.addEvent("alert", a)).id);
        return json({ ok: true, ids });
      }
      const ev = await this.addEvent(type, data);
      if (type === "decision" && data.requestId) await this.ctx.storage.put(`decreq:${data.requestId}`, ev.id);
      return json({ ok: true, id: ev.id });
    }

    if (url.pathname === "/v1/family" && request.method === "DELETE") {
      // 승인 전인 부모가 끊으면 자기 요청만 취소한다 (잘못 넣은 PIN으로 남의 가족을 지우지 않게)
      if (who.role === "parent" && !m.approved) {
        this.closeTag("parent", 4000, "unlinked");
        m.parentHash = null;
        m.parentName = null;
        await this.ctx.storage.put("meta", m);
        await this.addEvent("join_rejected", { by: "parent" });
        return json({ ok: true });
      }
      // 어느 쪽이든 연결 끊기. 상대에게 먼저 알린 뒤 모두 지운다.
      const msg = { kind: "event", event: { id: 0, ts: now, type: "unlinked", data: { by: who.role } } };
      this.send("child", msg);
      this.send("parent", msg);
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
    if (!m.approved) {
      const exp = m.expiresAt || m.createdAt + UNPAIRED_FAMILY_TTL_MS;
      if (now >= exp) {
        this.closeAll(4001, "expired");
        await this.wipe();
        return;
      }
      await this.ctx.storage.setAlarm(exp);
      return;
    }
    const seen = this.parentSeenAt(m);
    if (seen && seen > (m.lastHeartbeat || 0)) {
      // 실시간 연결로 살아 있음이 확인됨: 저장해 두고, 신호 없음 경고 중이었으면 복귀 알림
      m.lastHeartbeat = seen;
      const wasSilent = m.silentNotified;
      m.silentNotified = false;
      await this.ctx.storage.put("meta", m);
      if (wasSilent) await this.addEvent("parent_back", {});
    }
    if (!m.silentNotified && m.lastHeartbeat && now - m.lastHeartbeat > SILENT_AFTER_MS) {
      m.silentNotified = true;
      await this.ctx.storage.put("meta", m);
      await this.addEvent("parent_silent", { lastHeartbeat: m.lastHeartbeat });
    }
    await this.ctx.storage.setAlarm(now + ALARM_EVERY_MS);
  }

  async webSocketMessage(ws, message) {
    // 기기는 "ping" 외에 보낼 것이 없다 (ping은 자동 응답). 그 밖의 메시지는 무시.
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

function sanitizeAlerts(d) {
  const items = Array.isArray(d && d.items) ? d.items.slice(0, 50) : [];
  return items.map(sanitizeAlert);
}

function sanitizeSnapshot(d) {
  const apps = Array.isArray(d && d.apps) ? d.apps.slice(0, 50).map(sanitizeApp).filter((a) => a && a.packageName) : [];
  return { apps, scannedAt: Number.isFinite(d && d.scannedAt) ? d.scannedAt : Date.now() };
}

const COMMANDS = new Set(["lock", "block", "ignore"]);

function sanitizeCommand(b) {
  const type = b && b.type;
  if (!COMMANDS.has(type)) throw new HttpError(400, "bad_command", "알 수 없는 명령");
  const d = b.data && typeof b.data === "object" ? b.data : {};
  return {
    type,
    data: {
      requestId: cleanText(d.requestId, 64),
      packageName: cleanText(d.packageName, 200),
      label: cleanText(d.label, 80),
    },
  };
}

function sanitizeDecision(d) {
  const app = sanitizeApp(d && d.app);
  if (!app || !app.packageName) throw new HttpError(400, "bad_decision", "앱 정보가 없습니다");
  return {
    requestId: cleanText(d.requestId, 64),
    reason: d.reason === "surge" ? "surge" : "score",
    previousScore: Number.isFinite(d.previousScore) ? Math.max(0, Math.min(9999, Math.round(d.previousScore))) : 0,
    autoBlockAt: Number.isFinite(d.autoBlockAt) ? d.autoBlockAt : 0,
    app,
  };
}

function sanitizeCommandResult(d) {
  const o = d && typeof d === "object" ? d : {};
  return {
    commandId: Number.isFinite(o.commandId) ? o.commandId : 0,
    requestId: cleanText(o.requestId, 64),
    type: COMMANDS.has(o.type) || o.type === "auto_block" ? o.type : "unknown",
    ok: o.ok === true,
    message: cleanText(o.message, 200),
    packageName: cleanText(o.packageName, 200),
    label: cleanText(o.label, 80),
    lockUntil: Number.isFinite(o.lockUntil) ? o.lockUntil : 0,
  };
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
