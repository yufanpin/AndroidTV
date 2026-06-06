# TiviMateLite

Android TV IPTV 播放器，专为晶晨 S905L3（2GB RAM、Android 9）低端机顶盒打造。
像素级致敬 TiviMate 架构，RAM 目标 ≤60MB，秒切台，不掉帧。

---

## 功能特性

| 特性 | 说明 |
|---|---|
| **秒切台（Wave 3）** | 移除串行 HTTP 探测，换台直连 ExoPlayer `prepare()`，节省 350~3650ms/次 |
| **缓冲策略控制（Wave 2）** | Web 后台可切换 FAST_SWITCH / BALANCED / STABLE 三种缓冲策略 |
| **解码回退控制（Wave 2）** | Web 后台可切换 HW_ONLY / HW_WITH_SW_FALLBACK / SW_PREFERRED |
| 智能线路记忆（P0） | 播放成功自动记录域名，切台优先选记忆中可播放的源，失败自动移除 |
| 播放错误自愈（P1） | BEHIND_LIVE_WINDOW → 跳默认位置重试；PARSING_CONTAINER_UNSUPPORTED → 自动换格式（auto→HLS→Progressive） |
| 加载超时保护（P2） | 15s 超时自动切下一源（独立于 35s 缓冲超时） |
| SurfaceView 渲染（P3） | 裸 SurfaceView 替代 PlayerView，切台无黑闪 |
| 扩展解码器支持（P4） | EXTENSION_RENDERER_MODE_ON，内置/ffmpeg 解码器兼容 |
| 解码器信息采集（P5） | AnalyticsListener 实时采集视频/音频解码器名称、分辨率、码率等 |
| 容器格式自动探测 | 先 auto 让 ExoPlayer 自动推断，失败后 HLS → Progressive 轮换 |
| Web 后台管理 | NanoHTTPD 内置管理面板（端口 5220），查看日志、管理源、切换缓冲/解码策略 |
| 文件日志持久化 | 写入 `cacheDir/tivimate_diag.txt`，ADB 断开后仍可拉取 |
| 心跳监控日志 | 每 10s 写入 HEARTBEAT 确认 app 存活 |
| 定向日志写入 | `FileLogStore` 批量刷新（2s/10 行 + 崩溃时强制刷新），减少 I/O 开销 |

---

## 快速开始

### 使用 GitHub Actions 构建

1. 把项目推送到 GitHub 仓库。
2. 打开仓库 `Actions` → 自动触发 `Android CI`。
3. 等待任务完成后下载 `TiviMateLite-debug-apk` artifact。
4. 解压得到 `app-debug.apk`。

CI 配置：JDK 17 + Gradle 8.10.2 + AGP 8.7.3 + Kotlin 2.0.21。

### 准备直播源

默认从 asset 路径读取：

```text
app/src/main/assets/channels.m3u
```

改为远程源（`app/build.gradle.kts`）：

```kotlin
buildConfigField("String", "PLAYLIST_URL", "\"http://your-server/channels.m3u\"")
```

启动时优先拉取远程源，失败回退本地 asset。

示例：

```m3u
#EXTM3U
#EXTINF:-1 tvg-name="CCTV 1" tvg-logo="https://example.com/logo.png" group-title="Test",CCTV 1
http://example.com/live/cctv1.m3u8
```

### 安装到机顶盒

```bash
adb connect BOX_IP:5555
adb install -r app-debug.apk
adb shell monkey -p com.tivimatelite 1
```

---

## 核心架构

```
PlayerManager (object, 单例 ExoPlayer)
  ├─ setMediaItem + prepare 切台（不重建 player）
  ├─ P1 容器格式重试链 (auto→HLS→OTHER)
  ├─ P4 EXTENSION_RENDERER_MODE_ON
  ├─ P5 AnalyticsListener 解码器信息采集
  └─ BufferProfile / DecoderFallbackPolicy 可配置（Web 后台）

ChannelSwitcher
  ├─ 150ms 防抖 + 直连 playUrl（无 precheck 串行开销）
  ├─ P0 智能选源（记忆域名优先）
  ├─ P2 15s 加载超时 (cancelLoadTimeout)
  └─ 35s 缓冲超时 failover

PlayableHostStore (SharedPreferences)
  └─ addHost / removeHost / getHosts / extractHost

PlaybackTuningPrefs (SharedPreferences)
  └─ 持久化 BufferProfile + DecoderFallbackPolicy

MainActivity
  ├─ P3 SurfaceView (setVideoSurfaceView)
  ├─ P0 STATE_READY→记忆 / PLAYER_ERROR→移除
  ├─ P2 STATE_READY→取消加载超时
  └─ 启动时加载 PlaybackTuningPrefs 配置播放器

LocalAdminServer (NanoHTTPD :5220)
  ├─ 日志实时查看（2s 轮询）
  ├─ POST /profile/buffer → 切换缓冲策略
  └─ POST /profile/decoder → 切换解码回退策略
```

