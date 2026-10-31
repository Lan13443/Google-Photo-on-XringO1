# Google Photo on XringO1

> 模块名：**PhotoHDRFix**（应用名 / 包名 `com.wa2000.photohdr`）

面向 **玄戒 O1（Xring O1）** 机型的 Google 相册视频播放丢帧干预模块（LSPosed）。

> ⚠️ **实验性项目**。请先读完「现状与局限」再决定是否安装。

---

## 背景

部分玄戒 O1 机型（如 Xiaomi 15S Pro / HyperOS 4 / Android 17）上，Google 相册播放高码率视频（例如 4K60 杜比视界）时会出现持续卡顿、周期性抽帧，而同一个文件用其他播放器（VLC、系统播放器等）播放是流畅的。

基于 **Google 相册 7.97.0.1000520730** 的实测排查结论：

1. **解码器正常** —— `c2.xring.dolby.decoder` 工作正常，系统解码能力不缺；
2. **相册自己的渲染调度器有严格的时钟校验** —— 每一帧的渲染时间戳会被播放时钟校验，晚于播放时钟约 **170~200ms** 的帧被判定为「过期」直接丢弃（`jfo.n` 返回 `r=2` 的丢弃路径，以及异步队列 `jeu.b` 的清空路径）；
3. **温度限频是放大器** —— 播放后期 `mi_thermald` 触发功率预算，大核被限到 1.9GHz，帧迟到加剧，出现爆发式丢帧（实测 8 秒丢 1.4 万帧）；
4. **定性** —— 相册的调度参数按渲染更快的平台校准，没有针对 O1 的渲染延迟特性留容差；限频放大了这个问题。

结论：**根因在相册的调度策略与系统调频，模块只能改写「丢帧决策」，改不了「帧迟到」本身。**

---

## 功能

- 拦截相册的丢帧决策（`jfo.n`），把「丢弃」改写为按相册自身时间戳渲染；
- **SAFE-FIX 策略**：只抢救即将到期的帧（delta < 33ms），明显超前的帧保持丢弃，维持播放器自身的追帧机制，避免画面跳变；
- 渲染节奏基准 60 / 30 fps 切换；
- 详细日志开关（logcat tag：`PhotoHDRFix`）；
- Material 3 设置界面：莫奈动态取色 + 四组静态主题（经典紫 / 海岸蓝 / 苔原绿 / 玫瑰粉），支持深色模式。

---

## 现状与局限（重要）

- **实验性**。在作者设备上，干预后卡顿有所减轻，但**未完全消除**；部分场景下干预效果趋近于零（无副作用）。
- **模块不能解决根因**：帧迟到来自系统调度与温度限频，模块无法绕过 `mi_thermald` 的功率限制。
- **已验证环境**：
  - Xiaomi 15S Pro（dijum / Xring O1）
  - HyperOS 4 / Android 17
  - LSPosed 2.2.1 + KernelSU
  - Google 相册 7.97.0.1000520730
- 其他 O1 机型（平板等）**未验证**；非 O1 设备可安装但意义不大。
- 相册版本更新后内部类名可能变化，hook 会失效（**不会导致崩溃，只是不生效**），需要适配。

---

## 安装

**前置要求**：root（KernelSU / Magisk）+ LSPosed（Zygisk），Android 12+（minSdk 29）。

1. 从 [Releases](../../releases) 下载 APK 并安装；
2. 打开 LSPosed → 模块 → 启用 **PhotoHDRFix**；
3. 作用域勾选 **Google 相册**（`com.google.android.apps.photos`）；
4. 强制停止相册（或重启手机），再打开相册播放视频；
5. 打开 PhotoHDRFix 应用查看注入状态。

> 模块应用本身只是设置界面；真正的 hook 运行在 Google 相册进程内。

---

## 设置说明

| 项目 | 说明 |
|---|---|
| 修复干预 | 总开关，关闭后行为等同未安装模块 |
| 渲染节奏 | 帧间隔基准，默认 60 fps |
| 详细日志 | 开启后向 logcat 输出决策日志，排查用 |
| 配色主题 | 莫奈动态（跟随壁纸）/ 经典紫 / 海岸蓝 / 苔原绿 / 玫瑰粉 |

---

## 从源码构建

Gradle 工程：

```bash
./gradlew assembleRelease
```

- 需要 JDK 17 + Android SDK 34；
- Xposed API 通过本地 `:stubs` module 以 `compileOnly` 方式提供（仅编译期使用，运行时由 LSPosed 提供真实实现）；也可以替换为 `de.robv.android.xposed:api:82`（仓库 `https://api.xposed.info/`）；
- 签名：`app/build.gradle` 默认回落到 debug 签名，正式发布请配置你自己的 keystore，**不要提交签名文件**（`.gitignore` 已排除 `*.jks` / `*.keystore`）。

---

## 免责声明

- 本项目会修改第三方应用（Google 相册）的运行行为，可能违反其服务条款，**仅供学习研究使用**；
- root、刷机、Xposed/LSPosed 模块相关风险由使用者自行承担；
- 本项目与 Google、小米、LSPosed 官方无关；
- 仓库**不包含**任何 Google 相册的代码或资源，仅通过运行期 hook 引用其公开可见的类名 / 方法名。

---

## License

[MIT](LICENSE)

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed)
- Material Design 3 设计规范