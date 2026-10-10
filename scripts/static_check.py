#!/usr/bin/env python3
"""静态自检：括号平衡 + 残留/必备引用检查（发版 push 前必跑）。

用法：
    python3 scripts/static_check.py [源码目录]

不传参数时默认定位仓库内 app/src/main/java。
零第三方依赖：装有 ripgrep(rg) 时用其列文件，缺失则自动退回 os.walk；
环境变量 DQ_SC_NO_RG=1 可强制走纯 Python 路径（自测降级逻辑用）。

退出码：0 = PASS，1 = FAIL（缺失必备 API / 括号不平衡 / 目录不存在）。
"""
import os
import shutil
import subprocess
import re
import sys

DEFAULT_BASE = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "java"))
BASE = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_BASE


def list_kt_files(base):
    if not os.path.isdir(base):
        print(f"[FAIL] 源码目录不存在: {base}")
        sys.exit(1)
    if shutil.which("rg") and os.environ.get("DQ_SC_NO_RG") != "1":
        out = subprocess.run(["rg", "--files", base, "-g", "*.kt"],
                             capture_output=True, text=True).stdout.split()
        if out:
            return out
    files = []
    for root, _dirs, names in os.walk(base):
        for n in names:
            if n.endswith(".kt"):
                files.append(os.path.join(root, n))
    return sorted(files)


FILES = list_kt_files(BASE)

fail = False
_cache = {}


def load(f):
    if f not in _cache:
        with open(f, encoding="utf-8") as fh:
            _cache[f] = fh.read()
    return _cache[f]


def grep_hits(pat):
    """按行做字面量匹配（等价 rg -n 的字面用法），返回命中行。"""
    hits = []
    for f in FILES:
        for lineno, line in enumerate(load(f).splitlines(), 1):
            if pat in line:
                hits.append(f"{f}:{lineno}: {line.strip()[:140]}")
    return hits


# 1) 括号平衡（忽略字符串/注释内容的逐字符粗检）
for f in FILES:
    src = load(f)
    depth = {"(": 0, "{": 0, "[": 0}
    pairs = {")": "(", "}": "{", "]": "["}
    i, n = 0, len(src)
    mode = None  # None | 'line' | 'block' | 'str' | 'chr'
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if mode is None:
            if c == "/" and nxt == "/":
                mode = "line"; i += 2; continue
            if c == "/" and nxt == "*":
                mode = "block"; i += 2; continue
            if c == '"':
                mode = "str"; i += 1; continue
            if c == "'":
                mode = "chr"; i += 1; continue
            if c in depth:
                depth[c] += 1
            elif c in pairs:
                depth[pairs[c]] -= 1
                if depth[pairs[c]] < 0:
                    print(f"[FAIL] {f}: 多余的 {c} @ offset {i}"); fail = True; break
        elif mode == "line":
            if c == "\n":
                mode = None
        elif mode == "block":
            if c == "*" and nxt == "/":
                mode = None; i += 2; continue
        elif mode == "str":
            if c == "\\":
                i += 2; continue
            if c == '"':
                mode = None
        elif mode == "chr":
            if c == "\\":
                i += 2; continue
            if c == "'":
                mode = None
        i += 1
    for k, v in depth.items():
        if v != 0:
            print(f"[FAIL] {f}: 括号 {k} 不平衡，差 {v}"); fail = True

# 2) 残留引用检查：历史上已删除的 API/写法再次出现时提示（多为整块重写的遗漏）
stale = [
    ("notifyHint", "SettingsScreen 已删除的 notifyHint 残留"),
    ("PeriodicWorkRequestBuilder", "Notify.kt 已移除的周期调度残留"),
    ("ExistingPeriodicWorkPolicy", "Notify.kt 已移除的策略残留"),
    ("PageInfo", "历史 CI 陷阱 PageInfo.page 残留"),
    ("calculateTargetValue", "新版 Compose 已移除的 API"),
    ("widthIn(min = 110.dp", "填空输入框旧固宽残留(v2.8.4 已改自适应 60-170dp)"),
]
for pat, desc in stale:
    hits = grep_hits(pat)
    if hits:
        print(f"[WARN] {desc}:")
        for h in hits[:6]:
            print("   ", h)

