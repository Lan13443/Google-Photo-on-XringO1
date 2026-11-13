# PhotoHDRFix

解决玄戒 O1 平台（dijum / O1_asic）MIUI·澎湃相册在「缩略图滑动、截屏动画、屏幕录制」等场景瞬间偏黄的问题。

Xposed / LSPosed 模块，作用于 `com.google.android.apps.photos` 与 `com.miui.gallery`。

---

## 背景：为什么会偏黄

用户相机拍摄的「杜比视界」视频，底层编码实际就是 HLG：

- Profile 8.4，`transfer=arib-std-b67`（HLG），`color primaries=BT.2020`
- 容器 brands 含 `dby1`，本质是「杜比视界」壳包着 HLG

玄戒 O1 平台在 HDR 图层合成阶段存在色彩管理缺陷：

1. SurfaceFlinger 把显示色域从 `SRGB(7)` 切到 `DISPLAY_P3(9)`；
2. 但所有图层的 `colorTransform` 仍为单位矩阵，HLG 的 OOTF 未被正确处理；
3. 结果 BT.2020 的数值被直接当作 P3 显示，中性色偏黄。

因为偏色固化在**合成阶段**，所以连截图、录屏同样发黄——这不是硬件损坏，也不是用户设置，而是平台级色彩缺陷。

触发条件实测收窄：正常单张照片/视频点开全屏查看**不黄**；只有系统相册缩略图滑动、截屏动画、屏幕录制三种场景发黄——即「屏上出现 HDR 图层的那一刻」才偏色。

---

## 方案：按 Surface 类型分流（方案 A）

在 MediaCodec 层按 Surface 类型做分流，而不是去 hook 帧渲染节奏（早期版本做过，方向错了）：

| 场景 | 判定 | 处理 |
| --- | --- | --- |
| 全屏播放 | Surface 子类（如 `jfd`） | 保留真 HDR：重写 mime 为 hevc，注入 `color-standard=6`(BT.2020) / `color-transfer=7`(HLG)，**不碰 csd-0** |
| 缩略图 / 预览 | 原生 `android.view.Surface` | 压成 SDR：重写 hevc，注入 `color-standard=1`(BT.709) / `color-transfer=3`(SDR)，并 patch csd-0 到 SDR |

另将 `createByCodecName` / `createDecoderByType` 的 `dolby` 解码器重定向到 `hevc`。

> 说明：全屏保留 HDR 是为了不丢失高光与色彩层次；缩略图压 SDR 是因为小图本就不需要 HDR，且压 SDR 能绕开合成阶段的偏色。

---

## 功能闭环（用户实测）

- ✅ 相册全屏 HDR 保真（不偏亮、保留高光）
- ✅ 信息流缩略图不黄
- ✅ 截屏图片数据回看时不黄
- ⚠️ **截屏动画本身的瞬间偏色**为系统边界（图层由 SystemUI / system_server 截图控制器绘制），本模块不注入 system_server，故**保留不修**，属已知边界

---

## 红线（本模块绝不触碰）

- 不碰 `system_server` / `zygisk_lsposed` / `sepolicy.rule`
- 不碰 `/vendor`、系统分区、系统引导模块
- 只在普通 App 进程内排查与 hook，零黑屏/零引导风险

---

## 安装

1. 需要已 root（KernelSU / Magisk）+ LSPosed（Zygisk）环境；
2. 安装本模块 APK 后在 LSPosed 中启用；
3. 作用域勾选 **Google 相册**（`com.google.android.apps.photos`）与 **MIUI 相册**（`com.miui.gallery`）；
4. 重启目标应用（或重启系统）生效。

---

## 构建

依赖 Android SDK（compileSdk 34 / buildTools）与 gradle，`app/build.gradle` 中 versionCode / versionName 与 Manifest 对齐。

```bash
# 在工程根目录执行
./build.sh
```

产物 APK 位于 `out/` 目录（已被 `.gitignore` 排除，不会入库）。

---

## 版本

- **v2.3.0**（versionCode 23）—— 当前稳定版，方案 A 分流落地
- v2.0.0 —— 早期「丢帧干预」方向（已废弃）

---

## 免责声明

本模块仅供学习与研究色彩管理问题使用。刷入 Xposed 模块需自行承担风险，作者不对任何设备异常、数据丢失或系统损坏负责。

---

## 致谢

- LSPosed / Xposed 框架
- Operit AI、DeepSeek 等排障协作工具
