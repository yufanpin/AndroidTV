# 第三方组件与来源

## yspcore

`yspcore/` 的 Java 内核移植自：

- 项目：https://github.com/2909272751/ysp-live-android
- 目录：`core/`（纯 Java，零 Android 依赖）
- 许可：MIT（见 `yspcore/LICENSE`），版权归 YSP Live Android contributors

上游 README 明确说明：其协议常量与频道数据来自第三方实现，不在 MIT 许可范围内，
权利归原作者。本仓库仅将其用于个人学习与自用集成，未声称作何授权或背书。

## 运行期依赖

- AndroidX Media3 / ExoPlayer（`app` 模块未使用，`main` 分支历史依赖）
- IJKPlayer（`app/libs/ijkplayer-cmake-release.aar`，debugly/ijkplayer k0.8.9 预编译）
- NanoHTTPD 2.3.1（本地控制台）
- Glide 4.16.0（台标加载）
- Kotlin Coroutines 1.9.0

## 免责声明

本项目与央视、央视频无任何关联；不存储、不分发视频内容，仅在本机转发上游公开接口。
仅限技术学习与个人研究，请勿用于商业用途或公开传播。