# 3) 关键 API 必须存在：防止整函数/整特性被误删而 CI 才发现
must = [
    ("practiceTsSince", "Dao 新查询"),
    ("habitStartHours", "Repo 习惯时刻"),
    ("onPostFling", "Bounce v5 兜底"),
    ("scrolledFromTopPx", "柔化连续渐显"),
    ("clipToBounds", "Bounce 过冲裁剪"),
    ("remainingBottomPx", "网格底部剩余像素"),
    ("StreakHeatmap", "打卡热力图(v2.19.7，双卡已合并为单全宽卡)"),
    ("wallScrim", "壁纸主题纱"),
    ("practiceSessionRandom", "刷题会话随机槽"),
    ("examDeleteQuota", "模考删除周限额"),
    ("addUsageMs", "打赏使用时长累计"),
    ("displayCategory", "无分类题回退显示题库名(v2.9.2)"),
    ("UsageSignals", "打赏门槛只计真实刷题时长(v2.9.2)"),
    ("_new_exam_records", "MIGRATION_1_2 重建表模式(v2.8.1 修启动崩)"),
    ("index_exam_records_startedAt", "迁移补建 startedAt 索引(Room 校验必需)"),
    ("q.answers", "内置题库播种支持 multi answers 数组(修示例题库不出现)"),
    ("last7DaysByBank", "今日/近7天统计按题库隔离"),
    ("GlassAnchorMenu", "首页题库切换玻璃锚点菜单"),
    ("shareTextFile", "CSV 模板系统分享(FileProvider)"),
    ("imePadding", "键盘避让(刷题页/导入弹窗)"),
    ("examAutoMix", "模考题型构成自动配比开关(v2.8.3)"),
    ("autoRatios", "模考按题库占比自动配比(v2.8.3)"),
    ("buildSheetGroups", "模考答题卡按题型分组(v2.8.3)"),
    ("BlankInlineFields", "填空题题干内嵌输入(v2.8.3)"),
    ("OverlayBlur.push", "弹窗模糊引用计数(修叠层丢模糊)"),
    ("rawDragY", "把手下滑关闭+上拉 rubber-band 过冲"),
    ("formatUsage", "打赏弹窗累计时长动态文案"),
    ("rememberUpdatedState", "GlassSlider 外部值同步读最新 lambda(修联动滑块只变数值 v2.8.4)"),
    ("idsByFilterTypes", "题型多选 DAO IN 查询(v2.8.4)"),
    ("splitTypeFilter", "题型筛选逗号串解析(v2.8.4)"),
    ("LocalWallpaperLuminance", "壁纸亮度 CompositionLocal(自适应对比 v2.8.4)"),
    ("backdropIsDark", "提交按钮背景明暗自适应(v2.8.4)"),
    ("MixPreviewBar", "模考题型构成第4卡分布预览条(v2.8.4)"),
    ("val activeTypes = buildList", "activeTypes 排序提示非白名单(修 20 题只出 4 题 v2.8.4)"),
    ("parseZip", "ZIP 导入(题目CSV+图片 v2.8.5)"),
    ("QuestionImages", "题目图片存储 bank_images/<bankId>(v2.8.5)"),
    ("QuestionImageStrip", "题目图片组件·小图点按展开(v2.8.5)"),
    ("GlassPromptDialog", "Agent 提示词可滚动复制对话框(v2.8.5)"),
    ("AGENT_PROMPT", "Agent 整理提示词模板(v2.8.5)"),
    ("MIGRATION_2_3", "questions+images 重建表迁移(v2.8.5)"),
    ("_new_questions", "MIGRATION_2_3 重建表模式(v2.8.5)"),
    ("eyeCareReminder", "防沉迷刷题计时开关(v2.8.6/2.8.7 更名)"),
    ("sessionAtEnd", "顺序刷题刷到末题口径(v2.8.6)"),
    ("loadCatchUpRound", "顺序刷题未刷题回补(v2.8.6)"),
    ("passLine", "成绩单回显合格线(v2.8.6)"),
    ("conflateForRoot", "根级订阅收敛性能治理(v2.8.6)"),
    ("renameBank", "题库重命名 Repo/DAO(v2.8.7)"),
    ("GlassInputDialog", "玻璃输入对话框·重命名用(v2.8.7)"),
    ("readableSubColor", "小字自适应背景色(v2.8.7)"),
    ("animateItem", "错题本删除靠拢动画(v2.8.7)"),
    ("acrylicMaterial", "亚克力表面材质·只模糊无折射(v2.9.3)"),
    ("rememberReducedMotion", "系统减弱动画降级(v2.9.3/v2.11.2 挪入 GlassKit)"),
    ("MODE_ACRYLIC", "特效三级体系·亚克力模式(v2.11.2 原果冻，无 goo 动效)"),
    ("WidgetUpdater", "桌面小组件引擎·四款 RemoteViews(v2.10.0)"),
    ("ShareCardRenderer", "成绩分享卡 Canvas 渲染器·7主题(v2.10.0)"),
    ("ShareCardHost", "成绩分享卡全屏浮层·预览即产物(v2.10.0)"),
    ("ExamQuickConfig", "快速模考配置快照(v2.10.0)"),
    ("LauncherBus", "快捷方式/小组件启动目标中继(v2.10.0)"),
    ("savePngBitmap", "位图存相册 MediaStore 免权限(v2.10.0)"),
    ("practice_sessions_v2", "多槽会话存储 DataStore key(v2.11.0 修切库/换范围进度被覆盖)"),
    ("SessionSlots", "多槽会话容器(v2.11.0)"),
    ("ensureSlotsMigrated", "旧双槽一次性迁移·进度无损(v2.11.0)"),
    ("latestPracticeSession", "继续刷题接续最近会话(v2.11.0)"),
    ("clearAllPracticeSessions", "题库升级/清空数据全清会话(v2.11.0)"),
    ("purgeBankSessions", "删题库清会话槽(v2.11.0)"),
    ("ImportBus", "外部「其他应用打开」导入中继(v2.11.1)"),
    ("ExternalBankImporter", "外部打开读流+PK头嗅探解析器(v2.11.1)"),
    ("ExternalBankFile", "外部打开预解析 DTO·直接进预览态(v2.11.1)"),
    ("ExternalImportHost", "外部导入全局宿主·复用导入弹窗(v2.11.1)"),
    ("VibrateFeedback", "答对震动工具(v2.11.3)"),
    ("vibrate_on_correct", "答对震动 DataStore 键(v2.11.3)"),
    ("setVibrateOnCorrect", "答对震动开关 setter(v2.11.3)"),
]
for pat, desc in must:
    if not grep_hits(pat):
        print(f"[FAIL] 未找到 {desc}（{pat}）"); fail = True

