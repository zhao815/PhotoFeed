# 刷相册 PhotoFeed

一个**纯本地、无网络、无账号**的沉浸式刷相册 Android 应用——像刷短视频一样刷你手机里的照片、视频和动态照片。

> 无 `INTERNET` 权限，物理上无法联网、无法上传任何内容。所有数据只存在你的手机上。

## 功能特性

### 三个频道，左右滑动切换
- **照片**：静帧按原始比例完整显示（不裁切、不满屏拉伸）；双指缩放、可缩回；长截图短边铺满、可拖动、双击适应
- **视频**：进入频道自动播放、默认有声；底部控制栏支持**拖动进度条快进/快退**与**倍速选择（0.5x ~ 3.0x，点按钮弹出档位自己选）**；仅横版视频显示「全屏观看」，进入真正沉浸式全屏（状态栏/导航栏隐藏、按真实宽高比以整屏居中、不裁切）
- **动图**：苹果 Live Photo / 安卓动态照片按内容 ID 自动配对，动起来且保留原声，循环播放、翻页不闪烁

### 刷起来
- 无限刷：一轮不重复，刷完自动换新种子重新洗牌
- 防误触：左右切频道与上下刷内容的手势互不干扰

### 管理与记录
- 删除：立即生效，移入系统相册回收站（可在相册恢复）
- 笔记：给任意照片/视频写备注，本地保存
- 分享：调用系统分享
- 后台识别：进程级后台任务扫描动态照片，离开设置页仍继续，只增量扫新照片

## 从源码构建

**方式一：Android Studio（推荐）**

1. Clone 后用 Android Studio 打开 `PhotoFeed/` 目录
2. 同步 Gradle（AGP 8.4.2 / Kotlin，依赖走 google() 与 mavenCentral()）
3. 直接 Run，或 `assembleRelease` 出包

**方式二：命令行**

```bash
cd PhotoFeed
gradle assembleDebug     # 需 JDK 17、AGP 8.4.2、Gradle 8.7
```

> 仓库根目录的 `build.ps1` 是作者本机的**离线构建脚本**（自带 JDK/SDK/Gradle 缓存的 `toolchain/` 不入库），普通环境无需使用。

## 系统要求

| 项目 | 要求 |
|---|---|
| 最低系统 | Android 10 (API 29) |
| 已验证 | Android 11 (vivo V2123A)、Android 16 (vivo V2361A) |
| 权限 | 仅读取照片 / 读取视频（Android 13+ 支持部分授权） |

## 权限声明

申请的权限只有四个（`app/build.gradle.kts` 中的 `checkPermissions` 任务会在构建时强制校验）：

- `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`：浏览本地相册
- `READ_MEDIA_VISUAL_USER_SELECTED`：部分授权时只访问用户选中的媒体
- `READ_EXTERNAL_STORAGE`（仅 Android 12 及以下）

**不申请**：网络、位置、通讯录、短信、电话、相机、麦克风等一切其他权限。

## 仓库结构

```
├── PhotoFeed/                  # Android 工程主目录
│   ├── app/src/main/           # 源码 + 资源（Kotlin，单模块）
│   │   ├── java/…/data/        # 媒体查询、Live Photo 配对、动图识别、Room 存储
│   │   ├── java/…/ui/feed/     # 三频道刷图核心（ViewPager2 + ExoPlayer）
│   │   └── res/                # 布局 / drawable / 文案
│   └── …
├── 使用说明.md                  # 面向用户的使用说明
├── LICENSE                     # GPL-3.0 许可证全文
├── build.ps1                   # 作者本机离线构建脚本（仅供参考）
└── .gitignore
```

更详细的功能说明与真机验证记录见 [PhotoFeed/README.md](PhotoFeed/README.md)，用户手册见 [使用说明.md](使用说明.md)。

## License

本项目基于 [GNU General Public License v3.0](LICENSE)（GPL-3.0）开源。

这意味着你可以自由地：

- **使用**：个人或商业环境均可使用，无附加限制
- **研究**：阅读、修改全部源码
- **分发**：复制、传播本项目

同时需要遵守：**分发修改后的版本时，必须同样以 GPL-3.0 开源并保留原作者版权声明**；本软件不提供任何担保。

完整的法律条款见仓库根目录的 [LICENSE](LICENSE) 文件。
