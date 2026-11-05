// 抓 aweme/detail 的真实响应，检查 video 下有哪些质量档位字段
const http = require('http');
const fs = require('fs');

const req = (opts) =>
  new Promise((res, rej) => {
    const r = http.request(opts, (resp) => {
      let b = '';
      resp.on('data', (d) => (b += d));
      resp.on('end', () => res(b));
    });
    r.on('error', rej);
    r.end();
  });

const newTab = (url) =>
  req({ host: '127.0.0.1', port: 9222, path: '/json/new?' + encodeURIComponent(url), method: 'PUT' }).then(JSON.parse);

const target = process.argv[2];
const ua = process.argv[3] || '';
const WAIT_MS = parseInt(process.argv[4] || '15000', 10);
const outFile = process.argv[5] || '/tmp/detail.json';

(async () => {
  const tab = await newTab('about:blank');
  const ws = new WebSocket(tab.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  let detailReqId = null;
  let detailUrl = null;

  const send = (m, p = {}) =>
    new Promise((res) => {
      const mid = ++id;
      pending.set(mid, res);
      ws.send(JSON.stringify({ id: mid, method: m, params: p }));
    });

  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); return; }
    if (m.method === 'Network.responseReceived') {
      const u = m.params.response.url;
      if (u.includes('/aweme/detail/')) { detailReqId = m.params.requestId; detailUrl = u; }
    }
  };

  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Network.enable');
  await send('Runtime.enable');
  if (ua) await send('Network.setUserAgentOverride', { userAgent: ua, platform: ua.includes('iPhone') ? 'iPhone' : 'Win32' });
  await send('Page.navigate', { url: target });
  await new Promise((r) => setTimeout(r, WAIT_MS));

  if (!detailReqId) {
    const ev = await send('Runtime.evaluate', {
      expression: `JSON.stringify({url: location.href, title: document.title, vids: [...document.querySelectorAll('video')].map(v=>v.src).filter(Boolean), hasRender: !!document.getElementById('RENDER_DATA')})`,
      returnByValue: true,
    });
    console.log('NO_DETAIL', ev.result.value);
    process.exit(2);
  }

  const body = await send('Network.getResponseBody', { requestId: detailReqId });
  fs.writeFileSync(outFile, body.body);

  let d = null;
  try { d = JSON.parse(body.body); } catch (e) { console.log('NOT_JSON', body.body.slice(0, 200)); process.exit(3); }

  const item = d.aweme_detail || (d.aweme_list && d.aweme_list[0]) || null;
  const v = item ? item.video || {} : {};
  const pickAddr = (a) => (a && (a.url_list || a.urlList)) ? { urls: (a.url_list || a.urlList).length, first: String((a.url_list || a.urlList)[0]).slice(0, 90), dataSize: a.data_size || a.dataSize, w: a.width, h: a.height } : a;

  const out = {
    url: detailUrl ? detailUrl.slice(0, 80) : null,
    bytes: body.body.length,
    statusCode: d.status_code,
    hasAwemeDetail: !!d.aweme_detail,
    itemKeys: item ? Object.keys(item).slice(0, 40) : null,
    videoKeys: Object.keys(v),
    desc: item ? String(item.desc || '').slice(0, 40) : null,
    author: item && item.author ? item.author.nickname : null,
    images: item && item.images ? item.images.length : 0,
    bitRateCount: (v.bit_rate || v.bitRateList || []).length,
    bitRates: (v.bit_rate || v.bitRateList || []).map((b) => ({
      name: b.gear_name || b.gearName, rate: b.bit_rate || b.bitRate, quality: b.quality_type || b.qualityType,
      addr: pickAddr(b.play_addr || b.playAddr),
    })),
    playAddr: pickAddr(v.play_addr || v.playAddr),
    downloadAddr: pickAddr(v.download_addr || v.downloadAddr),
    playApi: v.playApi ? String(v.playApi).slice(0, 90) : null,
    duration: v.duration,
  };

  console.log(JSON.stringify(out, null, 1));
  ws.close();
  process.exit(0);
})().catch((e) => { console.error('ERR', e.message); process.exit(1); });