# 3.5) 迁移 SQL 模式检查（v2.8.0(23) 踩坑：NOT NULL 列带 DEFAULT 而实体未声明 defaultValue，
#      Room 逐列校验不匹配 → 启动即崩）。
#      v2.8.6 起放行"对齐模式"：ADD COLUMN ... NOT NULL DEFAULT x 的同时，
#      实体必须声明 @ColumnInfo(defaultValue = "x")（v2.8.1 先验证、v2.8.6 passLine 复用）。
#      grep 级实现：db 文件出现 NOT NULL DEFAULT 时，Entities.kt 必须存在 @ColumnInfo(defaultValue。
_has_aligned_entity = any("@ColumnInfo(defaultValue" in load(f) for f in FILES)
for f in FILES:
    if os.sep + "db" + os.sep in f or f.endswith(os.sep + "AppDatabase.kt"):
        for lineno, line in enumerate(load(f).splitlines(), 1):
            if "NOT NULL DEFAULT" in line and not _has_aligned_entity:
                print(f"[FAIL] 迁移 SQL 含 NOT NULL DEFAULT 但实体未声明 @ColumnInfo(defaultValue)（Room 校验必崩）: {f}:{lineno}")
                fail = True

# 3.6) 小组件 RemoteViews 保守化禁令（v2.10.2：ColorOS「载入窗口小部件时出现问题」清零改造）
#      widget 布局只许最保守白名单子集：禁 theme attr 引用（宿主 Context 无 app theme 时
#      inflate 直接抛异常）、禁 letterSpacing（OEM inflater 历史坑）、禁 ProgressBar+rotate/ring
#      矢量环（stats 环改预渲染 PNG + ImageView）。
#      注：路径基于仓库根（BASE 是 app/src/main/java，历史版在本节曾拼错路径致检查空转，v2.11.0 修正）
import glob as _glob
_repo_root = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
_widget_layouts = _glob.glob(_repo_root + "/app/src/main/res/layout/widget_*.xml")
if not _widget_layouts:
    print("[FAIL] 未找到 widget 布局文件（检查路径错误或布局被误删）"); fail = True
for f in _widget_layouts:
    s = load(f)
    if "?android:attr" in s or "?attr" in s:
        print(f"[FAIL] widget 布局禁止 theme attr 引用（RemoteViews 宿主端 inflate 雷区）: {f}")
        fail = True
    if "letterSpacing" in s:
        print(f"[FAIL] widget 布局禁止 letterSpacing（OEM 宿主兼容性清零）: {f}")
        fail = True
    if "ProgressBar" in s:
        print(f"[FAIL] widget 布局禁止 ProgressBar（进度环用预渲染 PNG + ImageView）: {f}")
        fail = True
_wu = load(_repo_root + "/app/src/main/java/com/drone/quiz/util/WidgetUpdater.kt")
for pat, desc in [("setImageViewResource", "stats 环 PNG 档位切换"),
                  ("widget_ring_l_100", "浅色进度环 PNG 档位"),
                  ("widget_ring_d_100", "深色进度环 PNG 档位")]:
    if pat not in _wu:
        print(f"[FAIL] WidgetUpdater 缺 {desc}（{pat}）"); fail = True
for f in ["app/src/main/res/drawable/widget_ring_light.xml",
          "app/src/main/res/drawable/widget_ring_dark.xml"]:
    if os.path.exists(os.path.join(_repo_root, f)):
        print(f"[FAIL] 旧矢量环未删除（应已被 PNG 取代）: {f}")
        fail = True

# 3.7) 旧单槽会话 API 残留禁令（v2.11.0）：双参清空/单参读口是「切库开刷即覆盖原库进度」
#      缺陷的根源写法，回归即 FAIL。
for pat, desc in [
    ("setPracticeSession(null, ", "旧双参单槽清空签名"),
    ("currentPracticeSession(order)", "旧单参读口"),
    ("practiceSession(settings.practiceOrder)", "旧单参流读口"),
]:
    for h in grep_hits(pat):
        print(f"[FAIL] 旧单槽会话 API 残留（{desc}）: {h}"); fail = True

