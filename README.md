<div align="center">

# 题屿 · TiYu

**题屿 TiYu**（原「无人机装调题库 DroneQuiz」，v2.8.8 起更名）— Android 原生刷题 / 模考 / 错题本应用 · Jetpack Compose · 液态玻璃 UI

[![Release](https://img.shields.io/github/v/release/Everett406/TiYu?style=flat-square)](https://github.com/Everett406/TiYu/releases)
[![Build APK](https://github.com/Everett406/TiYu/actions/workflows/build.yml/badge.svg)](https://github.com/Everett406/TiYu/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/Everett406/TiYu?style=flat-square)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%2012%2B-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-7F52FF?style=flat-square)

内置《无人机装调题库（含解析）》**800 题**（单选 640 / 判断 160），
支持多题库管理与自定义题库导入（CSV / ZIP，含图片题，五种题型）、模考自动阅卷、错题强化训练与每日学习提醒。

[下载最新 APK](https://github.com/Everett406/TiYu/releases/latest) · [更新日志](CHANGELOG.md) · [问题反馈](https://github.com/Everett406/TiYu/issues)

</div>

---

## 目录

- [项目简介](#项目简介)
- [功能特性](#功能特性)
- [安装与使用](#安装与使用)
- [技术栈](#技术栈)
- [应用架构](#应用架构)
  - [源码结构](#源码结构)
  - [液态玻璃实现（交接重点）](#液态玻璃实现交接重点)
  - [刷题会话双槽机制（v2.7.4 交接重点）](#刷题会话双槽机制v274-交接重点)
  - [打赏弹窗机制（v2.7.4）](#打赏弹窗机制v274)
  - [模考成绩重建与删除限额（v2.7.4）](#模考成绩重建与删除限额v274)
  - [长列表性能规约](#长列表性能规约)
  - [数据层](#数据层)
- [构建指南](#构建指南)
- [CI/CD 与发版流程](#cicd-与发版流程)
- [签名体系](#签名体系)
- [题库维护](#题库维护)
- [诊断与排障](#诊断与排障)
- [已知限制与注意事项](#已知限制与注意事项)
- [工具脚本](#工具脚本)
- [版本历史](#版本历史)
- [给接手人的建议](#给接手人的建议)
- [致谢](#致谢)
- [许可证](#许可证)

---

## 项目简介

**题屿**是一款面向**无人机装调员职业技能培训**的离线刷题应用（仓库 v2.8.8 起更名为 TiYu——题屿拼音，GitHub 仓库名不支持纯中文；包名沿用历史 `com.drone.quiz`，桌面名称与关于页自 v2.7.3 起为「题屿」）。应用内置 800 道含解析的正式题库，覆盖单选与判断两类题型，提供刷题练习、全真模考、错题本、题目搜索、学习打卡与每日提醒等完整学习闭环，同时支持通过设置页导入自定义题库（CSV / 带图片的 ZIP）以适配其他科目。

项目采用纯 Kotlin + Jetpack Compose 构建，UI 层基于 Kyant0 的 **AndroidLiquidGlass (backdrop)** 库实现了 Android 平台少见的「液态玻璃」视觉效果（折射、色散、振动度），并为此建立了一整套**运行时守护与自动降级机制**（BootGuard / CrashGuard / 安全模式），保证在低端 GPU 或异常渲染环境下应用始终可用。全部构建、签名与发版流程由 GitHub Actions 自动化完成，本地无需 Android Studio 亦可参与开发。

> 应用题库、做题、模考等全部核心功能均离线运行，不收集任何用户数据；唯一联网行为是手动点「检查更新」（访问 GitHub Releases API），以及跳转浏览器打开发布页。

---

## 功能特性

### 学习功能（六大页面）

| 页面 | 功能要点 |
|------|----------|
| **首页** | 时间问候 + 昵称、学习进度环、预估通过率条、近 7 日刷题量柱状图 / 正确率折线（Canvas 自绘）、连续打卡双卡、错题提示、上次模考成绩速览；全局壁纸之上铺主题纱（wallScrim）保证可读性 |
| **刷题** | **配置入口页**（`PracticeConfig.kt`：题型多选 / 筛选 chips、顺序 / 随机双模式、「将接续上次进度 · 第 N/M 题」实时提示、重新开始胶囊按钮）→ 全屏刷题页（`HorizontalPager` 左右滑题、点选即判、答错抖动 + 解析弹簧展开、题号面板单一数据源、自动切题、**题目图片**小图点开大图）；**顺序与随机两套进度槽独立记忆，且按题库隔离**（v2.7.4 双槽 + v2.8.8 读口校验，见下文）；**顺序模式循环补漏**（v2.8.6：刷到末题自动开新一轮，只挑没刷过的题）；**防沉迷**（v2.8.6：连续刷题 20 分钟弹窗提醒看远处，考试不受影响）；重新开始按钮带二次确认 |
| **模考** | 题数、时长、及格分（合格线）可拖滑杆配置；**题型构成**在高级选项内（自动按库占比配比 / 手动比例滑杆联动 / 拖拽排序，v2.8.3–2.8.4）；倒计时（< 60s 红色脉冲）、到时自动交卷、交卷确认、玻璃答题卡面板（按题型分段 + 点号跳题）、自动阅卷；**已完成历史记录可单击回看成绩页**（数据库按 examId 重建，v2.7.4）；进行中记录可继续 / 放弃 / 删除 |
| **模考成绩页** | 得分 / 用时 / 正误统计、**合格线回显**（开考时设定的分数定格存档，v2.8.6）、错题解析懒加载列表（`LazyColumn` 滚动流畅，v2.7.4 优化）、右上角浅色「三点」删除本次记录（**每周限删 2 次**，ISO 周自动重置，配额弹窗告知） |
| **错题本** | 答错自动收录、「连续答对 N 次」三档移除策略、错题特训入口（不占用刷题进度槽）、分类筛选胶囊、展开解析、像素级连续映射滚动把手、条目删除（30dp 触达区 + 缩放淡出 / 其余条目弹性靠拢动画，v2.8.7） |
| **搜索** | 题干 / 选项 / 解析全文检索、搜索历史（最多 8 条）、结果解析弹簧展开动画 |
| **设置** | 主题三档、字号四档、**阅读字体四款**（手机自带 / 思源黑体 / 思源宋体 / 霞鹜文楷，内置子集化字体文件）、刷题顺序、自动切题、及格分滑杆、错题移除阈值、每日提醒（WorkManager，刷过当天不打扰）、**防沉迷**（连续刷题 20 分钟提醒，考试不受影响）、壁纸（内置 4 张 + 自定义 + 可选模糊）、昵称、底栏玻璃模糊三档、**题库管理**（多题库切换 / 导入 CSV·ZIP / 重命名 / 删除 / 清空恢复，带图题目支持）、清空学习记录、画面特效开关、**使用引导**（题库管理与关于之间，随时重走导览）、**关于**（底部弹窗：应用介绍 / 检查更新 / 请喝奶茶） |

### 交互与视觉

- **液态玻璃 UI**：底部导航胶囊、按钮、滑杆、开关、卡片均基于 backdrop 折射渲染；弹窗体系（`GlassOverlays.kt`）提供 `GlassBottomSheet` / `GlassConfirmDialog` / `GlassInputDialog` / Portal 槽位机制，弹窗展开时页面内容层联动模糊（OverlayBlur）；所有玻璃组件带纯 Compose 降级分支
- **细节打磨**：分区标题 / 页脚小字按壁纸明暗自动切换可读色（v2.8.7）；错题本删除带缩放淡出 + 弹性靠拢动画（v2.8.7）；关于页为文楷文档式底部弹窗（v2.8.9）
- **动画系统**：页面转场统一（淡入 + 轻缩放）、iOS 式过冲回弹（BounceState）、按压缩放、搜索框 Hero 动画、倒计时脉冲
- **壁纸系统**：内置 4 张主题壁纸（黄昏原野 / 深林 / 浅林 / 天空），支持自定义图片与模糊化处理
- **配色**：奶油底 + 墨黑主操作 + 橙色点缀（浅色）；暖夜深色主题
- **稳定性**：全局崩溃捕获、启动心跳守护、自动安全模式、崩溃报告屏内一键复制

### 支持作者（打赏，v2.7.4 新增）

- **手动入口**：设置 → 关于 → 「☕ 请喝奶茶」（v2.8.9 起在关于底部弹窗内，随时打开、不影响自动触达；v2.8.10 起点击后关于弹窗先收起、打赏弹窗随后单独弹出）；
- 累计刷题满 **2 小时**后，温和弹一次打赏弹窗（仅一次）；
- 点「看看收款码」展示打赏码（白底大图），可一键**保存到相册** `Pictures/题屿`（MediaStore，API 31+ 无需存储权限）；
- 点「以后别提醒我」则**永久不再弹**；弹窗外关闭视为已弹过；
- **考试中绝不打扰**：正在模考时达标，会等考试结束回到普通页面后再出现。

---
## 安装与使用

### 系统要求

| 项目 | 要求 |
|------|------|
| 系统版本 | Android 12（API 31）及以上 |
| 设备 | 手机（竖屏应用），armeabi-v7a / arm64-v8a / x86_64 / x86 |
| 网络 | 无需 |

### 安装步骤

1. 从 [Releases](https://github.com/Everett406/TiYu/releases/latest) 下载最新 `TiYu-x.y.z.apk`；
2. 直接安装。**v2.0.2 起所有版本使用同一固定签名，之后升级均可直接覆盖安装，无需卸载旧版**；
   - 若从 v2.0.x 旧签名版本升级，需先卸载一次旧版（签名不同导致）；
3. 首次启动若提示未知来源，按系统引导授权即可。

### 使用提示

- **题库升级**：升级到内置题库版本更新的 APK 时，应用会自动重置学习数据（题目 id 变化，旧记录无法映射），属预期行为，详见[题库版本机制](#题库版本机制)；
- **自定义题库**：设置 → 题库管理 → 导入题库，纯文字题导 CSV，带图题打包 ZIP（题目 CSV + images 图片文件夹）；可将「提示词」连同 Excel / Word / PDF 材料交给 Agent 整理成可导入格式；
- **检查更新**：设置 → 关于 → 「检查更新」，应用比对 GitHub Releases 最新版，有新版时引导浏览器打开发布页手动下载 APK（不做应用内下载安装）；
- **刷题进度**：顺序 / 随机两种模式的进度**各自独立记忆**，切回任一模式都接着该模式上次的位置；进度**按题库隔离保存**（v2.8.8），切库互不干扰、切回原库进度仍在；「重新开始」会清空**当前模式**的进度，且需二次确认；
- **画面特效**：低端设备若出现卡顿或渲染异常，可在 设置 → 画面特效 关闭液态玻璃（应用也会在检测到异常退出后自动降级）；
- **打赏弹窗**：累计刷题满 2 小时会弹一次，不喜欢点「以后别提醒我」即可永久关闭。

---

## 技术栈

| 类别 | 组件 | 版本 |
|------|------|------|
| 语言 | Kotlin（启用 `-Xcontext-parameters`） | 2.2.20 |
| UI | Jetpack Compose（BOM）+ Material 3 | 2025.06.01 |
| 导航 | androidx.navigation:navigation-compose | 2.9.8 |
| 数据库 | Room + KSP | 2.8.4 |
| 偏好存储 | DataStore Preferences | 1.1.7 |
| 后台任务 | WorkManager | 2.10.5 |
| 序列化 | kotlinx-serialization-json | 1.9.0 |
| 协程 | kotlinx-coroutines-android | 1.10.2 |
| 构建 | AGP / Gradle | 8.13.2 / 8.14.3 |
| 工具链 | JDK（编译目标 21）、KSP | 21 / 2.2.20-2.0.4 |
| SDK | compileSdk / targetSdk / minSdk | 36 / 35 / 31 |

液态玻璃核心为 **vendor 进仓库的 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) backdrop 库源码**（`com.kyant.backdrop`），未以 Gradle 依赖形式引入（官方连续曲率形状 `com.kyant.shapes` 同样源码 vendor，因其 AAR 要求 compileSdk 37 + AGP 9.1），原因与改动见[液态玻璃实现](#液态玻璃实现交接重点)。

---

## 应用架构

### 源码结构

```
TiYu/
├── app/src/main/
│   ├── AndroidManifest.xml              # 权限：POST_NOTIFICATIONS、VIBRATE、INTERNET（仅检查更新）；竖屏锁定
│   ├── assets/questions.json            # 内置题库（800 题，version=2）
│   ├── res/
│   │   ├── font/                        # 内置阅读字体（子集化）：Noto Sans SC、Noto Serif SC、霞鹜文楷 各 regular/medium(bold)
│   │   ├── raw/support_qr.png           # 打赏收款码（打赏弹窗展示 / 存相册用）
│   │   └── drawable-nodpi/              # 内置壁纸 wp_dusk / wp_forest_deep / wp_forest_light / wp_sky
│   └── java/
│       ├── com/drone/quiz/
│       │   ├── MainActivity.kt          # 入口 Activity：启动画面、SafeModeBanner、诊断屏路由、刷题时长 60s 心跳累计（UsageSignals 门控）
│       │   ├── QuizApp.kt               # Application：CrashGuard 安装、WorkManager 调度
│       │   ├── BootGuard.kt             # 启动守护：心跳 / 面包屑 / 异常死亡计数 / 安全模式
│       │   ├── CrashGuard.kt            # 全局未捕获异常 → last_crash.txt → 启动报告屏
│       │   ├── data/
│       │   │   ├── db/                  # Room：AppDatabase / Entities（七表）/ Daos
│       │   │   ├── repo/Repo.kt         # 数据仓库：题库装载、版本比对、统计聚合、模考成绩按 examId 重建
│       │   │   └── settings/SettingsStore.kt  # DataStore：设置项 + 刷题会话双槽 + 打赏状态 + 删除周限额
│       │   ├── screens/
│       │   │   ├── HomeScreen.kt        # 首页（问候 / 进度环 / 可视化图表 / 打卡）
│       │   │   ├── PracticeConfig.kt    # 刷题配置入口页（筛选 / 顺序随机 / 接续提示 / 重新开始）
│       │   │   ├── PracticeScreen.kt    # 全屏刷题（Pager / 题号面板 / 点选即判 / 双槽进度落盘）
│       │   │   ├── ExamScreens.kt       # 模考配置 + 考试 + 成绩单（历史回看 / 三点删除 + 周限额）
│       │   │   ├── SearchScreen.kt      # 题目搜索（全文检索 / 历史 / 解析展开）
│       │   │   ├── WrongBookScreen.kt   # 错题本 + 特训（筛选 / 滚动把手）
│       │   │   ├── SettingsScreen.kt    # 设置（字体 / 壁纸 / 提醒 / 题库管理 / 关于弹窗 AboutSheet）
│       │   │   ├── BankImportSheet.kt   # 导入题库底部弹窗（CSV / ZIP 解析 / 模板 / Agent 提示词 / 导入报告）
│       │   │   └── common/Common.kt     # 公共组件（进度环、滑杆、确认对话框等）
│       │   ├── ui/
│       │   │   ├── glass/               # 液态玻璃组件族
│       │   │   │   ├── GlassKit.kt      #   GlassRuntime / glass() / GlassCard / GlassButton / GlassSlider / GlassToggle
│       │   │   │   ├── GlassBottomBar.kt#   底部导航（真折射玻璃胶囊，官方 LiquidBottomTabs 移植）
│       │   │   │   ├── GlassOverlays.kt #   GlassBottomSheet / GlassConfirmDialog / GlassInputDialog / Portal 槽位 / OverlayBlur 全局状态
│       │   │   │   ├── Bounce.kt        #   BounceState（iOS 式过冲回弹）/ rememberPressScale
│       │   │   │   └── AppIcons.kt      #   自绘描边矢量图标（底栏 5 图标 + 通用 glyphs）
│       │   │   ├── nav/AppRoot.kt       # NavHost：路由 + 统一转场 + glassMaterial() 布局 + 打赏弹窗宿主（考试路由判断）
│       │   │   ├── onboarding/Onboarding.kt  # 首启功能引导（高亮气泡导览：锚点埋点/挖孔遮罩/玻璃气泡/Hero 式步进转场，v2.9.0/2.9.1）
│       │   │   └── theme/Theme.kt       # 三档主题 / 四档字号 / 阅读字体四款 / 暖夜深色配色
│       │   ├── util/GallerySave.kt      # MediaStore 存相册工具（打赏码保存到 Pictures/题屿，API 31+ 免权限）
│       │   ├── util/GalleryShare.kt     # 系统分享面板（CSV 模板分享，FileProvider，v2.8.2）
│       │   ├── util/AppUpdater.kt       # 检查更新（GitHub Releases API 比对版本 + 浏览器打开发布页，v2.8.8）
│       │   └── work/Notify.kt           # 每日学习提醒（WorkManager，刷过当天不打扰）
│       ├── com/kyant/backdrop/          # ★ vendored Kyant0 backdrop 库（Apache-2.0，勿随意重构）
│       │   ├── Backdrop.kt / LayerBackdrop.kt / DrawBackdropModifier.kt
│       │   ├── effects/（blur / lens / RenderEffect / ColorFilter）
│       │   ├── highlight/ shadow/ internal/（AGSL 着色器、图层记录）
│       │   └── catalog/utils/（DampedDragAnimation / InteractiveHighlight 等官方示例工具类）
│       └── com/kyant/shapes/            # ★ vendored 官方连续曲率形状（Capsule/RoundedRectangle，同因 compileSdk 不升 maven 依赖）
├── .github/workflows/build.yml          # CI：构建 → 签名 → Artifact → 自动发 Release
├── scripts/                             # 工具脚本（Python，见「工具脚本」节）
├── CHANGELOG.md / NOTICE / LICENSE / FONT_LICENSES(assets)
├── build.gradle.kts                     # 插件版本集中声明
└── settings.gradle.kts
```

### 液态玻璃实现（交接重点）

这一节是本项目**最容易踩坑的核心知识**，接手前请务必完整阅读。

#### 组件分层

| 层 | 内容 | 说明 |
|----|------|------|
| 库层 | `com.kyant.backdrop.*`、`com.kyant.shapes.*`（vendored） | 官方库源码并入仓库：合并 `expect/actual` 为 Android 单源集、`lens()` 解除对外部 shapes 库的依赖、剥离 org.intellij 注解；shapes 因 maven AAR 要求 compileSdk 37 + AGP 9.1 而改源码内联 |
| 组件层 | `GlassKit.kt` / `GlassBottomBar.kt` / `GlassOverlays.kt` | `glass()` 修饰符（vibrancy + blur + AGSL lens 折射/色散）、`GlassCard` / `GlassButton` / `GlassSlider` / `GlassToggle`（官方 DampedDrag 移植，支持点选 + 拖动 + 步进吸附）、`GlassBottomTabs`（底层玻璃胶囊 + 隐藏染色副本 + 选中块折射染色图标，支持拖动切换）、`GlassBottomSheet` / `GlassConfirmDialog` / Portal 槽位弹窗体系（AppRoot 统一 `PortalHost` 承载，弹窗展开联动内容层 OverlayBlur） |
| 材质层 | `Modifier.glassMaterial()` | iOS regular material 观感（半透明渐变表面 + 顶部高光描边 + 轻阴影），**无采样循环风险**，用于页面内容流 |

#### ⚠️ 硬约束：记录层内禁止 drawBackdrop 采样（v2.1.0 血泪教训）

官方 issue [#54](https://github.com/Kyant0/AndroidLiquidGlass/issues/54)：**`layerBackdrop` 记录层内部如果存在采样同一 backdrop 的 `drawBackdrop` 节点（即"内容绘制自身"），会构成循环引用，在 RenderThread 触发 `SIGSEGV` native 崩溃**。该崩溃 Java 层 catch 不到，表现为首次启动即闪退。

- v2.0 的错误做法：`AppRoot` 把 `NavHost` 内容层整体 `layerBackdrop`，页面内所有玻璃组件都在记录层内采样 → 首屏必崩；
- v2.1.0 的正解：**页面内容流一律使用 `glassMaterial()`（无采样）**；真折射（`refracts = true`）只允许用于记录层**之外**的浮动元素（当前仅底部导航胶囊）；
- 玻璃上叠玻璃需走官方 `drawBackdrop(exportedBackdrop = ...)` 导出方案，不要直接嵌套采样；
- 修改玻璃相关代码后，**必须在真机（API 31+）冷启动首帧验证**，模拟器绿不代表真机不崩。

#### 运行时守护与自动降级

由于 AGSL 着色器行为依赖 GPU 驱动，项目建立了三层防御：

```
启动 → BootGuard 心跳（首帧稳定 700ms 后写 boot_state.txt 标记健康）
         │
         ├─ Java 层崩溃 → CrashGuard 捕获 → last_crash.txt → 下次启动显示「启动报告」屏
         ├─ AGSL 着色器构造失败 → runCatching 降级为纯模糊 + 普通描边（不崩溃）
         └─ 上次未标记健康即死（native 崩溃 / 被杀）
              ├─ 1 次 → 自动安全模式：GlassRuntime.enabled = false，全部玻璃组件退化为纯 Compose 渲染
              └─ 连续 2 次 → 启动时弹出诊断屏（设备信息 + 版本 + 面包屑，一键复制）
```

- `GlassRuntime.enabled` 为全局总开关，另有 DataStore 持久化用户开关 `glass_effects`（设置 → 画面特效）；
- 安全模式下用户可手动重开特效验证是否为特效所致；
- 兼容性基线：`RenderEffect` 需 API 31+，`RuntimeShader`（AGSL lens 折射）需 API 33+，低版本自动走降级分支。

### 刷题会话双槽机制（v2.7.4 交接重点）

刷题进度是本项目**返工次数最多的模块**（v2.7.2 竞态、v2.7.3 三重加固、v2.7.4 双槽化），现状如下：

- **数据结构**：`PracticeSession`（kotlinx-serialization JSON 存 DataStore）：`src`（all/wrong）、`type`、`cat`、`ids`（题目顺序快照）、`answers`（qid→选项）、`index`（0-based 进度）、`savedAt`；
- **双槽**：`practice_session`（**顺序模式**槽，沿用旧 key，老版本数据无损迁移）+ `practice_session_random`（**随机模式**槽）。API 一律带 `order` 参数（0/1）：`practiceSession(order)` / `currentPracticeSession(order)` / `setPracticeSession(s, order)`；
- **落盘规约**：
  1. `persistSession(...)` 必须传入所属模式的 `order`，开头 `src == "wrong"` 直接短路——**错题特训永不写会话快照**（v2.7.3 教训：单槽时代特训一开就把主刷题进度冲掉）；
  2. 只在**真实用户行为**（翻页 / 作答）时落盘；会话恢复窗口期（`restoring`）抑制一切"顺手落盘"（v2.7.2 教训：恢复流程尾部落盘把 index 覆写回 0）；
  3. 随机模式列表来自 `ids.shuffled()` 后**按输入 ids 保序重排**——Room `IN (:ids)` 固定按主键升序返回，必须 `associateBy` 后重排，否则洗牌被吃掉（v2.7.3 教训）；
  4. 刷题页读设置（如 `practiceOrder`）必须在协程内 `settings.first()` 挂起读真值——**Compose `collectAsState` 首帧快照是默认值**（v2.7.3 教训）；
- 会话全部刷完不接续（随机模式自然重洗新一轮）。

### 打赏弹窗机制（v2.7.4）

- **计时**：`MainActivity` 前台期间每 60 秒心跳一次 `addUsageMs(60_000)`（`onStop` 取消协程），且仅在刷题答题页前台时才计入（`UsageSignals.onPracticeScreen` 门控，v2.9.2 起口径与文案对齐：只统计真实刷题时长），累计值存 DataStore `usage_ms`（粒度 1 分钟）；
- **触发**：`AppRoot` 观察设置流，条件全部满足才弹：`usage_ms ≥ 2h` && `!support_prompted` && `!support_refused` && **当前路由非模考进行页**。考试中达标 → 考完回到普通页面后自动出现（条件含路由，路由变化即重评）；
- **两态 UI**（`GlassBottomSheet`）：询问态（文案 + 按钮）→ 收款码态（`res/raw/support_qr.png` 白底大图 + 保存按钮）；
- **保存**：`util/GallerySave.kt` 走 MediaStore.Images，`RELATIVE_PATH = Pictures/题屿`，`IS_PENDING` 两段式写入，API 31+ 应用自有媒体**无需任何存储权限**；
- **语义**：弹窗外部关闭 / 保存完成 = `setSupportPrompted()`（只弹一次）；「以后别提醒我」= `setSupportRefused()`（同时置 prompted，永久静默）。

### 模考成绩重建与删除限额（v2.7.4）

- **成绩重建**：模考每题作答本来就落库 `exam_answers`，因此**任意历史记录都能精确还原**：考试页点击已完成记录 → `onOpenResult(examId)` 导航成绩页 → `Repo.loadExamOutcome(examId)` 从 `exam_records + exam_answers` 重建 `ExamOutcome`（刚交卷的内存 `SessionHolder` 快照优先，DB 兜底）；
- **删除限额**：成绩页右上角浅色「三点」按钮 → `SettingsStore.examDeleteQuota()` 查本周剩余额度（ISO 周键 `2026-W36` + 计数，跨周自动重置，**每周 2 次**）→ 剩余 0 弹「已用完，下周一恢复」；有额度弹确认框（实时显示剩余次数）→ `abandonExam(examId)` 事务删除成绩+作答 + `recordExamDeletion()` 计数 +1 → `popBackStack`。

### 长列表性能规约

v2.7.4 教训固化：**错题解析展开后 20+ 条全量组合在外层滚动 Column 里、外加玻璃卡逐帧采样 → 每帧测量绘制量巨大，长列表滑动卡顿**。规约：

- 任何可能很长的列表一律 `LazyColumn`（`items(key = 稳定id)`），禁止放 `Column(verticalScroll)` 全量组合；
- 列表条目内**不要用真折射玻璃**（采样成本随条目数翻倍），用普通 surface / glassMaterial；
- 展开/收起避免对整列表 `animateContentSize`（逐帧驱动全列表重测量）。

### 数据层

#### Room 数据库（drone_quiz_v2.db，八表，version = 4）

| 表名 | 用途 | 关键点 |
|------|------|--------|
| `banks` | 题库元数据（多题库，v2.8.0） | 名称 / 来源 / 播种版本；当前题库 id 存 DataStore |
| `questions` | 题库 | 索引 category / type；id 由 CSV 题号或题干哈希生成；v2.8.5 起带 `images` 列（图片题，文件名按 \| 分隔） |
| `practice_records` | 刷题记录 | 索引 qid / ts |
| `question_stats` | 题目维度统计 | 正确次数 / 作答次数等 |
| `exam_records` | 模考记录 | 配置、得分、用时、合格线 `pass_line`（v2.8.6，开考定格；成绩页重建来源之一） |
| `exam_answers` | 模考答题明细 | 索引 examId / qid（**成绩按 examId 重建的数据基础**） |
| `wrongbook` | 错题本 | qid 唯一索引；`addedAt` **非空默认 0**（v2.0 交卷崩溃修复点，所有插入路径必须显式赋值 `System.currentTimeMillis()`） |
| `streak_log` | 连续打卡 | 按日记录 |

- 迁移策略（v2.8.1 起为**显式重建式 Migration**，`MIGRATION_1_2 / 2_3 / 3_4` 已注册）：模式为「建新表 → 拷数据 → 删旧表 → 改名 → 补齐全部索引」，逐列逐索引与实体校验一致；**改表必须新增 Migration 并递增 DB version**，带默认值的新列须 `@ColumnInfo(defaultValue = ...)` 与 SQL `DEFAULT` 对齐（v2.8.6 教训）；`fallbackToDestructiveMigration()` 仅作漏写迁移时的兜底，**绝不能再依赖它发版**；
- 模考交卷曾因 `wrongbook.addedAt` 非空约束触发 `SQLiteConstraintException`，此约束已被修复并固化为编码规约。

#### DataStore（settings，键全表）

| 键 | 类型 | 含义 | 默认值 |
|----|------|------|--------|
| `theme` | int | 0 跟随系统 / 1 浅色 / 2 深色 | 0 |
| `font_level` | int | 字号四档（0.85 / 1.0 / 1.15 / 1.3） | 1 |
| `auto_next` | bool | 答题后自动切题 | true |
| `pass_score` | int | 及格分（50–95，步进 5） | 60 |
| `remove_threshold` | int | 错题「连续答对 N 次」移除 | 2 |
| `daily_notify` | bool | 每日提醒 | false |
| `practice_order` | int | 0 顺序 / 1 随机 | 0 |
| `glass_effects` | bool | 画面特效（液态玻璃）开关 | true |
| `bank_version` | int | 已加载题库版本（与 assets 比对） | 0 |
| `glass_blur_level` | int | 底栏玻璃模糊三档（0/1/2） | 1 |
| `wallpaper_path` | string | 自定义壁纸文件路径（空 = 内置） | "" |
| `wallpaper_blur` | bool | 壁纸模糊化 | false |
| `nickname` | string | 昵称（≤5 字） | "" |
| `reading_font` | string | 阅读字体 system/sans/serif/kai | "system" |
| `search_history` | string(JSON) | 搜索历史数组（≤8 条，最新在前） | [] |
| `usage_ms` | long | 累计刷题毫秒（打赏门槛，仅答题页前台心跳累计） | 0 |
| `support_prompted` | bool | 打赏弹窗已弹过 | false |
| `support_refused` | bool | 打赏永久拒绝 | false |
| `practice_session` | string(JSON) | **顺序模式**刷题会话快照（旧 key，历史数据无损迁移） | — |
| `practice_session_random` | string(JSON) | **随机模式**刷题会话快照 | — |
| `exam_del_week` | string | 模考删除限额 ISO 周键（如 2026-W36） | — |
| `exam_del_count` | int | 本周已删模考记录次数 | 0 |
| `current_bank` | string | 当前使用题库 id（v2.8.0 多题库） | 内置题库 |
| `deleted_banks` | string(JSON) | 被删除内置题库的墓碑数组（清空记录后重新播种） | [] |
| `exam_include_short` | bool | 模考高级选项：含简答题（v2.8.0） | false |
| `exam_type_order` | string(JSON) | 模考题型顺序（高级选项拖拽；空 = 单选→多选→填空→判断→简答） | [] |
| `exam_auto_mix` | bool | 模考题型构成自动配比（关 = 手动拖比例，v2.8.3） | true |
| `eye_care_reminder` | bool | 防沉迷：连续刷题 20 分钟提醒（v2.8.6） | false |
| `onboarding_done` | bool | 首启功能引导已完成/已跳过（v2.9.0，跳过即不再自动弹） | false |

#### 阅读字体（res/font）

内置四款阅读字体均为**子集化**产物（GB2312 常用字 + 仓库全字符集约 7900 字符，fontTools 处理，6 个 ttf 共约 17MB）：思源黑体 / 思源宋体（Noto Sans / Serif SC，OFL）、霞鹜文楷（LXGW WenKai，OFL）；许可清单见 `app/src/main/assets/FONT_LICENSES.txt`。注册在 `Theme.kt` 的 `ReadingFontOptions`，全局经 MaterialTheme typography 注入，`BasicTextField` 需单独接 `LocalReadingFont`。

#### 题库版本机制

`Repo.ensureBankLoaded` 启动时比对 **DataStore `bank_version`** 与 **assets/questions.json 的 `version` 字段**：

- 相同 → 跳过导入（老手机也能拿到新题库靠这个机制，v2.2.0 引入，此前仅 `count == 0` 才导入）；
- 不同 → 清空全部学习数据（刷题记录 / 统计 / 模考 / 错题 / 打卡）后重新导入，并写入新版本号。**替换内置题库时必须递增 `version`，否则存量用户拿不到新题**。

---
## 构建指南

### 环境要求

| 项 | 版本 | 说明 |
|----|------|------|
| JDK | 21（需含 javac，即 JDK 而非 JRE） | `compileOptions` 与 `kotlinOptions.jvmTarget` 均为 21 |
| Android SDK | Platform 36 + Build-Tools 35.0.0 + Platform-Tools | `sdkmanager` 安装；`local.properties` 或 `ANDROID_HOME` 指向 SDK |
| Gradle | 8.14.3 | 由 wrapper 自动下载，无需手动安装 |
| 网络 | 首次构建需访问 google() / mavenCentral() | 依赖走 Gradle 缓存后可离线 |

### 本地构建

```bash
# Debug
./gradlew assembleDebug

# Release（与 CI 产物一致，需要签名环境变量，见「签名体系」）
export DQ_KS_PATH=/path/to/dronequiz.keystore
export DQ_KS_STORE_PASS=<密码>
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

<details>
<summary>无 Android Studio 的最小环境搭建（Linux）</summary>

```bash
# 1. JDK 21（Temurin）
# 2. Android cmdline-tools + sdkmanager
sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
# 3. export ANDROID_HOME=<sdk路径>   ← 新 shell 必须显式设置，workspace 重置后最常见漏项
./gradlew assembleRelease
```

</details>

> 常见坑：① 只装了 JRE 没有 javac → 换 JDK 21；② shell 里 `ANDROID_HOME` 未导出 → Gradle 找不到 SDK；③ Kotlin `-Xcontext-parameters` 编译器参数已在 `build.gradle.kts` 配置，不要删除，源码中有依赖该特性的写法。

---

## CI/CD 与发版流程

CI 由 [.github/workflows/build.yml](.github/workflows/build.yml) 承担，`push` 到 `main` 或手动触发（`workflow_dispatch`）时运行（纯文档提交不触发，见下）：

```
Checkout → JDK 21(Temurin) → Gradle Setup
  → 从 Secrets 恢复 keystore（DQ_KEYSTORE_B64 解码 + DQ_KS_STORE_PASS）
  → ./gradlew assembleRelease
  → 重命名为 TiYu-${APP_VERSION_NAME}.apk
  → Upload Artifact
  → softprops/action-gh-release 自动发布到 tag ${APP_VERSION_TAG}
```

- workflow 配置了 `paths-ignore: ['**.md']`：**只改 Markdown 文档的提交不会触发构建**，避免同版本号 tag 重复发布；
- 兜底手段：纯文档 / CI 配置微调的提交，commit message 里带 `[skip ci]` 可强制跳过本次构建；
- 监控 CI（本仓库开发环境的 `gh` CLI 已不可用，统一用 curl + REST API，Token 走环境变量 `GITHUB_TOKEN`，**严禁写死进任何文件**）：

```bash
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/Everett406/TiYu/actions/runs?per_page=5"
```

### 发版 Checklist（每版必做，五处同步）

| 顺序 | 位置 | 字段 | 示例 |
|------|------|------|------|
| 1 | `app/build.gradle.kts` | `versionCode`（每版 +1）、`versionName` | `23` / `"2.7.5"` |
| 2 | `.github/workflows/build.yml` `env` | `APP_VERSION_NAME`、`APP_VERSION_TAG` | `2.7.5` / `v2.7.5` |
| 3 | `.github/workflows/build.yml` `body:` | Release notes 文案 | 按更新内容撰写 |
| 4 | `CHANGELOG.md` | 版本变更记录 | 见现有条目 |
| 5 | `README.md` 版本历史表 | 补 `vX.Y.Z` 一行（v2.8.8 曾漏补、v2.8.9 追补，固化进流程） | 见表尾 |

提交前：

```bash
python3 scripts/static_check.py   # 括号平衡 + 残留/必备 API 检查，PASS 才准 push
```

> ⚠️ 版本号不同步的直接后果：tag 与 APK 实际版本不一致，用户在 Release 下载到旧版本号命名的 APK；同版本号 tag 复用会更新既有 Release / 覆盖同名附件，**发版必须递增版本号，不要重复发同一 tag**。

---

## 签名体系

项目自 v2.0.2 起使用**固定签名 keystore**（RSA 2048，有效期 30 年，alias `dronequiz`），本地与 CI 共用同一把钥匙，保证所有渠道 APK 签名一致、可覆盖安装。

| 载体 | 内容 |
|------|------|
| 本地 | keystore 与密码保存在仓库**之外**的安全目录（本项目工作区为 `/home/z/my-project/keystore/`：`dronequiz.keystore` + `pass.txt`），**严禁提交进仓库** |
| GitHub Secrets | `DQ_KEYSTORE_B64`（keystore 文件的 base64）、`DQ_KS_STORE_PASS`（store/key 密码，两者相同） |
| 构建注入 | 本地经环境变量 `DQ_KS_PATH` / `DQ_KS_STORE_PASS`；CI 由 build.yml 解码 secret 写入 `$RUNNER_TEMP` 后同样以环境变量传给 Gradle |
| 缺省回退 | 若环境变量缺失，release 构建回退为 **debug 签名**——该产物无法覆盖安装正式版，注意识别 |

**交接必读**：keystore 一旦丢失，后续所有版本都无法以固定签名发布，用户必须卸载重装。请立即备份 `dronequiz.keystore` 与密码（至少两份异地存储，如密码管理器 + 离线介质）。需要重置 Secrets 时可运行 `scripts/setup_signing.py`（需 `pip install pynacl`，并经环境变量提供 GitHub Token）。

---

## 题库维护

### 题库文件格式（assets/questions.json）

```jsonc
{
  "version": 2,                        // 题库版本号，替换题库必须递增
  "questions": [
    {
      "id": 1,                         // int，稳定唯一（CSV 题号优先，否则题干哈希）
      "category": "无人机装调",         // 分类名，刷题筛选 chips 数据源
      "type": "single",                // "single" 单选 / "judge" 判断
      "question": "时间压力可能会导致人为差错，是因为____。",
      "options": ["A 选项", "B 选项", "C 选项"],   // judge 类型固定为 ["正确","错误"]
      "answer": 2,                     // int，options 的下标（0 起）
      "explanation": "选C。……"          // 解析，可为空串
    }
  ]
}
```

### 从 CSV 转换内置题库

```bash
python3 scripts/convert_bank.py <题库.csv> [-o app/src/main/assets/questions.json]
```

脚本能力与约定：

- 自动识别列头（题干 / 选项A–H / 答案 / 解析 / 类型 / 分类 / 题号，支持中英文变体），多编码尝试（utf-8-sig / gb18030 等）；
- 答案归一化：字母（A–H）、对/错、正确/错误、数字序号（1 起）均可解析；解析失败会逐行列出；
- 输出 `version = 2`；**若替换的是新版题库，请手动递增 version**（如改为 3）并重新发版；
- 当前内置题库：`version = 2`，800 题（单选 640 / 判断 160），单分类「无人机装调」。

`scripts/gen_bank.py` 是初版 801 题参数化生成器（11 分类），仅作为历史数据源留存，不再用于当前题库。

### 用户题库导入格式（CSV / ZIP，v2.8.0 起）

应用内「提示词」按钮复制的是一份可直接发给 Agent 的完整整理规范；人工对照时速记如下：

| 通道 | 结构 | 要点 |
|------|------|------|
| CSV | 列头：题号 / 题干 / 选项A–H / 答案 / 解析 / 类型 / 分类 / 图片（可选） | RFC4180 解析；类型支持单选 / 多选 / 判断 / 填空 / 简答；图片列填文件名，多图 `\|` 分隔 |
| ZIP | 题目 CSV + `images/` 图片文件夹（也兼容只打包单个 CSV） | 仅被引用的图片落盘、随题库删除一并清理；图片支持 jpg / jpeg / png / webp / gif / bmp，建议英文数字命名 |

- 导入完成自动切换为新题库；学习数据（进度 / 统计 / 错题）按题库隔离保存；
- 题库可重命名（列表铅笔入口，≤16 字）与删除（内置题库删除后可经「清空记录」恢复）；
- 「CSV 模板」按钮走系统分享面板（可发微信 / 邮件等），模板含图片列与 ZIP 打包示例行。

---

## 诊断与排障

### 用户侧（按现象）

| 现象 | 处置 |
|------|------|
| 打开即闪退 | 重新打开应用：若出现「启动报告」屏，复制内容反馈；否则应用会自动进入安全模式（顶部出现安全模式横幅） |
| 安全模式下正常，开特效后异常 | 设置 → 画面特效 关闭；将机型 + Android 版本反馈到 Issues |
| 无法覆盖安装 | 先卸载旧版再装（多为混入了 debug 签名构建或 v2.0.x 旧签名版本） |
| 想换题库 | 设置 → 题库管理 → 导入题库（CSV / ZIP），或复制「提示词」交给 Agent 整理材料 |
| 打赏弹窗不想再看到 | 点「以后别提醒我」即永久关闭 |
| 「检查更新」失败或无反应 | 检查网络后重试（应用仅访问 GitHub Releases API）；也可直接到 Releases 页下载最新 APK |

### 开发侧

| 数据 | 位置（应用私有目录） | 用途 |
|------|----------------------|------|
| `last_crash.txt` | `/data/data/com.drone.quiz/files/` | 最近一次 Java 层崩溃堆栈 |
| `boot_log.txt` | 同上（≤150 行） | 启动面包屑，定位死在启动哪个阶段 |
| `boot_state.txt` | 同上 | 心跳状态；「上次是否健康启动」决定安全模式触发 |

`adb bugreport` 或 `adb shell run-as com.drone.quiz cat files/boot_log.txt`（debug 构建可用）可直接取阅。

---

## 已知限制与注意事项

1. **迁移纪律**：DB 已建立显式重建式 Migration（1→4），但每次改表结构仍必须手写新 Migration + 递增 version + 默认值列对齐，漏写即触发兜底清库（见[数据层](#数据层)）；
2. **题库升级重置**：内置题库 `version` 变更会清空全部学习数据（设计如此，因题目 id 不具备跨版本稳定性）；若未来需要保留记录，须改为「题干哈希稳定 id + 迁移映射」方案；
3. **液态玻璃依赖 GPU 驱动**：AGSL 行为在不同厂商 GPU 上存在差异，已有三层守护兜底，但新增玻璃用法仍需真机验证（见[硬约束](#⚠️-硬约束记录层内禁止-drawbackdrop-采样v210-血泪教训)）；
4. **minSdk = 31**：Android 11 及以下不在支持范围（RenderEffect 基线；同时也是 MediaStore 免权限保存打赏码的基线）；
5. **竖屏锁定**：Manifest 固定 `portrait`，无平板 / 横屏适配计划；
6. **签名 keystore 不可再生**：丢失即失去固定签名能力（见[签名体系](#签名体系)）；
7. **无自动化测试**：当前仓库未包含单元 / UI 测试；`scripts/static_check.py` 只能兜住括号失衡与历史 API 残留等最低级错误，回归依赖真机手工验证；
8. **打赏计时粒度 1 分钟**：刷题时长按 60s 心跳累计（仅答题页前台计入），进程被系统直接杀死时最多丢失最后一个心跳周期；
9. **打赏收款码入仓库**：`res/raw/support_qr.png` 是作者收款码，随 APK 公开分发——这是功能本意（供用户保存后扫码支持），如需更换直接替换该文件即可，**无需改代码**。

---

## 工具脚本

| 脚本 | 用途 | 备注 |
|------|------|------|
| `scripts/static_check.py` | **发版前静态自检**（括号平衡 / 历史 API 残留 / 关键特性防误删断言） | push 前必跑；零依赖，有 rg 用 rg、没有自动降级 os.walk |
| `scripts/convert_bank.py` | 题库 CSV → `assets/questions.json` | 日常题库维护主入口 |
| `scripts/gen_bank.py` | 初版 801 题参数化生成器 | 历史留存 |
| `scripts/vendor_backdrop.py` | 从上游 backdrop 仓库源码 vendor 进 `com.kyant.backdrop` | `SRC` 为上游源码本地路径，升级库版本时修改后运行；vendor 后需按 NOTICE 重做 expect/actual 合并等改动 |
| `scripts/setup_signing.py` | 生成 / 校验 keystore，并写入 GitHub Secrets（`DQ_KEYSTORE_B64` / `DQ_KS_STORE_PASS`） | GitHub Token 经环境变量 `GITHUB_TOKEN` 提供；依赖 `pynacl` |

---

## 版本历史

| 版本 | versionCode | 主题 |
|------|-------------|------|
| v2.0 | 2 | 全量功能重建：真液态玻璃（Kyant0 backdrop）、五大页面、动画系统、801 题内置题库 |
| v2.0.1 | 3 | 首帧 AGSL 着色器构造失败自动降级 + CrashGuard 崩溃报告屏 + 新数据库文件名 |
| v2.0.2 | 4 | BootGuard 启动守护 + 自动安全模式 + 画面特效开关 + **固定签名** |
| v2.1.0 | 5 | **根治首启 SIGSEGV**（官方 backdrop 架构：记录层内禁用 drawBackdrop 采样，内容流改 glassMaterial）+ 滑杆居中 / 开关样式修复 |
| v2.2.0 | 6 | 修复开关圆钮恒停左侧、底栏图标消失 / 幽灵槽；接入正式题库 800 题；题库版本机制 |
| v2.3.0 | 7 | **液态玻璃全面对齐官方**（GlassKit 组件族重写、Capsule 连续曲率胶囊、弹窗玻璃化）；题库加载死锁 / 模考统计污染修复 |
| v2.3.1 | 8 | 热修 v2.3.0 首启 lens 崩溃（Lens.kt RoundedRectangularShape 分支补回 + 不支持形状优雅跳过折射） |
| v2.3.2 | 9 | 底栏单击 / 拖拽切换、滑杆手势统一、题库加载 10s 超时重试、模考答题卡面板、通知运行时权限 |
| v2.4.0 | 10 | 底栏拖拽根因修复；**刷题页重构**（配置入口页 + 全屏刷题）；刷题会话快照持久化；模考继续 / 放弃；答题卡玻璃面板；弹窗模糊体感 |
| v2.5.0 | 11 | 弹窗被自身模糊修复；弹窗折射升级；BounceState 过冲回弹重写；界面整理 |
| v2.5.1 | 12 | 回弹手感 v3；底栏方框 / 圆环伪影；左右切题裁切；应用图标更换 |
| v2.6.0 | 13 | 滚动惯性丢失修复；刷题记录丢失双根因；**题目搜索**；**全局壁纸**；底栏模糊三档；错题本筛选 + 滚动把手 |
| v2.6.1 | 14 | 滚动惯性真根因（连续三轮未修透）；fling 过冲挂住；壁纸可读性；每日提醒智能化 |
| v2.6.2 | 15 | 标题柔化伪影 / 突变 / 重影同根三症；渐显去固定延迟 |
| v2.6.3 | 16 | 柔化伪影根除（雾条方案）；壁纸更换不生效修复；**内置 4 张壁纸** |
| v2.6.4 | 17 | 柔化方案回调（雾条废弃，回归蒙版柔化）；过冲越界修复 |
| v2.7.0 | 18 | 羽化 v3（saveLayer 重做）；首页问候 + 昵称；搜索历史；搜索框 Hero 动画；设置分区重排；错题本把手 v2 |
| v2.7.1 | 19 | 羽化 v4 生长式蒙版；Hero 动画真正生效；光标 / 问候 / 双卡排版 |
| v2.7.2 | 20 | **羽化正式砍除**；滚动把手 v3 齿轮感根除（像素级连续映射）；**内置阅读字体四款**；昵称输入框；刷题进度恢复竞态修复 |
| v2.7.3 | 21 | 随机刷题真随机（Room 保序 + 设置首帧双根因）；进度接续加固（错题特训不再覆写快照）；**更名「题屿」**；错题本间距 / 把手样式微调 |
| v2.7.4 | 22 | **打赏弹窗**（累计 2h / 考试不扰 / 收款码存相册 / 拒绝永久）；**模考历史回看（DB 重建成绩）+ 三点删除（每周限 2 次）**；**顺序 / 随机双槽进度**；成绩页错题解析 LazyColumn 流畅化；重新开始二次确认胶囊 |
| v2.8.0 | 23 | **多题库管理**（设置页统一管理 / 首页副标题切换弹窗 / 按库隔离 / 内置可删清空恢复）；**导入底部弹窗**（JSON / CSV / RFC4180 解析 / 模板 / 导入报告）；**新题型多选 / 填空 / 简答**（自适应筛选、全对才算对、键盘避让、自评计分）；**模考题型分段组卷 + 高级选项拖拽排序**；错题特训按筛选；支持作者手动入口；⚠️ 迁移缺陷致老用户升级启动崩溃 |
| v2.8.1 | 24 | **修复 v2.8.0 升级启动崩溃**：数据库迁移重写为重建表模式（建新表→拷数据→删旧表→改名→补齐全部索引，与实体校验逐列逐索引一致），补建模考历史 startedAt 索引；崩溃设备库未受损、覆盖安装自动重迁移，数据完整保留；static_check 固化迁移禁令防回归 |
| v2.8.2 | 25 | **修复示例题库从未出现**（sample JSON 多选用 answers 数组，播种只读 answer 位掩码→校验全败静默跳过）；**题库隔离加固**（刷题配置页首帧默认值 bug 修复：切库后分类/概览跟随当前库；首页今日/近 7 天改按库统计并标注）；把手去重 / 高级选项展开动画 / 题库切换小窗玻璃化+动画 / 题型构成改滑杆 / CSV 模板改系统分享 / 导入弹窗键盘避让 / 弹窗壁纸一并模糊 |
| v2.8.3 | 26 | **模考题型构成重构**（移入高级选项；自动按库占比配比开关 / 手动比例滑杆联动让份额；题目数量外置）；**修高级选项展开文字重叠**（AnimatedVisibility 多子级叠放）；**答题卡按题型分段**（小标题+已答计数）；**填空题题干原位输入**（拆 ____ 占位内嵌输入框）；**解析展开动画回归**；提交按钮禁用态对比度；刷题页分类筛选移除；题型 chips 自动换行；弹窗把手上拉过冲；收款码两态推移动画；打赏文案真实累计时长；OverlayBlur 引用计数修叠层丢模糊；模糊/折射降档提帧 |
| v2.8.4 | 27 | **修组卷题型丢失**（排序提示被当白名单→20 题库只出 4 题/只剩判断单选，同根因）；**滑杆联动位置跟随**（rememberUpdatedState 修闭包捕获旧 lambda）；**高级选项不再自动收起**（开关双触发整卡 onClick）；模考第 4 卡补齐（题型构成摘要+占比预览条）；**刷题题型多选**（保留「全部」，逗号串编码+IN 查询）；**填空空位自适应重设计**（60–170dp 随内容）+ 题干去重；**提交按钮自适应壁纸明暗反色** |
| v2.8.5 | 28 | **图片题库**：导入改「CSV / ZIP」双通道（JSON 下线）；**ZIP 导入**（题目 CSV + images 图片文件夹，兼容单 CSV 打包；「图片」列文件名匹配、多图 \| 分隔；仅被引用图片落盘，随题库删除）；DB v3（questions 重建式加 images 列）；**复制 Agent 提示词**（可滚动预览+一键复制，交给 Agent 整理 Excel/Word/PDF 为 CSV/ZIP）；CSV 模板加图片列+ZIP 示例行；**题目图片展示**（小图点按 Hero 式展开大图，刷题/模考接入，降采样+缓存） |
| v2.8.6 | 29 | **护眼提醒**（设置-提醒新开关：连续刷题 20 分钟弹窗提醒看远处，20-20-20 法则，仅前台计时、考试不受影响）；**顺序刷题循环补漏**（刷到末题即本轮结束，下轮只挑没刷过的题，covered 链式累计，全覆盖后开完整新一轮）；**成绩单回显合格线**（DB v4：exam_records +passLine 列，开考定格）；导入弹窗删灰色小字+按钮改名（CSV 模板/提示词）；图片小图缩小（64–96dp+宽度贴合）+删「点按放大」角标；收款码图片内层圆角裁切；**性能治理**（根级订阅收敛 RootSettings：修会话快照写入触发整树重组的卡顿；题单上限 800→2000） |
| v2.8.7 | 30 | **防沉迷**（「护眼提醒」更名；全部设置说明字压缩 ≤10 字左右不换行）；**题库重命名**（题库管理铅笔入口+GlassInputDialog 玻璃输入对话框，限长 16 字）；**错题本删除体验**（删除钮 30dp 触达区+左移避开把手轨道 44→24dp；两段式删除动画：缩放淡出+animateItem 条目弹性靠拢）；**小字自适应背景**（readableSubColor：分区标题/页面副标题/页脚提示按壁纸明暗自动提亮压深） |
| v2.8.8 | 31 | **切库数据混乱根治**（会话快照读口按 currentBank 过滤，配置页「接续上次进度」不再串显异库进度、切随机不再露内置题库旧会话；进度按库各自保存）+ **检查更新**（关于页胶囊查 GitHub Releases 比对版本，弹窗引导浏览器打开发布页；版本行可点直达；INTERNET 权限）+ **流畅度**（RootSettings 剔除每分钟变化的三字段，根级连锁重组消除；异库旧会话重复加载消除）+ **仓库更名 TiYu**（remote/CI/APK 名/README 同步，旧链接 301 不断链） |
| v2.8.9 | 32 | **关于页弹窗化**（内联卡片改单行入口，点开底部弹出玻璃弹窗：文楷文档式排版+虚线分隔+应用介绍；版本行直达 Releases；「请喝奶茶/检查更新」双胶囊入弹窗）；**修复检查更新提示串位**（「已是最新版本」误入题库管理区提示位→独立状态只渲染在弹窗内）；**修复奶茶按钮换行不等高**（固定 44dp 等高+单行+文案精简「请喝奶茶」） |
| v2.8.10 | 33 | **修复打赏弹窗层级**（关于弹窗内点「请喝奶茶」，打赏弹窗被压在关于弹窗下方且无法操作——传送门注册顺序根因；交互改为先关关于弹窗、退场动画走完再弹打赏弹窗，不再同屏叠加） |
| v2.9.0 | 34 | **首启功能引导**（欢迎卡+7 步高亮气泡导览：真实界面锚点挖孔+呼吸描边+玻璃气泡自适应落位+bringIntoView 滚锚点+缺失兑底居中；遮罩吃掉触控防状态脱钩；跳过即不再自动弹；设置页「使用引导」入口随时重看） |
| v2.9.1 | 35 | **引导体验打磨**（步骤间 Hero 式转场：挖孔跨步平滑飞行+气泡同节奏跟随+内容方向感知滑动切换+整层淡入淡出；8 步文案精简为一句话；修复欢迎卡 CTA「开始逛一逛」未生效；加固引导层触控接管，转场窗口期不再误触下层） |
| v2.9.2 | 36 | **无分类题回退显示题库名**（displayCategory+rememberBankName 四处接入：刷题/模考卡片/搜索/错题本）+ **模考成绩页错题解析展开动画**（弹性展开+渐隐）+ **打赏门槛只计真实刷题时长**（UsageSignals 门控，口径对齐弹窗文案「累计刷题」） |
| v2.9.3 | 37 | **果冻（Gooey）模式**：关玻璃分支整体改为亚克力表面（只模糊无折射，默认 8dp）+ gooey 液态动效层（createChainEffect：blur→alpha 阈值；API 33+ AGSL / API 31–32 ColorMatrix 双路）；底栏液态拖尾 / Toggle 拉伸融合 / 按钮按压液泡 / 弹窗过冲 / 勾选框与对勾融合；特效三级体系（玻璃→果冻→安全平涂，崩溃兜底独立于用户设置）；文字零接触 goo 阈值层；设置文案「液态玻璃 / 果冻」二选一 |
| v2.10.0 | 38 | **桌面小组件 ×4**（极简杂志风：继续刷题磁贴 / 学习统计卡 4×2 目标进度环+正确率+连击 / 错题特训卡 / 快速模考贴；RemoteViews 零新依赖、深浅色自适应、作答/交卷/删记录即时刷新+30min 兜底；设置-刷题新增「每日目标」）+ **长按快捷方式 ×4**（继续刷题接续会话 / 错题特训沿用筛选 / 随机小练抽 20 题 / 快速模考沿用配置直落开考；singleTask+LauncherBus 意图路由）+ **成绩分享卡**（成绩页「生成成绩卡」，Canvas 渲染 7 主题：日落/极光/薄荷/液态玻璃/纸感白/墨黑/深色金辉；大分数+合格徽章+四宫格+较上次进步对比+打卡累计；署名/标语/强调色即改即存；预览即最终产物；MediaStore 存相册免权限 + FileProvider 系统分享） |
| v2.10.1 | 39 | **成绩卡体验修复**：预览加投影+细描边（深色主题卡片轮廓一眼可辨，与主题区间距加大）；修复署名/标语输入框占位提示不显示（decorationBox 改标准 Box 叠加）+ 可见光标 + 边框对比度提高；移除设置页「每日目标」滑杆（统计卡/小组件目标照常按默认 30 题） |
| v2.10.2 | 40 | **修复 ColorOS/一加「载入窗口小部件时出现问题」**：四款小组件布局收敛到 RemoteViews 最保守子集——统计卡进度环矢量改预渲染 PNG（0~100% 十档×深浅 22 张，PIL 超采样），ImageView 切档；移除 ProgressBar 主题属性引用与 letterSpacing |
| v2.10.3 | 41 | **修复切题库/换题型范围后刷题进度被清零**：会话快照升级多槽存储（题库×模式×题型范围各自占槽，LRU 上限 10），切库/换范围互不覆盖、切回原样接续；旧进度无损迁移；「继续刷题」接最近一份；static_check 修正 widget 禁令路径 + 旧单槽写法即 FAIL |

完整变更明细见 [CHANGELOG.md](CHANGELOG.md)。

---

## 给接手人的建议

1. **先读本文档**「液态玻璃实现」「刷题会话双槽机制」两节——它们分别对应本项目最大的崩溃风险源和返工最多的业务模块；
2. **再翻 CHANGELOG.md**：每轮修复都记录了根因分析，大部分「为什么这么写」的答案都在里面；
3. 改代码前跑一次 `python3 scripts/static_check.py` 建立基线，改完再跑一次；
4. 发版严格走「四处同步 + static_check + push main」流程（见 [CI/CD 与发版流程](#cicd-与发版流程)），CI 失败先看 Actions 日志定位编译错，本仓库的历史 CI 失败几乎全是「重构漏声明 / import 遗漏」一类；
5. 涉及玻璃渲染的改动，**合并前必须真机冷启动验证首帧**；
6. keystore 备份永远排第一优先级。

---

## 致谢

- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) —— 液态玻璃核心能力来源（backdrop 库 + shapes，Apache-2.0，vendor 并入并修改，见 [NOTICE](NOTICE)）
- [Jetpack Compose](https://developer.android.com/compose) / [Room](https://developer.android.com/training/data-storage/room) / [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) —— AndroidX 全家桶
- [Noto Sans SC / Noto Serif SC](https://fonts.google.com/noto) 与 [霞鹜文楷 LXGW WenKai](https://github.com/lxgw/LxgwWenKai) —— 内置阅读字体（OFL，子集化嵌入，见 FONT_LICENSES）
- 《无人机装调题库（含解析）》 —— 题库内容由需求方提供

## 许可证

本项目代码以 [Apache License 2.0](LICENSE) 发布；`com.kyant.backdrop/`、`com.kyant.shapes/` 目录源码源自 Kyant0/AndroidLiquidGlass（Apache-2.0），按其许可要求保留署名与变更说明（见 [NOTICE](NOTICE)）。内置阅读字体按各自开源许可（OFL）分发，清单见 `app/src/main/assets/FONT_LICENSES.txt`。内置题库内容版权归原始编制方所有，仅随本应用分发使用。
