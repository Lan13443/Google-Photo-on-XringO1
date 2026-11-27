# PhotoHDRFix

解决玄戒 O1 平台（`dijun` / `O1_asic`）小米机型在澎湃 OS（HyperOS）生动模式下，**Google 相册、小米系统相册、微信** 在 HDR/广色域场景出现全局色域切换（发黄、偏色）或全屏 HDR 异常的问题。

包含两部分（均严格运行在 App 层，不碰系统分区与 `system_server`）：

1. **PhotoHDRFix**（Xposed / LSPosed 模块，`v2.4.2`）：作用于 **Google 相册**（`com.google.android.apps.photos`）、**微信**（`com.tencent.mm`）及旧版 Java 架构的小米相册；
2. **GallerySrgbFix**（Zygisk Next Native 配套模块，`gallery_srgb_fix/`，`v1.0.0`）：作用于 **HyperOS 4 纯原生重构版小米相册**（`com.miui.gallery`）。

---

## 背景：为什么会偏黄 / 偏色

### 1. HDR 图层引发的平台色域切换（Google 相册）

用户相机拍摄的「杜比视界」视频，底层编码实际为 HLG：

- Profile 8.4，`transfer=arib-std-b67`（HLG），`color primaries=BT.2020`
- 容器 brands 含 `dby1`，本质是「杜比视界」壳包着 HLG

玄戒 O1 平台在启用**生动模式**（`display_color_mode=258`）时，一旦界面出现 HDR 或 Wide-Color 图层：

1. SurfaceFlinger 把屏幕显示色域从 `ColorMode::SRGB (7)` 切到 `ColorMode::DISPLAY_P3 (9)`（甚至触发 DisplayFeature HDR 模式切换）；
2. 图层叠加或 OOTF / 色域映射退出生动模式后切回标准色域，观感上表现为整块屏幕瞬间**发黄**。

在 Google 相册中，点开全屏播放视频时需保留真 HDR；但在信息流**缩略图自动播放** HDR 视频时，同样会触发全局切色域发黄。

### 2. 微信看图与选图预览切广色域（`Window.setColorMode`）

在微信全屏浏览他人发送的图片或全屏选取图片预览时，应用调用 `Window.setColorMode(COLOR_MODE_WIDE_COLOR_GAMUT)`，触发相同机制导致全屏偏色。

### 3. HyperOS 4 小米系统相册变为纯原生进程（Java LSPosed 无法注入）

在 HyperOS 4（如 Xiaomi 15S Pro / `OS4.0.0.19`）上，小米系统相册（`com.miui.gallery`）已重构为纯原生 **Rust (`hyos_spawner`) + Flutter Impeller (Vulkan)** 应用（`base.apk` 内无 `classes.dex`）：

- Java 层 Xposed / LSPosed 的 `handleLoadPackage` 无法进入该进程；
- 且小米相册首页瀑布流与普通看图时（无 HDR 图层），直接通过原生图形接口输出 `dataspace=DISPLAY_P3 (143261696)`，导致一打开相册就全局切到 `DISPLAY_P3 (9)` 持续发黄。

---

## 方案设计与更新（v2.4.2 + GallerySrgbFix v1.0.0）

### 一、PhotoHDRFix v2.4.2（LSPosed 模块）

#### 1. Google 相册：基于 `SurfaceView` 维度与绑定状态的精确分流

使用 `WeakHashMap<Surface, WeakReference<SurfaceView>>` 跟踪解码输出 `Surface` 与视图尺寸，并结合通用 **HEVC SPS NAL（type 33）VUI 位解析器**：

| 场景 | 判定逻辑 | 处理 |
| --- | --- | --- |
| 信息流缩略图预览 | 绑定已知小尺寸 `SurfaceView`（如缩略图列表单元） | 压成 SDR：`color-standard=1` (BT.709)、`color-transfer=3` (SDR)，并将 HEVC `csd-0` SPS VUI `(9, 18, 9)` 改写为 `(1, 1, 1)`，阻断缩略图触发 `DISPLAY_P3` 发黄 |
| 全屏播放（含 ExoPlayer `surfArg == null` 延迟绑定） | 大尺寸全屏 `SurfaceView`、`surfArg == null` 或未知/尚未布局的 `Surface` | 保留真 HDR：`dolby -> hevc` 解码器重定向并保留原生 `BT2020_ITU_HLG` 输出；移除会引发 VPP 丢帧卡顿的 `color-transfer-request=3`，避免全屏播放卡顿或误压成发白 SDR |

#### 2. 微信：拦截广色域窗口切换

Hook `Window.setColorMode(int)`：在 `com.tencent.mm` 内将 `COLOR_MODE_WIDE_COLOR_GAMUT (1)` 钳制为 `COLOR_MODE_DEFAULT (0)`，避免全屏看图和选图预览时屏幕偏色。

---

### 二、GallerySrgbFix v1.0.0（Zygisk Next Native 配套模块）