# 3.8) 外部「其他应用打开」导入链路（v2.11.1）：intent-filter 缺失 = 系统分享列表里
#      题屿消失（微信/文件管理器无法看到入口，只能回应用内手动选文件）；
#      octet-stream/text/plain 是微信对 zip/csv MIME 推断不准时的兜底类型，缺一个就少一类入口。
_manifest_xml = load(_repo_root + "/app/src/main/AndroidManifest.xml")
if "android.intent.action.VIEW" not in _manifest_xml:
    print("[FAIL] Manifest 缺 ACTION_VIEW intent-filter（外部打开导入入口消失）"); fail = True
for _mime in ["application/zip", "application/x-zip-compressed", "application/octet-stream",
              "text/csv", "application/csv", "text/comma-separated-values", "text/plain"]:
    if f'android:mimeType="{_mime}"' not in _manifest_xml:
        print(f"[FAIL] Manifest VIEW intent-filter 缺 mimeType {_mime}（微信/文件管理器兜底类型）"); fail = True

# 3.9) 发版版本号三处一致性（v2.11.1 踩坑：APP_VERSION_TAG 漏同步，release 步骤
#      用旧 tag v2.10.3 找到已存在 release 追加 APK——新版本没建、旧版本被污染）。
import re as _re
_gradle_src = load(_repo_root + "/app/build.gradle.kts")
_wf_src = load(_repo_root + "/.github/workflows/build.yml")
_vname = _re.search(r'versionName = "([\d.]+)"', _gradle_src)
_vcode = _re.search(r'versionCode = (\d+)', _gradle_src)
_wf_name = _re.search(r'APP_VERSION_NAME: ([\d.]+)', _wf_src)
_wf_tag = _re.search(r'APP_VERSION_TAG: v?([\d.]+)', _wf_src)
if not (_vname and _vcode and _wf_name and _wf_tag):
    print("[FAIL] 版本号解析失败（gradle / build.yml 格式变更，请修 3.9 节正则）"); fail = True
else:
    if not (_vname.group(1) == _wf_name.group(1) == _wf_tag.group(1)):
        print(f"[FAIL] 版本号三处不一致: gradle={_vname.group(1)} "
              f"build.yml name={_wf_name.group(1)} tag={_wf_tag.group(1)}")
        fail = True

# 3.10) 果冻(gooey)动效残留禁令（v2.11.2：关特效=纯亚克力模糊，goo 动效整体删除）。
#       题屿自有 goo API 复活即 FAIL（createChainEffect 不进禁令——vendored
#       com/kyant 库内部合法使用，grep 范围含 vendored 会误伤）。
for _gpat, _gdesc in [
    ("GooeyContainer", "goo 融合容器"),
    ("GooeyItem", "goo 液滴"),
    ("GooeyDefaults", "goo 参数"),
    ("GOOEY_SRC", "goo AGSL 着色器"),
    ("MODE_GOOEY", "旧果冻模式常量"),
    ("ui.gooey", "goo 包引用"),
    ("ui/gooey", "goo 包路径引用"),
]:
    for h in grep_hits(_gpat):
        print(f"[FAIL] 果冻动效残留（{_gdesc}，v2.11.2 已整体删除）: {h}"); fail = True

# 3.11) 答对震动链路（v2.11.3）：刷题/特训统一提交入口 onCommit 判定「回答正确」时
#       轻震一下。Manifest VIBRATE 权限缺失 = 震动静默失效；考试为整卷提交无即时
#       判定，ExamScreens 出现震动调用即 FAIL（防止后续改造误接入）。
if "android.permission.VIBRATE" not in _manifest_xml:
    print("[FAIL] Manifest 缺 VIBRATE 权限（答对震动静默失效）"); fail = True
_vib_src = load(_repo_root + "/app/src/main/java/com/drone/quiz/util/VibrateFeedback.kt")
for _vpat, _vdesc in [("VibratorManager", "API31 震动服务入口（minSdk 31 无旧版分支）"),
                      ("EFFECT_CLICK", "平台标准点击触感"),
                      ("hasVibrator", "无震动器设备防护")]:
    if _vpat not in _vib_src:
        print(f"[FAIL] VibrateFeedback 缺 {_vdesc}（{_vpat}）"); fail = True
_practice_src = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/PracticeScreen.kt")
if "VibrateFeedback.onCorrect(context)" not in _practice_src:
    print("[FAIL] PracticeScreen onCommit 未接入答对震动（正确性首次判出处必震）"); fail = True
for h in grep_hits("VibrateFeedback.onCorrect"):
    if "ExamScreens" in h or "exam" in h.lower():
        print(f"[FAIL] 考试路径不得接入答对震动（整卷提交无即时判定）: {h}"); fail = True