### 关键设计

- **单 ExoPlayer 实例**：切台只调 `setMediaItem` + `prepare`，永不 `release`/重建
- **串行 precheck 已移除**：旧版每次换台先做 HTTP `Range:bytes=0-0` 探测（200~3500ms），现直接拉流播放，故障由 15s 加载超时覆盖
- **SurfaceView**：裸 SurfaceView 替代 PlayerView，消除切台黑闪
- **纯黑背景**：`#000000`，无动画过渡
- **Glide**：强制 `override(64,64)` + `RGB_565`，滑动时不加载
- **硬解异常**：捕获 `MediaCodec` 异常链，只日志不崩溃
- **多源 URL 解析**：`#` 分割多线路，流式 `BufferedReader.lineSequence()` + Kotlin Flow

---

## 调试命令

### 基础检查

```bash
adb shell dumpsys meminfo com.tivimatelite
adb shell top -o PID,CPU,RES,ARGS | grep tivimatelite
adb logcat -s PlayerManager MainActivity
```

### 功能验证

```bash
# 解码器信息
adb logcat -s PlayerManager | findstr "Video decoder\|Audio decoder"
# → "Video decoder: OMX.amlogic.hevc.decoder.awesome (init=XXms)"
# → "Audio decoder: OMX.google.aac.decoder (init=XXms)"

# 加载超时
adb logcat -s ChannelSwitcher | findstr load_timeout
# → "Load timeout (15000ms), trying next source"

# 容器格式重试
adb logcat -s PlayerManager | findstr "Container unsupported"
# → "Container unsupported, retrying as type=2"

# 线路记忆
adb logcat -s MainActivity | findstr "playable hosts"
# → "Removed failed host from playable hosts: http://..."

# 换台速度（测量 precheck 移除后的关键路径耗时）
adb logcat -s ChannelSwitcher | findstr "Playing"
# 示例输出：
# 06-06 19:02:22.427 Playing 金鹰卡通 source 1/3
# 06-06 19:02:22.418 → 06-06 19:02:22.427 = ~9ms debounce-to-play（纯 prepare 时间见 logcat 时间差）

# 缓冲策略 / 解码策略
adb logcat -s MainActivity | findstr "profile\|policy\|tuning"
# → "Loaded tuning prefs: buffer=FAST_SWITCH decoder=HW_WITH_SW_FALLBACK"

# 设备端日志文件（ADB 断开后仍可用）
adb shell cat /data/data/com.tivimatelite/cache/tivimate_diag.txt
```

### 性能命令

```bash
# ADB keepalive（长测必开，ADB 约 3 分钟无活动断开）
powershell -c "while(1){sleep 5; adb shell echo alive > $null}"

# 内存快照
adb shell dumpsys meminfo com.tivimatelite

# 日志过滤
adb logcat -s PlayerManager MainActivity MediaCodec ExoPlayer ChannelSwitcher
```

### 恢复命令

```bash
adb shell am force-stop com.tivimatelite
adb shell pm clear com.tivimatelite
adb uninstall com.tivimatelite
```

---

## 目标表现

- 启动后内存贴近 60 MB
- 快速按上下键不卡顿
- 切台不重建 ExoPlayer
- 硬解异常不崩溃
- Overlay 显示隐藏无动画

---

## 按键映射

| 按键 | 动作 |
|---|---|
| 上下键 | 频道列表滚动（150ms 防抖） |
| 左右键 | 音量调节 |
| 数字键 0-9 | 数字选台（900ms 无输入确认） |
| MENU / SETTINGS / INFO | 切换后台诊断信息浮层 |

---

## 已知问题

- **UnexpectedDiscontinuityException**：中国移动源音频 PTS 每 30-40s 跳变导致卡顿，已通过反射 `setEnableAudioTrackPlaybackParams` 缓解，效果待确认
- **fmt=ts2hls HLS timeline 不连续**：实时转码流导致 currentPosition 卡住，300s ready-stall 保守兜底
- **ADB 约 3 分钟无活动断开**：长测需后台 keepalive
