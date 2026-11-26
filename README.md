# YSP TV（ysp 分支）

**一个 APK，打开就能看央视频直播。** 不需要订阅地址、不需要电脑、不需要 Docker。

本分支把央视频直播引擎直接编进 App，启动时在本机 `127.0.0.1:8767` 起一个轻量网关，
播放器从它取流；同时提供 **Web 控制台**（端口 5221），可在浏览器里看状态、流程、设置与日志。
`main` 分支仍是原来的 M3U 播放器；两者包名不同，可同时安装。

- 应用名：**YSP TV** · 包名：`com.tivimatelite.ysp`
- 频道：**64 路**（央视 / CGTN / 卫视 / CETV / 国学等），1080p 起播，支持高码率 / 4K / 8K
- 最低系统：Android 8.0（minSdk 26），按 Android 9 机顶盒（如 S905L3）调校

---

## 功能

| 能力 | 说明 |
|---|---|
| 本机直播引擎 | 纯 Java 内核（`:yspcore`），启动自动拉起，无外部服务依赖 |
| 64 路频道 | 央视、CGTN、卫视、CETV、国学等，双通道（设备协议高码率 + bk 1080p）自动兜底 |
| 播放器 | **Media3 ExoPlayer（HLS）**，前向 15s / 后向 30s 大缓冲，抗网络抖动 |
| 换台秒开 | 换台只换 MediaSource、复用播放器；保留旧频道分片缓存，切回直接命中 |
| 左右键重连 | 立即重连当前频道（不走缓存失效） |
| 数字键选台 | 最长 4 位，900ms 确认 |
| Web 控制台 | 端口 **5221**，四页签：状态 / 流程 / 设置 / 日志 + JSON API |
| 进程可视化 | 请求 → 选源 → 上游取流 → 列表改写 → 输出 → 播放器，实时事件流 |
| 日志 | App 与引擎日志合并，级别筛选、增量刷新、下载 |
| 开机自启 / 局域网共享 | 系统开关自启；可选把 8767 暴露给 TiviMate / PotPlayer |

---

## 安装

CI 每次推送 `ysp` 分支会构建 debug APK，从 GitHub Actions 的 `YSPTV-debug-apk` artifact 下载：

```bash
adb install -r app-debug.apk
```

桌面图标为「YSP TV」。调试签名使用仓库内 `app/ysp-debug.jks`（口令 `ysplive`），保证多次构建可覆盖安装。

---

## 使用

| 按键 | 动作 |
|---|---|
| 上 / 下 | 换台（左下角显示台名 + 序号） |
| 左 / 右 | 重连当前频道 |
| 数字键 | 数字选台（1–64，900ms 确认） |
| MENU / INFO | 诊断浮层（引擎端口、控制台地址、当前线路） |

启动后引擎自动初始化设备会话与频道列表，通常十秒内出首帧；失败时显示原因与「重试」。

---

## Web 控制台（5221）

```
http://<设备IP>:5221/
```

| 页签 | 内容 |
|---|---|
| 状态 | 引擎状态、端口、频道数、运行时长、内存、缓存命中率、当前频道、设备会话 guid |
| 流程 | 六段处理链路 + 最近事件流 + 当前频道 + 线路分布 |
| 设置 | 高码率开关、仅 4K、缓存、并发、换台防抖、端口、局域网共享、日志级别、音量、开机自启 |
| 日志 | App + 引擎合并日志，增量刷新，可筛选 / 清空 / 下载 |

> 标 `*` 的设置保存后自动重启引擎。5221 绑定 `0.0.0.0`，仅建议在可信局域网使用。

### HTTP API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 引擎与当前频道状态 |
| GET | `/api/channels` | 64 路频道状态 |
| GET | `/api/events?after=<seq>` | 流程事件增量 |
| GET | `/api/logs?after=<seq>&level=` | 日志增量 |
| GET | `/diag` | 引擎逐频道诊断（代理 8767） |
| GET | `/all.m3u` | 代理引擎订阅（给外部播放器） |
| POST | `/config` | 保存设置并重启 |
| POST | `/engine/restart` `/engine/stop` `/engine/start` | 引擎控制 |
| POST | `/audio/gain` `/boot/toggle` `/logs/clear` | 音量 / 自启 / 清日志 |

---

## 架构

```
YSP TV (com.tivimatelite.ysp)
├─ SplashActivity ──启动──> YspEngineService（前台服务）
│                              └─ YspGateway（:yspcore 纯 Java，移植自 ysp-live-android v9.6.5）
│                                   ├─ LocalServer :8767  /all.m3u  /<channel>.m3u8  /proxy.ts
│                                   ├─ ChannelEngine（64 路、JCE / BK / 设备协议、清晰度阶梯）
│                                   └─ TsSegmentCache（LRU 分片缓存）/ 日志总线
├─ MainActivity（Media3 ExoPlayer + SurfaceView）
│    └─ ChannelLoader ← http://127.0.0.1:8767/all.m3u（唯一频道来源）
│       └─ ChannelSwitcher（换台防抖 / 单源重试 / 缓冲故障转移）
└─ LocalAdminServer :5221（NanoHTTPD，Web 控制台）
```

