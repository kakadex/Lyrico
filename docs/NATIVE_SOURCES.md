# LyricoX 内置源（V0.01）

V0.01 把 **音乐整理工具（MusicOrganizer）** 的多源在线能力整合进 LyricoX：
**不装任何插件**，也能批量匹配标签、批量补歌词。

上游 Lyrico 的搜索源 100% 依赖 JS 插件（QuickJS），装完是白板；本版补齐了 8 个内置源，
全部走宿主原生实现，随 APK 发布、不可卸载、无需配置。

## 1. 内置源清单

| 源 | 键（id） | 能力 | 接口 | 实测状态 |
|---|---|---|---|---|
| MusicBrainz | `native.musicbrainz` | 元数据 | `musicbrainz.org/ws/2/recording` | ✅ 200 |
| Deezer | `native.deezer` | 元数据 | `api.deezer.com/search` | ⚠️ 本机代理 502，代码按官方文档实现 |
| iTunes | `native.itunes` | 元数据 | `itunes.apple.com/search` | ✅ 200 |
| 网易云音乐 | `native.netease` | 元数据 | `music.163.com/api/search/get/web` | ✅ 200（有 IP 限频） |
| QQ 音乐 | `native.qqmusic` | 元数据 | `c.y.qq.com/.../client_search_cp` + `smartbox_new` | ✅ smartbox 200（主接口本机 500） |
| 酷狗音乐 | `native.kugou` | 元数据 | `mobilecdn.kugou.com/api/v3/search/song` | ✅ 200 |
| 咪咕音乐 | `native.migu` | 元数据 | `app.c.nf.migu.cn/MIGUM2.0/.../search_all.do` | ✅ 200 |
| LRCLIB | `native.lrclib` | 歌词 | `lrclib.net/api/get` + `/api/search` | ✅ 200 |

> 未标注来源的均为 **匿名只读接口、零 API Key**。
> 「实测状态」是在本机（Windows + 代理）用 curl 打的结果，手机上可能不同；
> 不可达的源只会返回空结果，不影响其它源。

## 2. 与上游架构的接法

内置源实现的是上游既有的 `SearchSource` 接口，因此**不需要任何新 UI**：

```
SearchSourceProvider（唯一出口）
  ├── PluginSearchSourceManager  → JS 插件源（数据库里的 manifest）
  └── NativeSourceRegistry       → 8 个内置源（本版新增）
```

- **id 加 `native.` 前缀**：插件 id 由 manifest 决定，没法保证不重名，前缀是唯一可靠的隔离手段。
  该 id 会被写进「批量匹配的源顺序」配置，**发布后不可再改**，否则用户已保存的顺序会失效。
- **排序号 500**：插件源的 sortOrder 更小，所以用户装了插件时内置源排在后面做兜底；
  没插件时它们就是全部可用源。
- **打分交给宿主**：上游已有 `MusicMatchUtils.calculateMatchScore`（片段匹配 + 时长 + 名次加分），
  内置源只负责把字段取干净、时长统一成毫秒，不另做一套置信度聚合——
  这样内置源与插件源在同一套规则下比较，谁更准一目了然。

## 3. 移植时踩到的坑

**① 时长单位不统一。** 上游 `SongSearchResult.duration` 是**毫秒**
（`durationSimilarityOrNull` 的阈值是 1500/3000/5000ms）。而各家接口给的单位不一样：

| 源 | 原始单位 |
|---|---|
| 网易 | 毫秒 |
| Deezer / QQ / 酷狗 / 咪咕 | 秒 |
| iTunes | 毫秒（`trackTimeMillis`） |
| LRCLIB | 秒（浮点） |

全部在 `NativeHttp.secondsToMillis()` 一次换算，绝不让秒混进毫秒的管道——
这会让时长相似度全判 0，进而毁掉整个匹配排序，而且症状是「排序看着怪」而不是报错，极难排查。

