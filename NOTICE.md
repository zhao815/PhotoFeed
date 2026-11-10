# NOTICE — 版权与第三方许可归属

## 本项目

刷相册 PhotoFeed
Copyright (C) 2026 PhotoFeed contributors

本项目源码以 **GNU General Public License v3.0（GPL-3.0）** 发布，条款全文见 [LICENSE](LICENSE)。
本文件集中记录版权人与第三方归属声明，随源码一并分发；分发修改版时请保留本文件。

## 随源码分发的第三方素材

### Google Material Design Icons（Apache License 2.0）

`PhotoFeed/app/src/main/res/drawable/` 下下列矢量图标的 `pathData` 取自 Google Material Design Icons，
按 **Apache License, Version 2.0** 授权：

- `ic_arrow_back.xml`、`ic_delete.xml`、`ic_fullscreen.xml`、`ic_fullscreen_exit.xml`
- `ic_like.xml`、`ic_like_filled.xml`、`ic_note.xml`、`ic_settings.xml`
- `ic_share.xml`、`ic_volume.xml`、`ic_volume_off.xml`

来源：<https://github.com/google/material-design-icons>
许可全文：<https://www.apache.org/licenses/LICENSE-2.0>

说明：仅使用其路径数据，尺寸与配色由本项目调整；版权归原作者所有。

以下图形为本项目自行绘制，不涉及第三方素材：
`ic_launcher.xml`、`ic_playback_speed.xml`，以及全部 `bg_*.xml`、`seekbar_*.xml`、
`video_controls_bg.xml`、`bottom_gradient.xml`。

## 构建期依赖

下列依赖通过 Gradle 从 `google()` / `mavenCentral()` 获取，**不随本仓库分发**。
各库许可证原文以其发布仓库为准；Apache-2.0、BSD 3-Clause、MIT 均与 GPL-3.0 兼容。

| 依赖 | 版本 | 许可 |
|---|---|---|
| AndroidX（core-ktx / appcompat / activity / fragment / constraintlayout / coordinatorlayout / recyclerview / viewpager2 / lifecycle） | 见 `app/build.gradle.kts` | Apache-2.0 |
| AndroidX Room | 2.6.1 | Apache-2.0 |
| AndroidX Media3（media3-exoplayer / media3-ui） | 1.3.1 | Apache-2.0 |
| Google Material Components | 1.11.0 | Apache-2.0 |
| Glide | 4.16.0 | BSD 3-Clause / MIT / Apache-2.0 混合，见其仓库 LICENSE |
| Kotlin / kotlinx-coroutines-android | 1.9.24 / 1.7.3 | Apache-2.0 |

## 隐私说明

本仓库不含任何用户媒体、测试素材、设备标识、账号信息、签名密钥或日志文件。
应用本体只申请读取照片 / 读取视频权限，不含 `INTERNET` 权限，所有记录仅保存在本机。
