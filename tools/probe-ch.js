// 复现「Android WebView + 桌面 UA」这个自相矛盾的环境，看到底是什么导致接口回空体/HTML。
//
// 关键：App 用 WebSettings.userAgentString 把 UA 改成了 Windows Chrome/120，
// 但 navigator.userAgentData（客户端提示）仍由内核真实环境决定 = Android/143。
// 页面 JS 会拿这套信息算签名，服务端一比对就是矛盾体。
//
// 用 CDP 的 setUserAgentOverride(userAgent + userAgentMetadata) 精确复刻四种组合，
// 每次都真加载页面并读接口响应的真实字节数。
const http = require('http');

const newTab = (url) =>
  new Promise((res, rej) => {
    const r = http.request(
      { host: '127.0.0.1', port: 9222, path: '/json/new?' + encodeURIComponent(url), method: 'PUT' },
      (resp) => {
        let b = '';
        resp.on('data', (d) => (b += d));
        resp.on('end', () => res(JSON.parse(b)));
      }
    );
    r.on('error', rej);
    r.end();
  });

const closeTab = (id) =>
  new Promise((res) => {
    const r = http.request({ host: '127.0.0.1', port: 9222, path: '/json/close/' + id, method: 'GET' }, () => res());
    r.on('error', () => res());
    r.end();
  });

const DESKTOP_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const MOBILE_UA =
  'Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/143.0.7499.192 Mobile Safari/537.36';

const WIN_META = {
  brands: [
    { brand: 'Not_A Brand', version: '8' },
    { brand: 'Chromium', version: '120' },
    { brand: 'Google Chrome', version: '120' },
  ],
  fullVersionList: [
    { brand: 'Not_A Brand', version: '8.0.0.0' },
    { brand: 'Chromium', version: '120.0.6099.230' },
    { brand: 'Google Chrome', version: '120.0.6099.230' },
  ],
  fullVersion: '120.0.6099.230',
  platform: 'Windows',
  platformVersion: '15.0.0',
  architecture: 'x86',
  model: '',
  mobile: false,
};

const ANDROID_META = {
  brands: [
    { brand: 'Not)A;Brand', version: '24' },
    { brand: 'Chromium', version: '143' },
    { brand: 'Google Chrome', version: '143' },
  ],
  fullVersionList: [
    { brand: 'Not)A;Brand', version: '24.0.0.0' },
    { brand: 'Chromium', version: '143.0.7499.192' },
    { brand: 'Google Chrome', version: '143.0.7499.192' },
  ],
  fullVersion: '143.0.7499.192',
  platform: 'Android',
  platformVersion: '13.0.0',
  architecture: '',
  model: 'Pixel 7',
  mobile: true,
};

async function run(label, videoUrl, override) {
  const tab = await newTab('about:blank');
  const ws = new WebSocket(tab.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  const send = (m, p = {}) =>
    new Promise((res) => {
      const mid = ++id;
      pending.set(mid, res);
      ws.send(JSON.stringify({ id: mid, method: m, params: p }));
    });

  const detailIds = [];
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m.result);
      pending.delete(m.id);
      return;
    }
    if (m.method === 'Network.responseReceived') {
      const u = m.params.response.url || '';
      if (u.indexOf('/aweme/detail') >= 0) {
        detailIds.push({ id: m.params.requestId, url: u, status: m.params.response.status, ct: (m.params.response.headers['content-type'] || m.params.response.headers['Content-Type'] || '') });
      }
    }
  };
  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Network.enable');
  if (override) {
    await send('Emulation.setUserAgentOverride', {
      userAgent: override.userAgent,
      acceptLanguage: 'zh-CN,zh;q=0.9',
      platform: override.platform,
      userAgentMetadata: override.metadata,
    });
    await send('Emulation.setDeviceMetricsOverride', {
      width: 360, height: 640, deviceScaleFactor: 2, mobile: override.metadata.mobile,
    });
  }
  await send('Page.navigate', { url: videoUrl });
  await new Promise((r) => setTimeout(r, 14000));

  const title = (await send('Runtime.evaluate', { expression: 'document.title||""', returnByValue: true })).result.value;
  const out = [];
  for (const d of detailIds.slice(0, 4)) {
    let len = -1;
    let has = false;
    const body = await send('Network.getResponseBody', { requestId: d.id });
    if (body && body.body) {
      const s = body.base64Encoded ? Buffer.from(body.body, 'base64').toString('utf8') : body.body;
      len = s.length;
      has = s.indexOf('aweme_detail') >= 0;
    }
    out.push(`HTTP ${d.status} ${len}B ct=${d.ct} hasDetail=${has}`);
  }
  console.log(`\n[${label}]  title="${String(title).slice(0, 40)}"`);
  if (!out.length) console.log('   没有发出 detail 请求');
  out.forEach((s) => console.log('   ' + s));
  ws.close();
  await closeTab(tab.id);
}

(async () => {
  const video = process.argv[2] || 'https://www.douyin.com/video/7699062672146337075';
  await run('A 真实 PC Chrome（基线）', video, null);
  await run('B 桌面UA + 桌面CH（自洽）', video, { userAgent: DESKTOP_UA, platform: 'Win32', metadata: WIN_META });
  await run('C 桌面UA + 安卓CH（App 现状，矛盾）', video, { userAgent: DESKTOP_UA, platform: 'Linux armv8l', metadata: ANDROID_META });
  await run('D 安卓WebView UA + 安卓CH（自洽）', video, { userAgent: MOBILE_UA, platform: 'Linux armv8l', metadata: ANDROID_META });
  process.exit(0);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