# 3.12) 底栏选中块色散（v2.11.4）：v2.5.1 误当"栏外彩圈"元凶关闭，v2.11.4 依用户
#       对比参照（酷安底栏棱镜彩虹边）恢复，与 GlassButton 同口径。回退即 FAIL。
_gbb_src = load(_repo_root + "/app/src/main/java/com/drone/quiz/ui/glass/GlassBottomBar.kt")
if "chromaticAberration = true" not in _gbb_src:
    print("[FAIL] 底栏选中块色散未开启（v2.11.4 已恢复，勿再误关）"); fail = True
if "chromaticAberration = false" in _gbb_src:
    print("[FAIL] 底栏存在 chromaticAberration = false 回退写法"); fail = True

# 3.13) 顺序刷题末题续轮（v2.11.5）：做完本轮末题（答对自动切题/手动「下一题」）应跳到
#       本轮第一个未答的题，不得回退为「停在末题不动」（老行为须退出重进才触发补漏轮，
#       观感像进度丢失：总题数 800→743、从第 1 题重新开始）。补漏轮副标题须带口径标识。
_ps_src = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/PracticeScreen.kt")
_catch_up = "questions.indices.firstOrNull { questions[it].id !in answers }"
if _ps_src.count(_catch_up) != 2:
    print(f"[FAIL] 末题续轮逻辑应恰好出现 2 处（onCommit 自动 + 手动下一题按钮）: 实际 {_ps_src.count(_catch_up)} 处"); fail = True
if "补漏轮 · 此前已刷" not in _ps_src:
    print("[FAIL] 补漏轮副标题口径标识缺失（防止总题数变少被误解为进度丢失）"); fail = True
if "autoNext && pagerState.currentPage < questions.size - 1" in _ps_src:
    print("[FAIL] onCommit 翻页条件回退为旧写法（末题停在原地不续轮）"); fail = True

# 3.14) 拍照搜题 / OCR 整体下线（v2.17.0 用户裁定，v2.12.0~v2.16.0 整条链路作废）：
#       从「必须存在」反转为「禁止复活」——模块整体删除后，任何文件/依赖/权限/资源
#       的残留都视为半拆状态（会拖慢构建、虚增 APK、或让相机权限无端索权）。
#       曾经的实现：PaddleOCR PP-OCRv4 mobile(det+rec) + MNN 推理(libdroneocr.so)、
#       模型首用按需下载(OcrModels)、自建 CameraX 取景页、切题/段级匹配链路。
#       下线范围：ocr/ 四个 kt、screens/PhotoCaptureScreen.kt、screens/PhotoSearchScreen.kt、
#       src/main/cpp/（CMake + ocr_mnn.cpp + MNN 头）、src/main/jniLibs/（libMNN 双 ABI + libc++）、
#       assets/ocr/ppocr_keys_v1.txt，以及 gradle 的 CameraX×4 / exifinterface / externalNativeBuild /
#       ndkVersion、manifest 的 CAMERA 权限与 camera.any 特性。
import os as _os
_manifest_src = load(_repo_root + "/app/src/main/AndroidManifest.xml")
_BANNED_PATHS = (
    "app/src/main/java/com/drone/quiz/ocr",
    "app/src/main/java/com/drone/quiz/screens/PhotoCaptureScreen.kt",
    "app/src/main/java/com/drone/quiz/screens/PhotoSearchScreen.kt",
    "app/src/main/cpp",
    "app/src/main/jniLibs",
    "app/src/main/assets/ocr",
)
for _p in _BANNED_PATHS:
    if _os.path.exists(_repo_root + "/" + _p):
        print(f"[FAIL] 拍照搜题/OCR 已整体下线（v2.17.0），残留路径复活：{_p}"); fail = True
# 路由 / 入口 / 依赖 / 权限：源码与配置里的任何提及都算残留
for _needle, _why in (
    ("PhotoCaptureScreen", "自建相机页类名"),
    ("PhotoSearchScreen", "拍照搜题结果页类名"),
    ("photoCapture", "拍照路由 photoCapture"),
    ("photoSearch", "拍照路由 photoSearch"),
    ("onOpenCapture", "搜索页相机入口回调"),
    ("PaddleOcr", "PaddleOCR 推理流水线"),
    ("OcrModels", "OCR 模型按需下载器"),
    ("OcrNative", "MNN JNI 封装"),
    ("ppocr_keys_v1", "OCR 字典资源"),
    ("libdroneocr", "OCR native 胶水层"),
    ("libMNN", "MNN 运行时"),
    ("segmentQuestions", "拍照搜题切题链路"),
    ("matchQuestions", "拍照搜题匹配链路"),
):
    _hits = grep_hits(_needle)
    if _hits:
        print(f"[FAIL] 拍照搜题/OCR 残留（v2.17.0 已下线）——{_why}：{_needle} 命中 {len(_hits)} 处，例 {_hits[0][:110]}")
        fail = True
for _dep in ("androidx.camera", "exifinterface", "com.google.mlkit", "play-services-mlkit"):
    if _dep in _gradle_src:
        print(f"[FAIL] 拍照搜题下线后不应再依赖 {_dep}（相机/EXIF/ML Kit OCR）"); fail = True