**② 咪咕的旧端点已废弃。** MusicOrganizer 里用的 `m.music.migu.cn/migu/remoting/scr_search_tag`
现在会 **301 跳到 H5 页面**，返回 HTML（JSON 解析失败 → 空结果）。
换成移动端 MIGUM2.0 的 `search_all.do` 才拿到正常 JSON。

**③ QQ 主搜索接口会拒服务。** `client_search_cp` 在部分网络下直接 500（空响应体），
所以做成两级：先主接口（专辑/时长/封面 mid 都全），失败再退 `smartbox_new`（只有歌名 + 歌手）。

**④ 网易按 IP 限频。** 密集请求返回 `405 操作频繁`，批量匹配每首歌都要查一次，
不加节流会成片失败。加 350ms 票制限流。

**⑤ 票制限流不能用 synchronized 包住整段请求。** 锁内只预约发车时间、锁外等待，
否则并发请求会被串成一条长队，延迟放大成 N 倍。见 `NativeRateLimiter`。

**⑥ 必须做主机级熔断，而不是只做节流。** 这四家境内接口一旦拒服务，会「每首歌都拒一次」：
批量匹配 100 首就是 100 次白跑（还各自带着 5s 超时）。移植源实现的 `Http.HostState`：
按主机累计失败，连续 2 次后冷却，**冷却期内不发请求直接返回空**。

| 状态码 | 判定 | 冷却 |
|---|---|---|
| 403 / 405 / 429 | 限流（网易的 `405 操作频繁` 就是这一类） | 90s |
| 5xx / 连接异常 | 服务端故障 | 45s |
| 404 | 「库里没有这首」，**不计失败** | — |
| 其它 4xx | 本次查询无结果，不计失败 | — |

实测效果：QQ 的 `client_search_cp` 在这台机器上稳定 500，两次之后整批任务就不再打它，
而不是每首歌浪费一次请求。

**⑦ MusicBrainz 必须带可联系的 User-Agent**，官方要求 1 req/s，否则封 IP。
UA 统一写在 `NativeHttp`，限流 1050ms。

**⑧ 单源失败必须只损失这一家。** 全部查询走 `safeQuery`：吞掉异常但**保留协程取消语义**
（批量任务被取消时必须立刻停，不能当成「这个源没结果」继续跑）。

## 4. 本版没做的（后续版本）

- **写入撤销 / 批次回滚**：MusicOrganizer 的 `TagTransaction + Journal` 按 batchId 整批逆序回滚。
  移植需要新增 Room 表 + 迁移，风险较高，留到 V0.02。
- **重复文件检测 / 目录整理 / 清理**：`core/Duplicates`、`core/Organizer`、`core/Cleaner`，
  需要新页面，V0.03 之后再评估。
- **跨源共识置信度上限**：MusicOrganizer 会按「哪些源同时返回」给置信度封顶
  （MusicBrainz + Deezer 同现 → 1.00，仅境内两家 → 0.80）。上游是各源独立返回、由宿主统一打分，
  拿不到这个全局视图，暂不实现。

## 5. 新增文件

```
lyrico-app/src/main/java/com/lonx/lyrico/plugin/source/native/
├── NativeHttp.kt              请求封装（分级超时 / UA / 异常不外泄）+ Json 宽松取值
├── NativeRateLimiter.kt       票制限流
├── NativeSearchSource.kt      基类（id 前缀 / 能力位 / safeQuery / 结果构造）
├── OverseasSources.kt         MusicBrainz · Deezer · iTunes
├── DomesticSources.kt         网易 · QQ · 酷狗 · 咪咕
├── LrclibSource.kt            LRCLIB 歌词源
└── NativeSourceRegistry.kt    清单

修改：
  plugin/source/SearchSourceProvider.kt   合并插件源与内置源
  di/AppModule.kt                         注册 NativeHttp + 8 个源
  lyrico-app/build.gradle.kts             版本号 → 0.01 / versionCode 22
```