- `:yspcore`：44 个 Java 文件，零 Android 依赖；移植自
  [`ysp-live-android`](https://github.com/yufanpin/ysp-live-android) 的 `core`（其原创代码 MIT）。
- 播放器：Media3 ExoPlayer 1.4.1（`media3-exoplayer` / `-hls` / `-ui`），替代早期 IJKPlayer。
- 数据目录：`Android/data/com.tivimatelite.ysp/files/ysp/`（设备身份、缓存状态）。
- 默认参数（真机调校）：缓存 128MB、单设备并发 4、**高码率开启**、**仅 4K 关闭**、**换台防抖 0s**。

### 为什么这样配

真机 A/B 实测（SKTWORTH 盒子 / Android 9 / 2GB）：

- 央视频 CDN 对**完整 GET（无 Range）**严重限速（2MB 分片要 20–40s），
  对 **Range 请求**可达数 MB/s。播放器直连 CDN 发完整 GET 必然卡，因此分片一律走本机
  `/proxy.ts`，由引擎按 512KB 分块并行 Range 取流并回填 LRU 缓存。
- 1080p bk 通道在该网络下分片 403 全挂，只有设备协议高码率通道可用；
  故默认开启高码率、关闭"仅 4K"，避免换台掉进必挂的 bk 通道。
- 换台防抖会强制先走 1080p 等 10s，期间首片 403 触发重试；默认 0s。
- 换台**不主动停止旧频道**后台线程与缓存（引擎 `IDLE_TIMEOUT=120s` 自动回收），
  切回已访问频道直接命中缓存，避免重复 resolve 触发上游"操作过于频繁"限流。
- 配置带版本迁移（`config_version=2`）：老用户升级后自动套用上述最优值，保留端口等个人偏好。

---

## 构建

推送到 `ysp` 分支触发 `.github/workflows/android.yml`：构建 debug APK + 跑单测 + 上传 artifact。

本地构建需 JDK 17+ 与 Android SDK（`platforms;android-35`、`build-tools;35.0.0`）：

```bash
gradle assembleDebug --no-daemon
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

---

## 真机验证（2026-11-26，64 频道全遍历）

在 Android 12 / x86_64 模拟器上逐个切换全部 64 个频道，实测：

| 场景 | 结果 |
|---|---|
| 引擎启动 | 高码率通道就绪、guid 正常、64 频道 |
| 冷启动首播 | 约 12–20s（首次设备协议握手 + 回源） |
| 连续播放 | 稳定 60s+ 无 BUFFERING、无错误 |
| **主流频道换台** | **3–5s READY，无错误**（CCTV-1~5/7~17、CGTN 系列、多数卫视） |
| 重连当前频道 | 缓存命中时 <1s READY |
| Web 控制台 | 四页签 + 4 个 JSON API 全部 HTTP 200 |

**换台耗时取决于缓存与上游状态**：
- 访问过 / 主流的频道走缓存约 3–5s；
- 部分频道（4K/8K、付费剧场、个别卫视）上游返回 **403/503 或"操作过于频繁"**，
  这是央视频对出口 IP 的上游限制，**非本应用代码缺陷**——同一频道在不同网络/时段结果不同；
- 引擎对此有兜底：bk 请求全局节流（1.2s 间隔 + 限流后 45s 冷却）、
  频道失败 15/60/120s 递增冷却、后台刷新失败退避到 60s、双通道（高码率 ↔ 1080p）自动切换。

> 若某频道暂时不可播，等几十秒再切通常恢复；密集连续换台（每秒一台）会加速耗尽上游配额，
> 建议正常节奏使用。盒子（ARM 真机）的硬解与网络路径与模拟器不同，通常表现更好。

> 若盒子系统时间被重置到过期年份，会导致 HTTPS 证书校验失败、引擎 503 无数据。
> 请开启设备网络自动校时，或 `adb shell date MMDDhhmmYYYY` 手动校正。

---

## 已知限制

1. **依赖上游接口**：复刻央视频客户端协议，上游改签名/风控会导致暂时不可用；引擎内置限流冷却与旧列表兜底。
2. **x86 模拟器**：靠 ARM 二进制翻译运行，换台/首播明显慢于真机；软解性能有限。
3. **4K/8K**：需 HEVC 硬解与 ≥50Mbps 带宽，解码不足时自动回落 1080p。
4. **回看**：引擎支持 7 天时移，App 暂未提供回看时间选择 UI。
5. **无 M3U 兜底**：本分支刻意不内置第三方源，引擎不可用时只显示诊断信息。

---

## 来源与声明

- 内核移植自 [`ysp-live-android`](https://github.com/yufanpin/ysp-live-android) 的 `core`
  （原创代码 MIT，见 `yspcore/LICENSE`）；协议常量与频道数据权利归原作者。
- 本项目与央视、央视频无任何关联，不存储、不分发视频内容，仅在本机转发公开接口。
- 仅供技术学习与个人研究，请勿用于商业用途或公开传播；使用风险由使用者自行承担。
- `main` 与 `ysp` 分支互不影响，本分支改动不会自动合并回 `main`。