源码位于仓库 [`gallery_srgb_fix/`](gallery_srgb_fix/) 目录，专为 HyperOS 4 纯原生小米相册设计：

1. **严格单应用隔离（零系统进程污染）**：
   - 使用 Zygisk Next Native API（`zn_module` + `zn_modules.txt`），仅注入 `/system_ext/bin/hyos_spawner`（**完全不注入 `zygote64`、`system_server` 及任何 Java 应用**）；
   - 通过 `pthread_atfork` + `libhyper_os_shell.so` 的 `android_dlopen_ext` GOT 门控检查进程命令行与加载路径：**仅在 `com.miui.gallery` 中安装 Hook**；遇到桌面（`com.miui.home`）等其他原生应用时立即还原 GOT 并直接放行。
2. **仅钳制 SDR P3，真 HDR 视频 100% 原样放行**：
   - 拦截 `ANativeWindow_setBuffersDataSpace`、`ASurfaceTransaction_setBufferDataSpace`、`SurfaceComposerClient::Transaction::setDataspace`、Vulkan `vkCreateSwapchainKHR` 及 `eglCreateWindowSurface`；
   - 仅将 `DISPLAY_P3`（`143261696`）/ `DISPLAY_P3_LINEAR` / `DCI_P3` 钳制为 `V0_SRGB`（`142671872`），让小米相册首页瀑布流、普通照片保持生动模式不发黄；
   - **`BT2020_ITU_HLG`（`302383104`）、`BT2020_PQ`（`298188800`）等 HDR 数据空间原样透传**，全屏播放杜比/HLG HDR 视频仍保持完整 HDR 激发效果。

---

## 功能闭环（实机验证）

- ✅ Google 相册缩略图滑动播放 HDR 视频不黄，全屏真 HDR 流畅、高光正常不偏白
- ✅ 微信全屏查看他人照片及全屏选图预览不偏色
- ✅ HyperOS 4 纯原生小米系统相册首页网格、普通看图保持生动模式不发黄，全屏 HDR 视频正常激发
- ✅ 截屏图片回看正常
- ⚠️ **截屏动画本身的瞬间偏色**为系统边界（图层由 SystemUI / `system_server` 绘制），本方案不注入 `system_server`，属已知保留边界

---

## 红线（本方案绝不触碰）

- 不碰 `system_server` / `sepolicy.rule`
- 不修改 `/system`、`/system_ext`、`/vendor`、`/odm` 或任何引导分区
- 仅在目标 App 进程内执行用户态 Hook，零卡米/变砖风险

---

## 安装与回滚

### 1. PhotoHDRFix（Google 相册 + 微信）

1. 需要已 Root（KernelSU / Magisk）+ LSPosed（Zygisk Next）环境；
2. 安装 Release 中的 `PhotoHDRFix-2.4.2.apk`，在 LSPosed 中启用；
3. 作用域勾选 **Google 相册**（`com.google.android.apps.photos`）与 **微信**（`com.tencent.mm`），如旧版系统仍使用 Java 版小米相册可同时勾选 `com.miui.gallery`；
4. 强行停止并重开对应应用生效（注意微信需彻底结束主进程）。

### 2. GallerySrgbFix（HyperOS 4 原生小米相册）

> 依赖 **Zygisk Next（>= 1.2，支持 `zn_modules.txt`）**。

1. 在 KernelSU / Magisk 管理器中刷入 Release 中的 `GallerySrgbFix-1.0.0-zn.zip`；
2. 执行一次**软重启**（如 `/data/adb/ksud soft-reboot`）生效；
3. **回滚方式**：在管理器中卸载模块（或删除 `/data/adb/modules/gallery_srgb_fix`）并软重启即可。

---

## 构建

### 构建 PhotoHDRFix APK

依赖 Android SDK（compileSdk 34 / buildTools）与 Gradle，`app/build.gradle` 中 `versionCode` / `versionName` 与 Manifest 对齐：

```bash
./build.sh
```

产物 APK 位于 `out/` 目录（已被 `.gitignore` 排除，不会入库）。

### 构建 GallerySrgbFix Native 模块

```bash
cd gallery_srgb_fix
./build.sh
```

---

## 版本历史

- **v2.4.2**（versionCode 27，附 **GallerySrgbFix v1.0.0**）—— 修复 Google 相册全屏 HDR 卡顿/误压发白问题，新增微信 `Window.setColorMode` 防偏色拦截，新增 HyperOS 4 原生小米相册 Zygisk Next Native 修复模块 `gallery_srgb_fix`
- **v2.3.0**（versionCode 23）—— 方案 A 按 Surface 类型分流落地
- **v2.0.0** —— 早期「丢帧干预」方向（已废弃）

---

## 免责声明

本模块仅供学习与研究色彩管理问题使用。刷入 Xposed / Zygisk 模块需自行承担风险，作者不对任何设备异常、数据丢失或系统损坏负责。

---

## 致谢

- LSPosed / Xposed / Zygisk Next 框架
- Operit AI、DeepSeek、Gemini 等排障协作工具
