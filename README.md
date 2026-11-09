# 去水印 · QuShuiYin

纯本地的抖音视频/图文下载工具。没有服务器、不需要账号、不申请存储权限。

**核心思路**：无水印不是图像处理，而是**请抖音给你那条已经去掉 logo 的视频流**。
同一个作品在 CDN 上同时挂着带水印和不带水印的版本：分享面板「保存本地」拿的是带水印那条
（`playwm`，带 `logo_name` 参数）；播放器放的、以及 `aweme/v1/play/` 返回的才是不带水印的。
本工具拿的是后者，按字节原样写入相册。不做解码、不做转码，
因此画质等于平台能给的上限（实测这条作品是 1080×1920 / 10.1 MB，抽帧肉眼确认无水印）。

## 直接装到手机

不用编译，点开下载即可（需要 Android 8.0 及以上）：

**https://github.com/a502385242/qushuiyin/releases/latest**

安装时系统会提示「未知来源应用」，允许浏览器/文件管理器安装就行。APK 用固定密钥签名，
以后发的新版本可以直接覆盖安装，不用先卸载。

自己发新版只要一条命令（自动升 versionCode、编译、验签、传 Release）：

```bash
bash tools/release.sh 0.3.0 "一句话说明这版改了什么"
```


## 取数链路：移动页拿 `video_id`，play 接口拿无水印原画

只有两步，都不需要账号、不需要签名：

```
① WebView 打开作品页（移动 UA）→ 从页面里读出 video_id（形如 v0200fg10000...）
② GET aweme.snssdk.com/aweme/v1/play/?video_id=... → 302 → CDN 上的无水印 mp4
```

第 ② 步实测结果：`HTTP 200`、`video/mp4`、10,120,457 字节、1080×1920，抽帧无任何水印。
`ratio` 参数被服务端忽略——给的一律是它能提供的最高档，所以只请求一次。

第 ① 步为什么非得跑 WebView：`video_id` 不在任何纯 HTTP 响应里（分享页 HTML、旧
`iteminfo` 接口都试过，要么空壳要么 200 空体），是页面 JS 现场算出来的。
好在移动页**不需要签名**，只要 UA 和客户端提示自洽（即不覆写 UA）就能正常渲染。

代价是解析要 3~8 秒（有一个「正在通过网页加载作品」的进度提示）。

### 走过的死路：桌面详情接口（0.2 ~ 0.3.3）

`aweme/v1/web/aweme/detail` 能一次给出 12 档清晰度，看起来是最优解，但它在真机上无解：

| 尝试 | 结果 |
|---|---|
| 裸离屏 WebView + JS 钩子 | ❌ 钩子注入时机在旧文档里，从来没生效过 |
| `shouldInterceptRequest` 转发请求 | ❌ 200 + **0 字节** |
| 补齐 Cookie（ttwid 等） | ❌ 仍是 0 字节 |
| 域名从 `www-hj` 换回 `www` | ❌ 仍是 0 字节 |

真因是用 CDP 精确复刻出来的：**UA 与客户端提示自相矛盾**。
App 把 UA 覆写成 `Windows Chrome/120`，而 `navigator.userAgentData` / `sec-ch-ua`
如实报告 `Android 143`。抖音只要看到这个矛盾，就不报错、只回空体，或者把整页 HTML
（830 KB）吐回来。Android WebView 能改 UA，**改不了客户端提示**，所以这条路不可能通。

复现脚本留在 `tools/probe-ch.js`：同一个页面，UA 与提示自洽时 73 KB 正常数据，
矛盾时空体。想验证的话直接跑它。

---

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 工程骨架、分享入口、链接识别 | ✅ |
| P2 | 解析核心（移动页拿 `video_id` + play 接口） | ✅ 真机跑通 |
| P3 | 下载器 + MediaStore 写入相册 | ✅ |
| P4 | 失败驱动的在线修复（拉取 → 验签 → 落盘 → 重试） | ✅ 代码完成 |
| P5 | 历史记录 | ⏳ |

**验证到什么程度**：
- 短链 → 作品 ID → 移动页 → `video_id` → play 接口，整条链路在真 Chrome 里跑通了
  （`tools/probe-mobile.js`），真机日志也走到了最后一步
- play 接口返回的片子下下来抽帧肉眼确认**无水印**：1080×1920、10,120,457 字节、无 logo 无账号水印
- 48 个单测全绿
- 保存到相册走 MediaStore（Android 10+）+ `IS_PENDING`，写完才可见，相册里不会留半截文件

---

## 构建

工程**必须放在纯 ASCII 路径**下（例如 `D:/qushuiyin`）。中文路径会让 Gradle 的单元测试工作进程
拿到乱码 classpath，表现为所有测试 `ClassNotFoundException`，没有任何配置项能绕过。

```bash
export JAVA_HOME=/path/to/jdk17          # 需要 JDK 17，JDK 8 编不了 AGP 8.x
export ANDROID_HOME=/path/to/android-sdk  # 或写进 local.properties 的 sdk.dir

./gradlew :app:assembleDebug      # 产物：app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest  # 解析层 + 修复判定层单测
```

