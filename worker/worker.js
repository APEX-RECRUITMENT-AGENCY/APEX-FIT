// Apex Fit backend (Cloudflare Worker)
// Routes:
//   POST /          -> AI assistant (free Workers AI), needs login
//   POST /checkout  -> creates a Paymob payment for the logged-in user and returns the checkout URL
//   POST /status    -> returns the user's subscription end date
//   POST /webhook   -> Paymob tells us a payment succeeded (verified with HMAC), we activate PRO
//
// Bindings (Settings > Bindings):
//   AI    -> Workers AI
//   SUBS  -> KV namespace (create one, name it apex-subs)
// Variables / secrets (Settings > Variables and Secrets):
//   FIREBASE_API_KEY      Text    your Firebase apiKey
//   ALLOWED_ORIGIN        Text    https://apex-recruitment-agency.github.io
//   SITE_URL              Text    https://apex-recruitment-agency.github.io/APEX-FIT/
//   PAYMOB_SECRET_KEY     Secret  Paymob Settings > Account info (egy_sk_...)
//   PAYMOB_PUBLIC_KEY     Text    Paymob Settings > Account info (egy_pk_...)
//   PAYMOB_HMAC           Secret  Paymob Settings > Account info > HMAC
//   PAYMOB_INTEGRATION_ID Text    the Card integration id (e.g. 6026229)

// Cloudflare retired the Llama 3.1 models on 2026-05-30. We try these in order and use the first one that answers.
// Current list: https://developers.cloudflare.com/workers-ai/models/
const MODELS = [
  '@cf/zai-org/glm-4.7-flash',
  '@cf/google/gemma-4-26b-a4b-it',
  '@cf/meta/llama-3.2-3b-instruct',
];

function pickText(out) {
  if (!out) return '';
  if (typeof out.response === 'string') return out.response;
  const c = out.choices && out.choices[0];
  if (c && c.message && typeof c.message.content === 'string') return c.message.content;
  if (out.result && typeof out.result.response === 'string') return out.result.response;
  return '';
}

const SYSTEM = 'You are a friendly fitness and nutrition assistant inside an app. Reply in simple Egyptian Arabic, short and practical. Never give medical diagnoses; for symptoms, chronic illness or pregnancy tell the user to see a doctor.';

// Prices live HERE (the server decides, never the browser). cents = piasters.
const PLANS = {
  m1: { name: 'Apex Fit PRO 1 month', cents: 9900, days: 30 },
  m3: { name: 'Apex Fit PRO 3 months', cents: 24900, days: 90 },
  y1: { name: 'Apex Fit PRO 1 year', cents: 89900, days: 365 },
};

const enc = new TextEncoder();
async function hmacHex(secret, msg) {
  const k = await crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-512' }, false, ['sign']);
  const s = await crypto.subtle.sign('HMAC', k, enc.encode(msg));
  return [...new Uint8Array(s)].map(b => b.toString(16).padStart(2, '0')).join('');
}
function same(a, b) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

async function authUser(req, env) {
  const token = (req.headers.get('Authorization') || '').replace('Bearer ', '');
  if (!token) return null;
  const r = await fetch('https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=' + env.FIREBASE_API_KEY, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ idToken: token }),
  });
  if (!r.ok) return null;
  const u = ((await r.json()).users || [])[0];
  return u ? { uid: u.localId, email: u.email || '', name: u.displayName || '' } : null;
}

async function grant(env, uid, plan) {
  const key = 'sub:' + uid;
  const cur = JSON.parse((await env.SUBS.get(key)) || 'null');
  const base = Math.max(Date.now(), (cur && cur.until) || 0); // renewing early adds time on top
  const until = base + PLANS[plan].days * 864e5;
  await env.SUBS.put(key, JSON.stringify({ until, plan }));
  return until;
}

async function webhook(req, env) {
  const ok = () => new Response('ok', { status: 200 });
  const hm = new URL(req.url).searchParams.get('hmac') || '';
  let body;
  try { body = await req.json(); } catch { return new Response('bad', { status: 400 }); }
  const o = body && body.obj;
  if (!o || o.amount_cents === undefined) return ok(); // not a transaction callback

  // Verify the signature (20 fields, exact order, HMAC-SHA512)
  const s = o.source_data || {};
  const signed = [
    o.amount_cents, o.created_at, o.currency, o.error_occured, o.has_parent_transaction, o.id,
    o.integration_id, o.is_3d_secure, o.is_auth, o.is_capture, o.is_refunded, o.is_standalone_payment,
    o.is_voided, o.order && o.order.id, o.owner, o.pending, s.pan, s.sub_type, s.type, o.success,
  ].join('');
  const expected = await hmacHex(env.PAYMOB_HMAC, signed);
  if (!same(expected, hm.toLowerCase())) { console.log('bad hmac'); return new Response('forbidden', { status: 403 }); }

  if (!(o.success === true && o.pending === false && !o.is_voided && !o.is_refunded && !o.error_occured)) return ok();

  // Find who paid: we saved the reference when we created the payment
  const pk = o.payment_key_claims && o.payment_key_claims.extra;
  const cands = [o.order && o.order.merchant_order_id, pk && (pk.ref || pk.special_reference)];
  let rec = null;
  for (const c of cands) {
    if (c) { const v = await env.SUBS.get('ref:' + c); if (v) { rec = JSON.parse(v); break; } }
  }
  if (!rec && o.order && o.order.id) {
    const v = await env.SUBS.get('ord:' + o.order.id);
    if (v) rec = JSON.parse(v);
  }
  if (!rec) { console.log('paid but no reference found, tx', o.id, JSON.stringify(cands)); return ok(); }
  if (!PLANS[rec.plan] || o.amount_cents < PLANS[rec.plan].cents) { console.log('amount mismatch', o.id); return ok(); }

  // Do it once per transaction
  if (await env.SUBS.get('tx:' + o.id)) return ok();
  await env.SUBS.put('tx:' + o.id, '1', { expirationTtl: 60 * 60 * 24 * 30 });
  await grant(env, rec.uid, rec.plan);
  console.log('activated', rec.uid, rec.plan);
  return ok();
}

