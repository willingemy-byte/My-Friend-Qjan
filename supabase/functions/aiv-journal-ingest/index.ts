import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });

const hex = (bytes: ArrayBuffer) =>
  [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");

const sha256 = async (text: string) =>
  hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));

const isHex64 = (v: unknown) =>
  typeof v === "string" && /^[0-9a-f]{64}$/i.test(v);

const isUuid = (v: unknown) =>
  typeof v === "string" &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v);

const asInt = (v: unknown) => {
  if (typeof v === "number" && Number.isFinite(v)) return Math.trunc(v);
  if (typeof v === "string" && /^-?\d+$/.test(v)) return Number(v);
  return null;
};

const asText = (v: unknown, max: number) =>
  typeof v === "string" ? v.slice(0, max) : "";

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const suppliedApiKey = req.headers.get("apikey") ?? "";
  const publishableRaw = Deno.env.get("SUPABASE_PUBLISHABLE_KEYS") ?? "{}";
  const legacyAnon = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
  let publishableAllowed = false;
  try {
    const parsed = JSON.parse(publishableRaw);
    publishableAllowed = Object.values(parsed).includes(suppliedApiKey);
  } catch (_) {}
  if (!publishableAllowed && suppliedApiKey !== legacyAnon) {
    return json({ error: "invalid_client_key" }, 401);
  }

  const installId = req.headers.get("x-aiv-install-id") ?? "";
  const installSecret = req.headers.get("x-aiv-install-secret") ?? "";
  if (!isUuid(installId) || installSecret.length < 32 || installSecret.length > 256) {
    return json({ error: "invalid_install_identity" }, 401);
  }

  let payload: any;
  try {
    const raw = await req.text();
    if (raw.length > 2_000_000) return json({ error: "payload_too_large" }, 413);
    payload = JSON.parse(raw);
  } catch (_) {
    return json({ error: "invalid_json" }, 400);
  }
  if (payload?.schema !== "aiv-journal/1") return json({ error: "invalid_schema" }, 400);

  const url = Deno.env.get("SUPABASE_URL");
  let serviceKey = "";
  try {
    const secretKeys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}");
    serviceKey = secretKeys.default ?? "";
  } catch (_) {}
  if (!serviceKey) serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  if (!url || !serviceKey) return json({ error: "server_not_configured" }, 500);
  const supabase = createClient(url, serviceKey, { auth: { persistSession: false } });

  const secretHash = await sha256(installSecret);
  let { data: device, error: deviceError } = await supabase
    .from("aiv_devices")
    .select("install_id, secret_hash")
    .eq("install_id", installId)
    .maybeSingle();

  if (deviceError) return json({ error: "device_lookup_failed" }, 500);
  if (!device) {
    const inserted = await supabase
      .from("aiv_devices")
      .insert({ install_id: installId, secret_hash: secretHash })
      .select("install_id, secret_hash")
      .single();
    if (inserted.error) {
      const retry = await supabase
        .from("aiv_devices")
        .select("install_id, secret_hash")
        .eq("install_id", installId)
        .maybeSingle();
      if (retry.error || !retry.data) return json({ error: "device_register_failed" }, 500);
      device = retry.data;
    } else {
      device = inserted.data;
    }
  }
  if (device.secret_hash !== secretHash) return json({ error: "install_secret_mismatch" }, 403);
  await supabase.from("aiv_devices").update({ last_seen_at: new Date().toISOString() }).eq("install_id", installId);

  const action = payload?.action;
  if (action === "status") {
    const segments = await supabase.from("aiv_journal_segments").select("segment_no", { count: "exact", head: true }).eq("install_id", installId);
    const verified = await supabase.from("aiv_journal_segments").select("segment_no", { count: "exact", head: true }).eq("install_id", installId).eq("state", "VERIFIED");
    const events = await supabase.from("aiv_journal_events").select("event_id", { count: "exact", head: true }).eq("install_id", installId);
    if (segments.error || verified.error || events.error) return json({ error: "status_query_failed" }, 500);
    return json({ ok: true, state: "READY", segments: segments.count ?? 0, verified_segments: verified.count ?? 0, events: events.count ?? 0, server_time: new Date().toISOString() });
  }
  const segmentNo = asInt(payload?.segment_no ?? payload?.segment?.segment_no);
  if (segmentNo === null || segmentNo <= 0) return json({ error: "invalid_segment" }, 400);

  if (action === "begin") {
    const s = payload?.segment ?? {};
    const first = asInt(s.first_event_id);
    const last = asInt(s.last_event_id);
    const count = asInt(s.expected_count);
    const manifest = asText(s.client_segment_sha256, 64).toLowerCase();
    if (first === null || last === null || count === null || first <= 0 || last < first ||
        count < 1 || count > 50000 || !isHex64(manifest)) {
      return json({ error: "invalid_segment_manifest" }, 400);
    }

    const row = {
      install_id: installId,
      segment_no: segmentNo,
      first_event_id: first,
      last_event_id: last,
      expected_count: count,
      client_segment_sha256: manifest,
      first_observed_ms: asInt(s.first_observed_ms),
      last_observed_ms: asInt(s.last_observed_ms),
      state: "UPLOADING",
      error: null,
      updated_at: new Date().toISOString(),
    };

    const existing = await supabase
      .from("aiv_journal_segments")
      .select("first_event_id,last_event_id,expected_count,client_segment_sha256,state,received_count,server_segment_sha256")
      .eq("install_id", installId)
      .eq("segment_no", segmentNo)
      .maybeSingle();
    if (existing.error) return json({ error: "segment_lookup_failed" }, 500);
    if (existing.data) {
      const same = existing.data.first_event_id === first &&
        existing.data.last_event_id === last &&
        existing.data.expected_count === count &&
        String(existing.data.client_segment_sha256).toLowerCase() === manifest;
      if (!same) return json({ error: "segment_manifest_conflict" }, 409);
      return json({ ok: true, state: existing.data.state, received_count: existing.data.received_count, server_segment_sha256: existing.data.server_segment_sha256, duplicate: true });
    }

    const inserted = await supabase.from("aiv_journal_segments").insert(row).select("state,received_count").single();
    if (inserted.error) return json({ error: "segment_begin_failed", detail: inserted.error.code }, 500);
    return json({ ok: true, state: inserted.data.state, received_count: inserted.data.received_count });
  }

  if (action === "batch") {
    const events = Array.isArray(payload?.events) ? payload.events : [];
    if (events.length < 1 || events.length > 500) return json({ error: "invalid_batch_size" }, 400);

    const seg = await supabase
      .from("aiv_journal_segments")
      .select("first_event_id,last_event_id,state")
      .eq("install_id", installId)
      .eq("segment_no", segmentNo)
      .maybeSingle();
    if (seg.error || !seg.data) return json({ error: "segment_not_started" }, 409);
    if (seg.data.state === "VERIFIED") return json({ ok: true, state: "VERIFIED", duplicate: true });

    const rows: any[] = [];
    for (const e of events) {
      const eventId = asInt(e?.event_id);
      const ts = asInt(e?.timestamp_ms);
      const eventJson = typeof e?.event_json === "string" ? e.event_json : "";
      const suppliedHash = asText(e?.event_sha256, 64).toLowerCase();
      if (eventId === null || ts === null || eventId < seg.data.first_event_id || eventId > seg.data.last_event_id ||
          !eventJson || !isHex64(suppliedHash)) {
        return json({ error: "invalid_event" }, 400);
      }
      const calculated = await sha256(eventJson);
      if (calculated !== suppliedHash) return json({ error: "event_hash_mismatch", event_id: eventId }, 409);
      let parsed: any;
      try { parsed = JSON.parse(eventJson); } catch (_) { return json({ error: "event_json_invalid", event_id: eventId }, 400); }

      rows.push({
        install_id: installId,
        segment_no: segmentNo,
        event_id: eventId,
        event_sha256: suppliedHash,
        timestamp_ms: ts,
        app: asText(e?.app ?? parsed?.app, 255),
        action: asText(e?.action ?? parsed?.action, 255),
        destination: asText(e?.destination ?? parsed?.destination, 512),
        transport: asText(e?.transport ?? parsed?.transport, 64),
        category: asText(e?.category ?? parsed?.category, 64),
        event_json: eventJson,
        payload: parsed,
      });
    }

    const inserted = await supabase.from("aiv_journal_events").insert(rows);
    if (inserted.error && inserted.error.code !== "23505") {
      return json({ error: "batch_insert_failed", detail: inserted.error.code }, 500);
    }

    if (inserted.error?.code === "23505") {
      const ids = rows.map((r) => r.event_id);
      const existing = await supabase
        .from("aiv_journal_events")
        .select("event_id,event_sha256,segment_no")
        .eq("install_id", installId)
        .in("event_id", ids);
      if (existing.error) return json({ error: "duplicate_check_failed" }, 500);
      const byId = new Map((existing.data ?? []).map((r: any) => [Number(r.event_id), r]));
      const missing: any[] = [];
      for (const row of rows) {
        const old: any = byId.get(Number(row.event_id));
        if (!old) missing.push(row);
        else if (Number(old.segment_no) !== segmentNo || String(old.event_sha256).toLowerCase() !== row.event_sha256) {
          return json({ error: "event_conflict", event_id: row.event_id }, 409);
        }
      }
      if (missing.length) {
        const retry = await supabase.from("aiv_journal_events").insert(missing);
        if (retry.error) return json({ error: "batch_retry_failed", detail: retry.error.code }, 500);
      }
    }

    const count = await supabase
      .from("aiv_journal_events")
      .select("event_id", { count: "exact", head: true })
      .eq("install_id", installId)
      .eq("segment_no", segmentNo);
    if (count.error) return json({ error: "batch_count_failed" }, 500);
    await supabase.from("aiv_journal_segments")
      .update({ received_count: count.count ?? 0, updated_at: new Date().toISOString() })
      .eq("install_id", installId)
      .eq("segment_no", segmentNo);

    return json({ ok: true, received_count: count.count ?? 0 });
  }

  if (action === "finalize") {
    const verified = await supabase.rpc("aiv_finalize_journal_segment", {
      p_install_id: installId,
      p_segment_no: segmentNo,
    });
    if (verified.error) return json({ error: "finalize_failed", detail: verified.error.message }, 500);
    const row = Array.isArray(verified.data) ? verified.data[0] : verified.data;
    if (!row) return json({ error: "finalize_empty" }, 500);
    return json({
      ok: row.state === "VERIFIED",
      state: row.state,
      received_count: row.received_count,
      server_segment_sha256: row.server_segment_sha256,
      first_event_id: row.first_event_id,
      last_event_id: row.last_event_id,
    }, row.state === "VERIFIED" ? 200 : 409);
  }

  return json({ error: "invalid_action" }, 400);
});