`local.properties`（不进版本库）：

```properties
sdk.dir=D:/devtools/android-sdk
# 可选覆盖项
# repair.mirrors=https://raw.githubusercontent.com/a502385242/qushuiyin/main/config/
# repair.pubkey=<Base64>
```

公钥在 `build.gradle.kts` 里有内置默认值，正常不需要在这里写。

---

## 目录

```
app/src/main/java/com/qushuiyin/app/
├── parser/     WebDetailFetcher 离屏 WebView 取数（shouldInterceptRequest 转发请求）
│               DetailApiMatcher 接口匹配（纯 JVM，可单测）
│               DetailSource 取数接口 · DouyinParser 解析主流程
│               LinkParser 链接抽取 · JsonPath 候选路径 · HtmlExtractor 抠 JSON
│               ParseRules 规则结构 · ParseError 错误分类
├── repair/     MirrorClient 多镜像竞速 · RulesGate 采用判定（纯 JVM，可单测）
│               RulesStore 持久化 · RepairManager 编排 · Verifier ECDSA 验签
├── ui/         HomeScreen · HomeViewModel · Theme
├── model/      Aweme
└── core/       Http
config/         manifest.json + manifest.json.sig  ← 推到仓库的就是这两份
keys/           私钥（gitignore，只在本机）与公钥
tools/          manifest_tool.py 签名工具 · setup-toolchain.sh 环境安装
                probe-*.js 真浏览器探测脚本（验证取数链路用，不参与打包）
```

---

## 在线修复

抖音改版导致解析失败时，App 会明确报「页面结构有变化」，并提供「在线修复」按钮。
点击后：并发拉取三个镜像 → 取最先返回的一份 → **验签** → 校验 `minAppVersion` 与规则版本
→ 原子写入 DataStore → 用新规则重试当前链接。

只有这一种失败会显示修复按钮。断网、链接无效、作品已删都不会，避免把修复按钮变成摆设。

### 发一份新规则的完整流程

```bash
# 1. 改规则：把 schemaVersion 加 1（必须大于上一次，否则客户端判定为「无更新」）
vim config/manifest.json

# 2. 重新签名（签名对象是文件原始字节，改一个空格都要重签）
python tools/manifest_tool.py sign config/manifest.json

# 3. 本地自检
python tools/manifest_tool.py verify config/manifest.json

# 4. 提交并推送
git add config/manifest.json config/manifest.json.sig
git commit -m "rules: v3 适配新版字段"
git push
```

用户下次点修复即可恢复；如果之前解析成功过，App 会静默预取（6 小时内最多一次），
真正遇到失效时规则往往已经是新的。

**镜像顺序是按国内实测可达性排的**，不是按「谁的官方」排：

| 顺序 | 地址 | 实测 |
|---|---|---|
| 1 | `fastly.jsdelivr.net` | 0.7~1.4s，稳定 |
| 2 | `cdn.jsdelivr.net` | 同源备用 |
| 3 | `raw.githubusercontent.com` | 内容最权威、更新最即时，但国内常卡在 DNS/TLS 阶段，实测偶发连不上 |

### 缓存陷阱：为什么不能「谁先回来用谁」

jsDelivr 对 `@main` 有约 12 小时边缘缓存，而且**不认 query 参数**——加 `?v=123` 一样返回旧内容
（purge API 报告成功也未必立刻生效，实测 purge 后仍然拿到旧版）。

于是出现过一个真实的 bug：镜像竞速时 CDN 比 raw 先返回，**旧规则永远赢过新规则**，
你推了 v3，用户点修复拿到的还是 v2，还提示「已是最新」。

现在的做法：`MirrorClient.fetchAll()` 把窗口内所有响应都收上来，
`RulesGate.choose()` **取版本号最大的那份**。旧缓存于是变成无害噪声，而不是决策依据。
回归测试用一份真发过的 v2（签名有效、内容过时）验证这个行为。

推完规则想确认线上生效（**直接看 raw，别信 CDN 的即时性**）：

```bash
curl -sL "https://raw.githubusercontent.com/a502385242/qushuiyin/main/config/manifest.json" | head -3
```

### 密钥

`keys/private.pem` 是这份方案唯一的信任根。**它丢了，老版本 App 就再也无法信任新规则**，
只能发版换公钥。当前它只在开发机上，`.gitignore` 已排除。

将来接 CI 时用 Secret 注入，绝不要提交。

---

## 边界

- 热修复只能救「字段位移、换包装键、域名变化」这类变更。如果抖音改成异步加密接口，
  或者加了新的签名校验，规则再多也修不了，只能发版。App 里要留「检查新版本」的出口。
- 远程规则能改的只有 UA、域名、取值路径。**绝不允许规则指定「去哪下载脚本并执行」**，
  那等于给自己装一个远程代码执行后门。
- 用途限定在个人收藏与学习研究。不要做批量爬取、不要二次分发。这类工具也上不了国内应用商店。