if "externalNativeBuild" in _gradle_src or "ndkVersion" in _gradle_src:
    print("[FAIL] 拍照搜题下线后工程已无 native 代码，externalNativeBuild/ndkVersion 须一并移除"); fail = True
if "android.permission.CAMERA" in _manifest_src or "android.hardware.camera.any" in _manifest_src:
    print("[FAIL] 拍照搜题下线后不应再声明 CAMERA 权限 / camera.any 特性（无端索权）"); fail = True

# 3.15) 渐进式模糊 —— v2.19.12 起**已整体移除**，此处只做「不得复活」的封锁
_prog_path = _repo_root + "/app/src/main/java/com/drone/quiz/ui/glass/ProgressiveBlur.kt"
if os.path.exists(_prog_path):
    print("[FAIL] ProgressiveBlur.kt 已被移除（用户裁定『干脆完全去掉』），不得复活"); fail = True
if os.path.exists(_prog_path + ".bak"):
    print("[FAIL] 不得留 ProgressiveBlur.kt.bak 之类的残留"); fail = True
_blur_hits = grep_hits("bottomEdgeBlur")
if _blur_hits:
    print("[FAIL] bottomEdgeBlur 已被移除，不得复活：")
    for h in _blur_hits[:6]:
        print("   ", h)
    fail = True
for _f in ("HomeScreen.kt", "PracticeConfig.kt", "ExamScreens.kt",
           "WrongBookScreen.kt", "SettingsScreen.kt"):
    if "gradientBlurEdges" in load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/" + _f):
        print(f"[FAIL] {_f} 不得再挂 gradientBlurEdges"); fail = True

# 3.15b) 打卡热力图（v2.19.7）
#   「连击卡 + 今日卡」两半并排 → 合并为一张全宽卡：顶行连击/今日两个数字，
#   中部 13 周热力图，底行脚注。半屏宽度塞不下 13 周，故必须合并。
_home_src2 = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/HomeScreen.kt")
if "StreakHeatmap(" not in _home_src2:
    print("[FAIL] 首页缺打卡热力图"); fail = True
if "heatmapDays(" not in _home_src2:
    print("[FAIL] 热力图未接数据（Repo.heatmapDays）"); fail = True
# 里程碑进度条按用户裁定移除——热力图已接管「我这阵子状态如何」
if "val p = (stats.streak.toFloat() / milestone)" in _home_src2:
    print("[FAIL] 里程碑进度条已按用户裁定移除，热力图接管该职责"); fail = True
# 0 题时不得显示正确率（此前显示「正确 0%」，0 题不存在正确率，是假数据）
if "正确 ${(stats.todayCorrect * 100) / stats.todayAnswered}%" in _home_src2 \
        and "stats.todayAnswered > 0" not in _home_src2:
    print("[FAIL] 今日正确率须在 todayAnswered > 0 时才显示（0 题不存在正确率）"); fail = True

_heat_src = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/StreakHeatmap.kt")
if not os.path.exists(_repo_root + "/app/src/main/java/com/drone/quiz/screens/StreakHeatmap.kt"):
    print("[FAIL] 热力图组件文件缺失：screens/StreakHeatmap.kt"); fail = True
else:
    # 分档只看题量：levelOf() 函数体内不得出现 correct
    _lvl = _heat_src[_heat_src.index("private fun levelOf("):]
    _lvl = _lvl[:_lvl.index("}")]
    if "correct" in _lvl:
        print("[FAIL] 分档函数 levelOf() 不得引入正确率（低正确率染暗红像欠账）"); fail = True
    # 未来日期必须留空不画，否则整张图左边看着缺一块
    if "future" not in _heat_src:
        print("[FAIL] 热力图须处理未来日期（留空不画）"); fail = True
    # v2.19.8 三条排版约束：写死格子尺寸会在宽屏右侧空一大块
    if "BoxWithConstraints" not in _heat_src:
        print("[FAIL] 热力图格子尺寸必须按可用宽度算（写死会在宽屏右侧空一大块）"); fail = True
    # 周内标签必须落在 0/2/4/6 行；写成四个盒子依次堆叠会把「日」挪到第 4 行
    # v2.19.9：格子间距不得再丢。v2.19.8 每个格子写成 Modifier.size(cell) 且没给行间距，
    # 格子连成实心板（用户反馈"过密"），而标签列却按 cell+gap 排版。
    # 行间距靠 Column(spacedBy)；横向间距由「列宽 pitch - 格子 cell」的余量给出
    if _heat_src.count("Arrangement.spacedBy(HeatGap)") < 1:
        print("[FAIL] 每列内部须用 Arrangement.spacedBy(HeatGap) 给出行间距"
              "（v2.19.8 漏掉行间距，格子连成实心板——用户反馈『过密』）"); fail = True
    # 月份标签不得因列宽不足被截断（「10月」→「10」）
    if "wrapContentWidth(unbounded = true)" not in _heat_src:
        print("[FAIL] 月份标签须允许溢出所在列，否则「10月」会被截成「10」"); fail = True
    # 月份标签不得设固定高度，9.sp 会被裁掉下半截
    _month_row = _heat_src[_heat_src.index("// ---- 月份标签"):_heat_src.index("// ---- 格子矩阵")]
    if ".height(" in _month_row:
        print("[FAIL] 月份标签行不得设固定高度（v2.19.7 用 12.dp 把 9.sp 的字裁掉下半截）"); fail = True