## 4. 内置歌词源：网易 + QQ（V0.02 起）

MusicPlayer2 的做法是**客户端直连官方接口**（不经过第三方歌词站），所以中文/日文曲的翻译轨
比 LRCLIB 全。我们照搬这两条接口，翻译由宿主解析：

| 源 | 端点 | 返回 |
|---|---|---|
| 网易 | `https://music.163.com/api/song/lyric?os=osx&id=<song id>&lv=-1&kv=-1&tv=-1` | `lrc.lyric`（原文）+ `tlyric.lyric`（翻译） |
| QQ | `https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=<mid>&format=json&nobase64=1` | `lyric`（原文）+ `trans`（翻译，部分曲目才有） |

坑：
- **`lv=-1&kv=-1&tv=-1` 三个参数都要带**。`-1` 是客户端语义「有就给我」，
  只传 `lv=1` 时 `tlyric` 常常整段为空（MusicPlayer2 的取值即如此）。
- QQ 必须带 `Referer: https://y.qq.com/`，否则返回空或报错；`nobase64=1` 拿明文。
- QQ 的 `trans` 覆盖不稳定：实测《晴天》《打上花火》都是空串，属正常结果，按「有就带」处理。

**翻译怎么进宿主**：不自己造 `LyricsResult`，而是把原文与翻译合成一份 LRC ——
同一时间点出现两行时，`LrcDocumentFormat.separateLrcTracks` 会自动把第二行归到翻译轨。
关键是**不能拿时间戳字符串当 key**：网易原文是 `[00:19.629]`、翻译是 `[00:19.64]`，
小数位不同对不上；改成按毫秒做「最近且 ≤150ms」的匹配（`buildBilingualLrc`）。

## 5. 封面搜索（V0.02 起）

`SearchSource` 有 `searchCovers`，但内置源原先一律返回空，封面 tab 只有插件源。
现在基类默认实现为「复用 `searchSongs` 的结果 + 只留带封面的 + 换成大图」：

| 源 | 搜索结果里的封面 | 封面搜索时换成 |
|---|---|---|
| 网易 | `album.picUrl` | 追加 `?param=800y800` |
| QQ | `T002R300x300M000<albummid>.jpg` | `T002R800x800M000<albummid>.jpg`（500/800 实测均 200） |
| 咪咕 | `imgItems[].img`（`imgSizeType=03` 最大） | 原样 |
| Deezer | `album.cover_xl`（1000px） | 原样 |
| iTunes | `artworkUrl100` | 搜索时已换成 `600x600` |

注意 QQ 封面用的是 **albummid 不是 songmid**，混用会 404；MusicBrainz 本身不带封面
（要再查 CoverArtArchive），所以它的 `coverEnabled` 保持 false。

## 6. Kid3 的数据获取方式（结论：部分可做）

Kid3 的取数插件在 `src/plugins/`：`musicbrainzimport`、`discogsimport`、`acoustidimport`、
`amazonimport`、`freedbimport`。逐条对照：

| Kid3 源 | 能否搬 | 说明 |
|---|---|---|
| MusicBrainz | ✅ 已有 | V0.01 已内置 |
| AcoustID 指纹 | ⚠️ 大工程 | `api.acoustid.org/v2/lookup?client=<key>&meta=recordingids&duration=&fingerprint=`；<br>指纹要靠 **chromaprint**（C 库，需按 ABI 编进 APK）+ 解码取 PCM，Lyrico 已带 FFmpeg 可复用。<br>价值：无标签文件也能识别。代价：native 构建 + 每首解码耗时 |
| Discogs | ⚠️ 中等 | 需个人 token，限流严格，偏黑胶/实体碟，华语曲库弱 |
| Amazon | ❌ 不做 | PAAPI v5 需签名密钥 + 联盟账号，对个人已无免费额度 |
| freedb | ❌ 不做 | 2020 年已停服；原接口依赖 CD 碟 ID，对文件库无意义 |