async function checkout(req, env, user, reply) {
  let b; try { b = await req.json(); } catch { return reply('Bad request', 400); }
  const p = PLANS[b.plan];
  if (!p) return reply('Bad plan', 400);
  const ref = user.uid + '-' + b.plan + '-' + Date.now();
  const origin = new URL(req.url).origin;
  const payload = {
    amount: p.cents, currency: 'EGP',
    payment_methods: [Number(env.PAYMOB_INTEGRATION_ID)],
    items: [{ name: p.name, amount: p.cents, description: 'Apex Fit PRO', quantity: 1 }],
    billing_data: {
      first_name: (user.name || user.email.split('@')[0] || 'Apex').slice(0, 30), last_name: 'User',
      email: user.email || 'user@example.com', phone_number: '+201000000000',
      country: 'EG', city: 'NA', street: 'NA', building: 'NA', floor: 'NA', apartment: 'NA', state: 'NA',
    },
    special_reference: ref, extras: { ref },
    notification_url: origin + '/webhook',
    redirection_url: env.SITE_URL + (env.SITE_URL.includes('?') ? '&' : '?') + 'paid=1',
  };
  const r = await fetch('https://accept.paymob.com/v1/intention/', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Token ' + env.PAYMOB_SECRET_KEY },
    body: JSON.stringify(payload),
  });
  if (!r.ok) { console.log('paymob error', r.status, await r.text()); return reply('Payment error', 502); }
  const d = await r.json();
  const rec = JSON.stringify({ uid: user.uid, plan: b.plan });
  await env.SUBS.put('ref:' + ref, rec, { expirationTtl: 60 * 60 * 24 * 7 });
  const oid = d.intention_order_id || d.order_id;
  if (oid) await env.SUBS.put('ord:' + oid, rec, { expirationTtl: 60 * 60 * 24 * 7 });
  return reply({ url: 'https://accept.paymob.com/unifiedcheckout/?publicKey=' + env.PAYMOB_PUBLIC_KEY + '&clientSecret=' + d.client_secret });
}

export default {
  async fetch(req, env) {
    const cors = {
      'Access-Control-Allow-Origin': env.ALLOWED_ORIGIN,
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
    };
    const reply = (body, status = 200) =>
      new Response(typeof body === 'string' ? body : JSON.stringify(body), { status, headers: { ...cors, 'Content-Type': 'application/json' } });
    const path = new URL(req.url).pathname.replace(/\/+$/, '') || '/';

    if (req.method === 'OPTIONS') return new Response(null, { headers: cors });
    if (path === '/webhook' && req.method === 'POST') return webhook(req, env);
    if (req.method !== 'POST') return reply('Method not allowed', 405);

    const user = await authUser(req, env);
    if (!user) return reply('Unauthorized', 401);

    if (path === '/status') {
      const cur = JSON.parse((await env.SUBS.get('sub:' + user.uid)) || 'null');
      return reply({ until: cur && cur.until > Date.now() ? cur.until : 0, plan: cur ? cur.plan : null });
    }
    if (path === '/checkout') return checkout(req, env, user, reply);

    // AI assistant
    let body;
    try { body = await req.json(); } catch { return reply('Bad request', 400); }
    const msgs = (body.messages || []).slice(-6).map(m => ({
      role: m.role === 'assistant' ? 'assistant' : 'user',
      content: String(m.content || '').slice(0, 4000),
    }));
    if (!msgs.length || msgs[msgs.length - 1].role !== 'user') return reply('Bad request', 400);
    let lastErr = '';
    for (const model of MODELS) {
      try {
        const out = await env.AI.run(model, { messages: [{ role: 'system', content: SYSTEM }, ...msgs], max_tokens: 700 });
        const text = pickText(out).trim();
        if (text) return reply({ text });
        lastErr = model + ': empty answer';
      } catch (e) {
        lastErr = model + ': ' + String((e && e.message) || e);
        console.log('AI failed', lastErr);
      }
    }
    return reply({ error: lastErr.slice(0, 160) }, /limit|quota|capacity/i.test(lastErr) ? 429 : 502);
  },
};