# v2.19.10 信息可读性（用户反馈"今日四十题，百分之七十"未明何意、
# "答对二十八·答错十二"亦未解其义）
_home4 = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/HomeScreen.kt")
# 每个百分数必须带主语，否则读者无从判断那是什么的比例
for _bare in ['Text(\n                                    "${(stats.todayCorrect * 100) / stats.todayAnswered}%"']:
    if _bare in _home4:
        print("[FAIL] 正确率必须带标签（用户反馈'百分之七十'未明何意）"); fail = True
if "正确率 ${(stats.todayCorrect * 100) / stats.todayAnswered}%" not in _home4:
    print("[FAIL] 今日正确率须写成『正确率 X%』——裸百分数读者无从判断是什么的比例"); fail = True
# 答对/答错必须标明是「今日」
if "今日答对 ${stats.todayCorrect}" not in _home4:
    print("[FAIL] 答对/答错须标明『今日』——用户反馈'答对二十八·答错十二'未解其义"); fail = True
# 里程碑注解挂在连击下，不得再放回底部脚注与答对/答错抢行
if 'modifier = Modifier.padding(top = 10.dp),\n                    ) {\n                        Text(\n                            if (stats.todayAnswered > 0) {\n                                "再练' in _home4:
    print("[FAIL] 里程碑注解应挂在连击下方，不应回到底部脚注行"); fail = True

_heat2 = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/StreakHeatmap.kt")
# 未来日须按 0 档绘制：只画到今天会让末列变成一根孤零零的竖条（用户反馈）
if "future" not in _heat2 or "val future = c.timeInMillis > startOfToday" not in _heat2:
    print("[FAIL] 未来日须按 0 档绘制（只画到今天会让末列脱节成孤条）"); fail = True
if "if (!future) todayIndex = cells.size" not in _heat2:
    print("[FAIL] 今天下标须按『最后一个非未来格』确定"); fail = True
# 左侧星期栏已按用户裁定移除
if "WeekdayLabels" in _heat2 or "HeatGutter" in _heat2:
    print("[FAIL] 左侧星期栏已按用户裁定移除（'似可有可无'），勿加回来"); fail = True

# v2.19.11：卡片高度、末列挤压、长按浮窗、底栏减效
_heat3 = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/StreakHeatmap.kt")
_home5 = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/HomeScreen.kt")
# ① 卡片过高 → 周数固定为 22（约 5 个月）。18 周网格高 113dp，26 周格子仅 8dp 太小。
if "HEATMAP_WEEKS = 22" not in _heat3:
    print("[FAIL] 热力图周数须为 22（18 周卡片过高，26 周格子过小）"); fail = True
# ② 末列被挤压 → 列宽必须「取整后由每一列自己声明」，不得靠 Row 自适应
if "floor(rawPitch)" not in _heat3 or "Modifier.width(pitch)" not in _heat3:
    print("[FAIL] 列宽须取整后由每列 width(pitch) 声明——靠 Row 自适应会压扁最后一列"
          "（v2.19.10『右侧一列狭长如被挤压』）"); fail = True
# ③ 详情浮窗：交互按图表惯例走**轻点**（不是长按），且渲染条件不得依赖自身尺寸
for _needle in ("detectTapGestures", "onTap", "DayTip", "正确率"):
    if _needle not in _heat3:
        print(f"[FAIL] 热力图缺详情浮窗要素：{_needle}"); fail = True
if "onLongPress" in _heat3:
    print("[FAIL] 浮窗交互须为轻点（图表惯例，触屏无 hover）；长按须移除"); fail = True
# v2.19.11 的鸡生蛋死锁：条件里要求 tipW>0 才渲染浮窗，而 tipW 只在渲染后才写入。
# 注释里复述这段代码是正常的，故对去注释后的源码做此项检查。
_heat3_code = re.sub(r"/\*.*?\*/", "", _heat3, flags=re.S)
_heat3_code = re.sub(r"//[^\n]*", "", _heat3_code)
if "tipIndex >= 0 && tipW > 0" in _heat3_code:
    print("[FAIL] 浮窗渲染条件不得依赖自身尺寸（v2.19.11 因此只有框没有窗）"); fail = True
if "Repo.HeatDay" not in _heat3 and "HeatDay" not in _heat3:
    print("[FAIL] 浮窗需含当日答对数，格子数据须带 correct"); fail = True
_repo_src = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/repo/Repo.kt")
if "data class HeatDay(val date: String, val answered: Int, val correct: Int)" not in _repo_src:
    print("[FAIL] Repo.HeatDay 须含 correct（长按浮窗要显示当日正确率）"); fail = True
