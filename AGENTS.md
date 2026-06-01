# TiviMateLite — Agent 指南

项目：Android TV IPTV 播放器，专为晶晨 S905L3（2GB RAM、Android 9）低端机顶盒打造。
像素级致敬 TiviMate 架构，RAM 目标 ≤60MB，秒切台，不掉帧。

---

## 构建与运行

```bash
# 构建（GitHub Actions 统一用 JDK 17 + Gradle 8.10.2）
gradle assembleDebug --no-daemon --stacktrace --info

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

CI 位于 `.github/workflows/android.yml`，推送 main/master 或手动触发。

**构建约束**：
- AGP 8.7.3 + Kotlin 2.0.21
- compileSdk = 35, targetSdk = 28（Android 9）, minSdk = 23
- ViewBinding + BuildConfig 均已开启

---

## 安装到机顶盒

```bash
adb connect <盒子IP>:5555
adb install -r app-debug.apk
adb shell monkey -p com.tivimatelite 1
```

**ADB over TCP 会在约 3 分钟无活动后断开**。长测时必须每 5s 发 keepalive：
```bash
# 后台 keepalive（长测必加）
powershell -c "while(1){sleep 5; adb shell echo alive > $null}"
```

---

## 核心架构

### 包结构（`com.tivimatelite`）

| 文件 | 职责 |
|---|---|
| `SplashActivity.kt` | 启动闪屏，900ms 后跳转 MainActivity |
| `MainActivity.kt` | 唯一 Activity：按键处理、切台逻辑、源故障转移、Ready-stall 监控 |
| `model/Channel.kt` | 频道数据类（name, logoUrl, groupName, streamUrl, epgText） |
| `parser/M3U8Parser.kt` | 流式 M3U8 解析器，基于 `BufferedReader.lineSequence()` + Kotlin Flow |
| `player/PlayerManager.kt` | **全局单例 ExoPlayer**（切台不复建，仅 `setMediaItem` + `prepare`） |
| `player/PlaybackHistoryStore.kt` | SharedPreferences 持久化上次播放的频道 |
| `ui/ChannelAdapter.kt` | RecyclerView 适配器，DiffUtil + Glide RGB_565 |
| `data/RemotePlaylistRepository.kt` | 远程播放列表拉取，退回到本地 asset |
| `web/LocalAdminServer.kt` | NanoHTTPD Web 后台（端口 5220） |
| `web/LocalAdminServerManager.kt` | 后台生命周期管理 |
| `web/PlaylistStore.kt` | 直播源管理模式（内置 / 自定义） |
| `web/FileLogStore.kt` | 文件日志（`cacheDir/tivimate_diag.txt`），含未捕获异常处理器 |
| `web/AppLogStore.kt` | 内存环形缓冲区日志（500 行），供 Web 后台查看 |

### 播放器核心设计
```
PlayerManager (object)
  └─ ExoPlayer (lazy singleton, 永不 release)
       ├─ LoadControl: minBuffer=5s, maxBuffer=20s, playbackBuffer=2s, rebuffer=5s
       ├─ HttpDataSource: timeout 5s/12s, keep-alive, cross-protocol redirect
       ├─ RenderersFactory: 反射启用 setEnableAudioTrackPlaybackParams
       └─ Listeners: onPlayerError → 硬解异常捕获不崩溃
```

### 切台逻辑
```
遥控上下键 → pendingSwitchIndex + 300ms debounce → switchChannelImmediately()
  └─ 300ms 内继续按则重置 debounce，仅最后一次生效
  └─ 频道多源 → 按顺序尝试，失败自动切下一源
  └─ 所有源失败 → 退回到重试机制（最多 5 次，间隔递增 8-20s）
  └─ UnrecognizedInputFormatException → 自动以 forceHls=true 重试
```

### 直播源加载优先级
1. `BuildConfig.PLAYLIST_URL`（远程 URL，在 `app/build.gradle.kts` 中配置）
2. `LocalAdminServer` 候选 URL（`http://<ip>:5220/channels.m3u`）
3. 回退到本地 `assets/channels.m3u`

### 监控机制
- **Buffering failover**：缓冲超过 20s 自动切源
- **Ready-stall watch**：播放器 STATE_READY 但位置卡住超过 300s 触发切源（有 60s 预热期和 300s 恢复冷却）
- **网络速度**：每秒更新网速悬浮层
- **心跳**：每 10s 写 FileLogStore 一条 HEARTBEAT，用于确认 app 存活

### 按键映射
| 按键 | 动作 |
|---|---|
| 上下键 | 频道列表滚动（300ms 防抖） |
| 左右键 | 音量调节 |
| 数字键 0-9 | 数字选台（900ms 无输入后确认） |
| MENU / SETTINGS / INFO | 切换后台诊断信息浮层 |
| BACK | 无 Overlay 设计，在布局中未实现 |

---

## 关键约束

- **UI 纯 Kotlin + XML ViewBinding，禁止 Jetpack Compose / WebView**
- **纯黑背景（#000000），无动画过渡**
- **单 ExoPlayer 实例，切台严禁销毁重建**——只看 `setMediaItem` + `prepare`
- **ExoPlayer 硬解异常只捕获日志，不崩溃**——`isCodecFailure()` 遍历异常链匹配 MediaCodec
- **Glide 强制 `.override(64, 64)` + `DecodeFormat.PREFER_RGB_565`**
- **列表仅在静止时加载台标**，快速滑动时不加载

---

## 调试命令

```bash
# 内存
adb shell dumpsys meminfo com.tivimatelite

# CPU/内存实时
adb shell top -o PID,CPU,RES,ARGS | grep tivimatelite

# 日志过滤
adb logcat -s PlayerManager MainActivity MediaCodec ExoPlayer

# 取设备端日志文件（跨 ADB 断开持续存在）
adb shell cat /data/data/com.tivimatelite/cache/tivimate_diag.txt

# 强制停止
adb shell am force-stop com.tivimatelite

# 清除数据
adb shell pm clear com.tivimatelite

# 卸载
adb uninstall com.tivimatelite
```

---

## 已知问题

| 优先级 | 问题 | 说明 |
|---|---|---|
| P1 | `UnexpectedDiscontinuityException` | 中国移动源音频 PTS 每 30-40s 跳变导致周期性卡顿。已尝试反射开启 `setEnableAudioTrackPlaybackParams`，效果待确认 |
| P2 | `fmt=ts2hls` 源 HLS timeline 不连续 | 实时转码 HLS 流导致 `currentPosition` 卡住，当前 300s ready-stall 保守处理 |
| P3 | ADB keepalive 自动化 | 长测需要后台 keepalive 脚本 |

---

## 开发者约定

- 仓库根目录下 `要求.txt` 是原始架构需求文档，比 AGENTS.md 详细，如需理解设计意图可读它
- `TODO_HANDOFF.txt` 是新旧 session 间的上下文传递文件，记录最新进展
- **PowerShell 中 `$pid` 是只读自动变量**，永远不能赋值，用 `$currentPid` 代替
- 修改 `app/build.gradle.kts` 中的 `PLAYLIST_URL` 来切换远程直播源
- Web 后台：`http://<盒子IP>:5220/`（日志实时刷新，2s 轮询）
