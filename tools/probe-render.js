// 在真浏览器里取出 RENDER_DATA，检查是否含 bit_rate / play_addr 等字段
const http = require('http');

function req(opts, body) {
  return new Promise((res, rej) => {
    const r = http.request(opts, (resp) => {
      let b = '';
      resp.on('data', (d) => (b += d));
      resp.on('end', () => res(b));
    });
    r.on('error', rej);
    if (body) r.write(body);
    r.end();
  });
}

const newTab = (url) =>
  req({ host: '127.0.0.1', port: 9222, path: '/json/new?' + encodeURIComponent(url), method: 'PUT' }).then(JSON.parse);

const target = process.argv[2];
const ua = process.argv[3] || '';
const WAIT_MS = parseInt(process.argv[4] || '14000', 10);

(async () => {
  const tab = await newTab('about:blank');
  const ws = new WebSocket(tab.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  const jsonRes = [];

  const send = (method, params = {}) =>
    new Promise((res) => {
      const mid = ++id;
      pending.set(mid, res);
      ws.send(JSON.stringify({ id: mid, method, params }));
    });

  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m.result);
      pending.delete(m.id);
      return;
    }
    if (m.method === 'Network.responseReceived') {
      const u = m.params.response.url;
      if (/aweme\/v1|aweme\/v2|iteminfo|web\/api/i.test(u)) {
        jsonRes.push({ url: u, status: m.params.response.status, reqId: m.params.requestId, type: m.params.response.mimeType });
      }
    }
  };

  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Network.enable');
  await send('Runtime.enable');
  if (ua) await send('Network.setUserAgentOverride', { userAgent: ua });
  await send('Page.navigate', { url: target });
  await new Promise((r) => setTimeout(r, WAIT_MS));

  const evalJs = async (expr) => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r && r.exceptionDetails) return { __err: r.exceptionDetails.text };
    return r && r.result ? r.result.value : null;
  };

  const out = await evalJs(`(() => {
    const el = document.getElementById('RENDER_DATA');
    const raw = el ? el.textContent : null;
    let obj = null, err = null;
    if (raw) { try { obj = JSON.parse(decodeURIComponent(raw)); } catch (e) { err = String(e); } }
    const vids = [...document.querySelectorAll('video')].map(v => v.src || v.currentSrc).filter(Boolean);
    if (!obj) return { hasEl: !!el, rawLen: raw ? raw.length : 0, err, vids, url: location.href, title: document.title };
    // 找到作品节点：递归找含 awemeId/aweme_id 的对象
    let item = null;
    const walk = (n, depth) => {
      if (!n || depth > 8 || item) return;
      if (Array.isArray(n)) return n.forEach(x => walk(x, depth + 1));
      if (typeof n === 'object') {
        if (n.awemeId || n.aweme_id || n.video) { if (n.video && (n.video.playAddr || n.video.play_addr || n.video.bitRateList || n.video.bit_rate)) { item = n; return; } }
        Object.values(n).forEach(v => walk(v, depth + 1));
      }
    };
    walk(obj, 0);
    const pick = (o) => {
      if (!o) return null;
      const v = o.video || {};
      const norm = (x) => Array.isArray(x) ? x.map(norm) : (x && typeof x === 'object' ? Object.fromEntries(Object.entries(x).map(([k, y]) => [k, norm(y)])) : (typeof x === 'string' && x.length > 300 ? x.slice(0, 300) + '...' : x));
      return norm({
        desc: o.desc, author: o.author, awemeId: o.awemeId || o.aweme_id,
        images: o.images ? o.images.length : 0,
        videoKeys: Object.keys(v),
        bitRateList: v.bitRateList || v.bit_rate,
        playAddr: v.playAddr || v.play_addr,
        duration: v.duration
      });
    };
    return {
      hasEl: true, rawLen: raw.length, url: location.href, title: document.title, vids,
      topKeys: Object.keys(obj).slice(0, 20),
      itemFound: !!item,
      item: pick(item)
    };
  })()`);

  const bodies = [];
  for (const r of jsonRes.slice(0, 6)) {
    const b = await send('Network.getResponseBody', { requestId: r.reqId });
    if (b && b.body) bodies.push({ url: r.url, status: r.status, len: b.body.length, head: b.body.slice(0, 260) });
  }

  console.log(JSON.stringify({ out, apiCalls: jsonRes.map(r => r.url + ' [' + r.status + ']').slice(0, 10), bodies }, null, 1));
  ws.close();
  process.exit(0);
})().catch((e) => { console.error('ERR', e.message); process.exit(1); });