if "heatmapDays(160)" not in _home5:
    print("[FAIL] 取数天数须覆盖周数×7（HEATMAP_WEEKS=22 → 至少 154 天）"); fail = True
# ④ 底栏不再有渐隐模糊（v2.19.12 已整体移除），本条随之作废

# 3.16) 每日提醒后台保活（v2.15.0 引入，v2.16.0 维持，v2.17.0 维持）：
#       每日提醒调度引擎维持 AlarmManager（WorkManager 全移除）：精确闹钟 + 开机重排 +
#          电池白名单请求 + Receiver 注册 + 权限齐全。
# 渐进式模糊的断言与禁令已移入 3.15（v2.17.0 起重新实现，路线改为分层固定半径模糊）
_softfade_hits = grep_hits("softTopFade")
if _softfade_hits:
    print(f"[FAIL] 旧顶部柔化函数必须彻底移除（v2.7.2 已裁定砍除），残留 {_softfade_hits}"); fail = True
_notify_src = load(_repo_root + "/app/src/main/java/com/drone/quiz/work/Notify.kt")
for _needle in ("setExactAndAllowWhileIdle", "setAndAllowWhileIdle", "canScheduleExactAlarms",
                "isIgnoringBatteryOptimizations", "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                "ACTION_BOOT_COMPLETED", "MY_PACKAGE_REPLACED", "goAsync", "RTC_WAKEUP"):
    if _needle not in _notify_src:
        print(f"[FAIL] 每日提醒闹钟引擎不完整：缺 {_needle}"); fail = True
if "androidx.work" in _notify_src:
    print("[FAIL] Notify.kt 不得再引用 WorkManager（调度引擎已换 AlarmManager）"); fail = True
_work_hits = grep_hits("androidx.work")
if _work_hits:
    print(f"[FAIL] WorkManager 引用残留（依赖已移除）：{_work_hits}"); fail = True
for _perm in ("RECEIVE_BOOT_COMPLETED", "USE_EXACT_ALARM", "SCHEDULE_EXACT_ALARM",
              "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"):
    if _perm not in _manifest_src:
        print(f"[FAIL] manifest 缺每日提醒后台保活权限：{_perm}"); fail = True
if "ReminderReceiver" not in _manifest_src or "android.intent.action.BOOT_COMPLETED" not in _manifest_src:
    print("[FAIL] manifest 缺 ReminderReceiver 注册（开机/升级重排失效）"); fail = True
_settings_src = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/SettingsScreen.kt")
if "requestRunInBackground" not in _settings_src:
    print("[FAIL] 设置页开启每日提醒须请求后台运行（电池优化白名单弹窗）"); fail = True
_main_src = load(_repo_root + "/app/src/main/java/com/drone/quiz/MainActivity.kt")
if "ReminderScheduler.schedule" not in _main_src:
    print("[FAIL] MainActivity 打开须自愈式补排每日提醒"); fail = True

# 3.17) 模考即时判定（v2.18.0 新增，可选制）：
#       「或全卷毕而统核正误，或逐题作而随验得失」——开关落在模考配置页高级选项，
#       默认关闭保持传统模考口径；开启后选完当场亮对错。
#       关键约束：**只改显示时机，不改判分口径**——题卡必须复用 judgeAnswer（与交卷同一函数），
#       不得另写一套判定，否则成绩单与当场反馈会打架。
_settings2 = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/settings/SettingsStore.kt")
for _needle in ("examInstantJudge", "exam_instant_judge", "setExamInstantJudge"):
    if _needle not in _settings2:
        print(f"[FAIL] 模考即时判定设置项不完整：SettingsStore 缺 {_needle}"); fail = True
_exam_src = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/ExamScreens.kt")
for _needle in ("即时判定", "setExamInstantJudge", "reveal = instantJudge"):
    if _needle not in _exam_src:
        print(f"[FAIL] 模考配置/考试页缺即时判定入口：{_needle}"); fail = True
if _exam_src.count("judgeAnswer") < 2:
    print("[FAIL] 题卡当场判定必须复用 judgeAnswer（与交卷判分同口径，禁止另写一套）"); fail = True
_uf_src = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/repo/QuestionFormats.kt")
if "val confirmed: Boolean = false" not in _uf_src:
    print("[FAIL] UserAnswer 缺 confirmed 字段（多选题点选是切换不是作答，需显式确认时刻）"); fail = True
# 即时判定不得侵入成绩单口径（submitExam 在 Repo.kt）
_repo_src2 = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/repo/Repo.kt")
_parts = _repo_src2.split("suspend fun submitExam")
if len(_parts) < 2:
    print("[FAIL] Repo.kt 找不到 submitExam（判分入口位置异常）"); fail = True
else:
    _submit_blk = "suspend fun submitExam".join(_parts[1:])
    if "examInstantJudge" in _submit_blk or "instantJudge" in _submit_blk:
        print("[FAIL] 即时判定开关不得影响 submitExam 判分/成绩单口径（仅改显示时机）"); fail = True

print("PASS" if not fail else "STATIC CHECK FAILED")
sys.exit(1 if fail else 0)
