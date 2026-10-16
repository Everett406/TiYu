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
# v2.19.24 起 gradle 里版本号可能是 `-PtiyuPreview=true` 的条件表达式（草稿预览包）。
# 正则须兼容两种写法：取**正式版**那一支（条件为 false / else 的那侧），
# 这样 static_check 校验的始终是要发的正式版本，而不是预览版本。
_vname = (_re.search(r'versionName\s*=\s*if\b[\s\S]*?"([^"]+)"\s*else\s*"([^"]+)"', _gradle_src)
          or _re.search(r'versionName\s*=\s*"([^"]+)"', _gradle_src))
_vcode = (_re.search(r'versionCode\s*=\s*if\b[\s\S]*?\s(\d+)\s+else\s+(\d+)', _gradle_src)
          or _re.search(r'versionCode\s*=\s*(\d+)', _gradle_src))
# gradle 里可能是 `-PtiyuPreview=true` 的条件表达式（草稿预览包），两个分支都可能有值：
#   group(1) = preview 分支（2.19.24-preview），group(2) = 正式分支（2.19.23）
# build.yml 侧若是 preview（名字带 -preview）就比对 group(1)，否则比对 group(2)。
_is_preview = "APP_VERSION_NAME:" in _wf_src and "-preview" in _wf_src.split("APP_VERSION_NAME:")[1].split("\n")[0]
_vi = 1 if _is_preview else 2
_vn = _vname.group(_vi) if _vname.re.groups >= 2 else _vname.group(1)
_vc = _vcode.group(_vi) if _vcode.re.groups >= 2 else _vcode.group(1)
# 预览包的版本名带 -preview 后缀（与 gradle 的 preview 分支对齐）
_wf_name = _re.search(r'APP_VERSION_NAME: ([\w.\-]+)', _wf_src)
_wf_tag = _re.search(r'APP_VERSION_TAG: v?([\w.\-]+)', _wf_src)
if not (_vname and _vcode and _wf_name and _wf_tag):
    print("[FAIL] 版本号解析失败（gradle / build.yml 格式变更，请修 3.9 节正则）"); fail = True
else:
    if not (_vn == _wf_name.group(1) == _wf_tag.group(1)):
        print(f"[FAIL] 版本号三处不一致: gradle={_vn} "
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
if "kotlin.math.floor(maxWidth.value / HEATMAP_WEEKS)" not in _heat3 or "Modifier.width(pitch)" not in _heat3:
    print("[FAIL] 列宽须取整后由每列 width(pitch) 声明——靠 Row 自适应会压扁最后一列"
          "（v2.19.10『右侧一列狭长如被挤压』）"); fail = True
# ③ 详情浮窗：交互按图表惯例走**轻点**（不是长按），且渲染条件不得依赖自身尺寸
#    箭头方向与浮窗坐标必须共用同一个 showBelow 判定（v2.19.12 二者各算各的，
#    窗被翻到下方而箭头仍朝下）
if "TooltipPositionProvider" not in _heat3 or "cellTop() - h - 10f" not in _heat3:
    print("[FAIL] 浮窗坐标与箭头方向须共用同一个 showBelow 判定"); fail = True
if "pitchPxOf" in _heat3 or "d.value * 3f" in _heat3:
    print("[FAIL] Dp→px 换算不得硬编码密度，须用 LocalDensity"); fail = True
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
# ④ 浮窗必须是**真·浮层**：用 Popup 独立窗口，不占布局、不被父级裁剪。
#    v2.19.12~v2.19.14 三版都把浮窗当网格 Box 的子节点、用 offset 摆位置，
#    于是陷入两难：窗高约 50dp 而格子只有 10dp，夹在网格内必然盖住旁边几列，
#    允许越界就会压住脚注、冲出卡片被圆角裁掉——那是架构选错，不是参数没调好。
for _needle in ("Popup(", "PopupPositionProvider", "dismissOnClickOutside"):
    if _needle not in _heat3:
        print(f"[FAIL] 详情浮窗须用 Popup 独立窗口浮在最上层（缺 {_needle}）"
              "——嵌在布局里的做法已被证伪三轮"); fail = True
if "tipW" in _heat3_code or "tipH" in _heat3_code:
    print("[FAIL] 浮窗尺寸不得自行测量（PopupPositionProvider 已给量好的尺寸，"
          "自行测量即回到鸡生蛋死锁）"); fail = True
if "clipToBounds" in _heat3_code:
    print("[FAIL] 网格区不得再靠 clipToBounds 兜底——浮窗已不在图里"); fail = True

# ⑤ 卡片高度须锁死：顶行两处注解、两个大数字、脚注全部 maxLines = 1，
#    文案长短不一（大字号/小屏下会换行）不得撑高整张卡
_home6 = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/HomeScreen.kt")
_blk = _home6[_home6.index("// ---- 顶行：连击（左） / 今日（右）----"):
              _home6.index("// ---- 18 周热力图")]
_foot = _home6[_home6.index("// ---- 脚注：主语写全的答对/答错 + 图例同处一行 ----"):
               _home6.index("// ---- 近 7 天图表 ----")]
if _blk.count("maxLines = 1") < 4:
    print("[FAIL] 打卡卡顶行四个文本须加 maxLines=1（文案长短不一会撑高卡片）"); fail = True
if "maxLines = 1" not in _foot:
    print("[FAIL] 打卡卡脚注须加 maxLines=1（『今日答对…』与『今天还没开始…』长短不一）"); fail = True

# ⑥ 底栏不再有渐隐模糊（v2.19.12 已整体移除），本条随之作废

# 选项圆标：字母必须真居中；判断题对勾/叉号必须自绘
_qb = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/common/QuestionBody.kt")
# Android includeFontPadding 默认 true（为 CJK 留白），会把 24dp 圆标里的字形整体压低
if "includeFontPadding = false" not in _qb:
    print("[FAIL] 选项圆标的字母须设 includeFontPadding=false —— Android 默认的 CJK "
          "字形留白会把字压在圆心下方（用户反馈『字母偏下』）"); fail = True
# 判断题 √/× 自绘；系统字符太细不好看。
# v2.19.36 起自绘字形提到 JudgeGlyph.kt 共用（搜索页也要同一套），
# 所以这里在 QuestionBody + JudgeGlyph.kt 两处合起来找。
_jg2 = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/common/JudgeGlyph.kt")
_qb2 = _qb + _jg2
for _needle in ("JudgeBadgeGlyph", "drawPath(path, color)", "JUDGE_CHECK", "JUDGE_CROSS"):
    if _needle not in _qb2:
        print(f"[FAIL] 判断题圆标须自绘且用**填充多边形**（缺 {_needle}）——"
              "描边折线在真机上『极丑而且不居中』：包围盒居中≠视觉重心居中"); fail = True
_fmt = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/repo/QuestionFormats.kt")
if 'internal const val JUDGE_CHECK = "√"' not in _fmt:
    print("[FAIL] JUDGE_CHECK/JUDGE_CROSS 须与 optionLabel 同源定义，避免标签与自绘分支漂移"); fail = True

# v2.19.18：抽题随机性 + 考试页与刷题页对齐
_repo2 = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/repo/Repo.kt")
_start = _repo2[_repo2.index("suspend fun startExam("):]
_start = _start[:_start.index("suspend fun startExam(", 10)] if "suspend fun startExam(" in _start[10:] else _start
if "sortedBy { typeRank[it.type]" not in _start:
    print("[FAIL] startExam 须用稳定排序只按题型分组——追加 `, { it.id }` 会把"
          "shuffled 后的组内顺序重排回 id 升序，用户反馈『抽题不太随机』"); fail = True
if "sortedWith(compareBy({ typeRank[it.type] ?: 99 }, { it.id }))" in _start:
    print("[FAIL] startExam 不得再按 id 次序排序（会抹掉随机顺序）"); fail = True

_exam = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/ExamScreens.kt")
if "OptionRow(" not in _exam:
    print("[FAIL] 考试页单选/判断须复用共用 OptionRow —— 内联自绘会导致："
          "判断题自绘勾叉不生效、无答错抖动动画、字号与刷题页不同步"); fail = True
# 判定结果必须在**作答区之后**
_vpos = _exam.find("ResultHeader(")
_opos = _exam.find("OptionRow(")
if _vpos < 0 or (_opos > 0 and _vpos < _opos):
    print("[FAIL] 判定结果与解析须排在作答区**之后**（用户反馈『排版上把解析放到了前面』）"); fail = True
if "ParseBlock(q.explanation)" not in _exam:
    print("[FAIL] 考试页解析须用共用 ParseBlock（与刷题页同构）"); fail = True

# v2.19.19：即时判定开关必须真的生效；设置项副标题一律单行
_exam2 = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/ExamScreens.kt")
# v2.19.18 照搬刷题页的 `ua == null` 判据，但两页 ua 语义不同：
# 刷题页 ua 是「提交后才写入」，模考页 ua 是「点一下就写入」——于是关掉即时判定
# 也会当场点亮正确答案。且 enabled = ua == null 把「可改选」也一并禁掉了。
# v2.19.22：这条禁令本身在 v2.19.22 被修正过一次。它当年只认「三态写法」，
# 而三态写法把『选中态』和『判定态』挤在一个变量里，一关判定选中态就没了
# （用户报告：关掉即时判定后点选项毫无反应）。现在要求**四态**：
#   verdict != null → 才判对错（绿/红）；否则 ua.picked → 已选中（state 3）。
# 意图不变：对错只能由 verdict 决定，绝不能用 ua 直接点亮正确答案。
if "verdict != null -> when {" not in _exam2 or "i == ua.picked -> 3" not in _exam2:
    print("[FAIL] 模考选项 state 须为四态：对错由 verdict 决定，未判定只显『已选中』—— "
          "绝不能只用 ua 判对错（会当场亮答案），也不能退回三态（选中态会消失）")
    fail = True
# v2.19.22：这条禁令本身被修正过两次，教训记在这里。
#   v2.19.18 传 enabled = ua == null → 关判定时也锁死，用户报"点不了"；
#   v2.19.19 改成写死 enabled = true → 开判定时能反复改，用户裁定不对
#            （口径：「选了就是选了，然后只能下一题」）。
# 正确判据是 **是否已判定**：`enabled = !judged`，即 verdict != null 才锁。
# 既不能拿 ua 当锁的判据（未判定就被锁死），也不能写死 true（已判定仍能改）。
if "enabled = !judged," not in _exam2:
    print("[FAIL] 模考单选/判断锁定判据须为 enabled = !judged——"
          "判定后锁定只能下一题；未判定（含关掉即时判定）仍可改选。"
          "既不能写死 true，也不能拿 ua 当判据"); fail = True
# 设置项副标题：单行 + 省略号兜底，且不得再用长句
if "overflow = TextOverflow.Ellipsis" not in _exam2:
    print("[FAIL] 模考配置项副标题须加 TextOverflow.Ellipsis —— 用户反馈长副标题换行很难受"); fail = True
for _long in ("逐题作答，当场验正误；已判定的题不再改动", "全卷交卷后统核正误（传统模考口径）",
              "简答题提交后对照参考答案自评计分", "题型顺序 · 长按拖动调整（先做哪种题型）"):
    if _long in _exam2:
        print(f"[FAIL] 配置项副标题须 ≤10 字，长句不得保留：{_long}"); fail = True

# 底栏模糊档位：同一存储值在两种画面特效下含义不同，改一处必须同步三处
_set = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/SettingsScreen.kt")
_bar = load(_repo_root + "/app/src/main/java/com/drone/quiz/ui/glass/GlassBottomBar.kt")
_store = load(_repo_root + "/app/src/java/../java/com/drone/quiz/data/settings/SettingsStore.kt") \
    if False else load(_repo_root + "/app/src/main/java/com/drone/quiz/data/settings/SettingsStore.kt")
if '"底栏玻璃模糊"' in _set:
    print("[FAIL] 设置项标题应为『底栏模糊』（用户要求去掉『玻璃』二字）"); fail = True
if '"底栏模糊"' not in _set:
    print("[FAIL] 找不到『底栏模糊』设置项"); fail = True
# 亚克力维持低/中/高；液态玻璃为 关/轻/重
if 'if (settings.effects) listOf("关", "轻", "重")' not in _set:
    print("[FAIL] 液态玻璃下档位名须为 关/轻/重（含一档纯玻璃）；亚克力维持 低/中/高"); fail = True
if 'else listOf("低", "中", "高")' not in _set:
    print("[FAIL] 亚克力下档位名须维持 低/中/高 不变"); fail = True
# 底栏须按 effects 分别解释同一档位，且零模糊时不挂 blur 效果
if "if (glassSettings.effects)" not in _bar:
    print("[FAIL] GlassBottomBar 的模糊档位须按 glassSettings.effects 分别解释"); fail = True
if "if (barBlurPx > 0f) blur(barBlurPx)" not in _bar:
    print("[FAIL] 液态玻璃『关』档须完全不放 blur 效果，退化为纯玻璃"); fail = True
if "blur(barBlurDp.toPx())" in _bar:
    print("[FAIL] 不得再无条件挂 blur(barBlurDp)（关档会退化成极轻模糊而非纯玻璃）"); fail = True
if "亚克力（effects=false）：" not in _store:
    print("[FAIL] SettingsStore.glassBlur 注释须写明两种特效下的不同解释，"
          "避免后续改动只改一半"); fail = True

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


# 4.x) 「更换壁纸」二级面板残留禁令（v2.19.21：用户反馈不需要，整体撤回）
#      v2.19.20 曾在「更换壁纸」按钮下加二级面板（相册 / 同步系统壁纸）。
#      用户要求改回最直接的做法：点按钮直接开系统相册选择器，多一次点击没意义。
#      同步系统壁纸还额外要求「所有文件访问权限」——为一个可有可无的功能要这种
#      敏感权限不划算，故清单也一并撤了。以下 token 若再次出现即为回归。
_sheet_hits = grep_hits("WallpaperSourceRow")
if _sheet_hits:
    print("[FAIL] 「更换壁纸」二级面板已被撤回（用户要求直接开相册），不得复活：")
    for _h in _sheet_hits[:5]:
        print("   ", _h)
    fail = True
_manifest = load(_repo_root + "/app/src/main/AndroidManifest.xml")
if "MANAGE_EXTERNAL_STORAGE" in _manifest:
    print("[FAIL] 清单里不得声明 MANAGE_EXTERNAL_STORAGE（v2.19.21 已随二级面板撤回）")
    fail = True


# 4.y) 模考单选/判断「已选中未判定」态残留禁令（v2.19.22）
#      用户报告：关掉模考「即时判定」后点选项毫无反应，误以为点不动。
#      根因是 OptionRow.state 只有 0/1/2 三态，判定一关 verdict 恒 null，
#      state 跟着恒 0，选中态被一并抹掉。三处必须同时在位：
#        1) OptionRow 注释里声明 3 态
#        2) optionColors 有 3 分支
#        3) 模考 state 计算有 `i == ua.picked -> 3`
_exam = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/ExamScreens.kt")
_qb = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/common/QuestionBody.kt")
for _tok, _where, _txt in [
    ("i == ua.picked -> 3", _exam, "模考单选/判断 state 计算缺『已选中未判定』分支"),
    ("3 -> ui.ink.copy(alpha = 0.10f) to ui.ink.copy(alpha = 0.35f)", _qb,
     "optionColors 缺 state 3（已选中未判定）配色"),
    ("3 -> ui.ink                      // 已选中未判定：实心 ink 底 + 白字", _qb,
     "OptionBadge 缺 state 3 徽标底色"),
]:
    if _tok not in _where:
        print("[FAIL] " + _txt)
        fail = True


# 4.z) 多选「漏选 / 错选」四态残留禁令（v2.19.23）
#      用户反馈：勾了 A、B、C 漏了 D，四个选项却全标"正确"，完全看不出漏在哪。
#      根因：多选 resultState 把「是正确答案」一律记 1，没区分"选没选"。
#      现在必须四态（1 正确 / 2 错选 / 3 漏选），且 3 要用 accent 琥珀色——
#      用绿会和"正确"混、用红会被误读成"你选错了"。
_pr = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/PracticeScreen.kt")
_qb2 = load(_repo_root + "/app/src/java/com/drone/quiz/screens/common/QuestionBody.kt")
_qf = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/repo/QuestionFormats.kt")
for _tok, _src, _msg in [
    ("isKey -> 3", _pr, "刷题页多选 resultState 缺『漏选』分支（该选没选也显示『正确』）"),
    ("3 -> Text(\"漏选\"", _qb2, "多选选项行缺『漏选』标签"),
    ('2 -> Text("错选"', _qb2, "多选错选标签须叫『错选』（旧文案『你选的』看不出是选错了）"),
    ("3 -> ui.accent.copy(alpha = 0.12f) to ui.accent.copy(alpha = 0.55f)", _qb2,
     "多选漏选须用 accent 琥珀色（绿会和正确混、红会被读成你选错了）"),
    ("fun pickedAnswerText(", _qf, "缺位掩码→字母的反解工具（多选『你的答案：A、B、C』要用）"),
]:
    if _tok not in _src:
        print("[FAIL] " + _msg)
        fail = True
# 6.x) 可空作答不得用 `!!` 强解（v2.19.24 崩溃修复）
#      用户真机崩溃报告：
#        java.lang.NullPointerException
#          at PracticeScreenKt.MultiSection(PracticeScreen.kt:943)
#          ← PagerState.scrollToPage ← PracticeScreen.kt:326
#      触发路径：切换题库 → 刷另一题库 → 滚到**未作答的多选题**必崩。
#      根因：v2.19.23 把多选改成四态时，原本藏在 `else if (!submitted)` 分支里的
#      `ua!!.picked` 被提到了选项渲染循环顶部，**挪出了 submitted 的保护范围**——
#      未作答时 ua 为 null，而循环照跑。这是最典型的重构事故：保护圈被挪破了。
#      编译与 static_check 全绿，只有真机能撞上（切题库 → details 清空 → 滚到多选题）。
#
#      还有一类同源隐患：`AnimatedVisibility(visible = submitted)` 的 content
#      在**退出动画期间仍然组合**，此时 visible 已 false 而 ua 可能已是 null，
#      所以连"看起来在保护里的" `ua!!` 也不能用。
for _f in ["screens/PracticeScreen.kt", "screens/ExamScreens.kt"]:
    _src = load(_repo_root + "/app/src/main/java/com/drone/quiz/" + _f)
    for _ln, _l in enumerate(_src.split("\n"), 1):
        _c = _l.split("//")[0]
        if "ua!!" in _c:
            print(f"[FAIL] {_f}:{_ln} 可空作答不得用 ua!! —— 未作答或退出动画期间 ua 可能为 null"
                  f"（v2.19.24 崩溃）: {_l.strip()[:60]}")
            fail = True


# 7.x) 星空按钮底纹（v2.19.26）
#      墨色主按钮改用星空纹理。三个渲染分支（亚克力 / 材质 / 真折射）都必须处理，
#      漏一个就会出现「某种模式下按钮没星空」的不一致——所以三条都锁。
_tex = _repo_root + "/app/src/main/res/drawable-nodpi/btn_starfield.jpg"
import os as _os
if not _os.path.exists(_tex):
    print("[FAIL] 星空纹理 btn_starfield.jpg 缺失（drawable-nodpi/）——"
          "墨色主按钮会退回纯色块"); fail = True
_gk = load(_repo_root + "/app/src/main/java/com/drone/quiz/ui/glass/GlassKit.kt")
# 必须等比裁切：横向拉宽的按钮会把星点拉成椭圆，一眼穿帮
if "max(size.width / bmpW, size.height / bmpH)" not in _gk:
    print("[FAIL] 星空底纹必须等比裁切（Cover + 居中平移），不得横向拉伸")
    fail = True
# 判据必须是「surfaceColor == ui.ink」而不是新增参数：全 app 17 处墨色按钮自动一致
if "surfaceColor == ui.ink" not in _gk:
    print("[FAIL] 星空底纹判据应为 surfaceColor == ui.ink（深色模式 ui.ink 是浅米色，"
          "判据天然不成立，故深色模式仍走浅底深字）"); fail = True
if _gk.count("drawStarfieldCropped(") < 4:
    print("[FAIL] 星空底纹须在三个渲染分支都绘制（亚克力 / 材质 / 真折射），"
          "漏一个会出现模式间不一致"); fail = True


# 7.y) 强调底纹理覆盖范围（v2.19.28）
#      用户反馈：黑色按钮之外的黑色大面积元素（分段控件滑块、筛选 chip、开关轨道）
#      也要同样的纹理；深色模式下强调底是浅色，于是又备了一张浅色水磨石。
#      现在统一走 `ui.ink` 判据 + `rememberAccentTexture()`（按主题自动选深/浅那张），
#      下列五类组件必须都接上，漏一处就会出现视觉语言不一致。
_tex_dark = _repo_root + "/app/src/main/res/drawable-nodpi/btn_starfield.jpg"
_tex_light = _repo_root + "/app/src/main/res/drawable-nodpi/btn_paper.jpg"
import os as _os2
for _p, _n in [(_tex_dark, "星空（浅色主题强调底）"), (_tex_light, "水磨石（深色主题强调底）")]:
    if not _os2.path.exists(_p):
        print(f"[FAIL] 强调底纹理缺失：{_n}")
        fail = True
_st = load(_repo_root + "/app/src/main/java/com/drone/quiz/ui/glass/SurfaceTexture.kt")
if "if (ui.isDark) R.drawable.btn_paper else R.drawable.btn_starfield" not in _st:
    print("[FAIL] 强调底纹理须按主题选择：深色 → btn_paper（浅）�� 浅色 → btn_starfield（深）")
    fail = True
if "max(size.width / bmpW, size.height / bmpH)" not in _st:
    print("[FAIL] 强调底纹理必须等比裁切（Cover + 居中），不得横向拉伸")
    fail = True
# 五类组件：谁漏了就报谁的
for _f, _tok, _who in [
    ("ui/glass/GlassKit.kt", "drawAccentTexture(accentTex)", "GlassButton 主按钮"),
    ("ui/glass/GlassKit.kt", "drawAccentTexture(accentTex, alpha = t)", "GlassToggle 开关轨道"),
    ("screens/common/Common.kt", "accentTexture(rememberAccentTexture())", "SegmentedRow 滑动指示块"),
    ("screens/PracticeConfig.kt", "Modifier.accentTexture(accentTex)", "ConfigChip 选中态"),
    ("screens/WrongBookScreen.kt", "Modifier.accentTexture(accentTex)", "FilterChip 选中态"),
]:
    _src = load(_repo_root + "/app/src/main/java/com/drone/quiz/" + _f)
    if _tok not in _src:
        print(f"[FAIL] {_who} 未接入强调底纹理（{_f}）")
        fail = True

# 7.z) 模考选项打乱（v2.19.33）
#      v2.11.0 做过一次、被整体 revert：当时「打乱」与「显示解析」共存，
#      而解析文字里写死了「选C」这类原始选项字母，乱序后必然对不上。
#      本版按用户新口径重做——**打乱即隐藏解析文字**，且与即时判定完全解耦
#      （判定与正确答案照常给，只撤掉 ParseBlock）。
#      结构性保证在于「视图层乱序、数据层恒原始空间」：
#      判分/错题本/统计只见原始下标，靠的是 uaToOriginal 这个唯一出口。
#      一旦有人改成直接拿显示空间的 ua 去落库或判分，整套答案就会错位——
#      而这种错位编译期与运行期都不报错，只有用户对答案才发现。所以锁死。
_osrc = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/ExamScreens.kt")
_fsrc = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/repo/OptionShuffle.kt")
_rsrc = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/repo/Repo.kt")

if not _fsrc:
    print("[FAIL] OptionShuffle.kt 缺失——模考选项打乱依赖它（视图/原始空间双射换算）")
    fail = True
else:
    for _tok, _why in [
        ("fun permOf", "排列生成"),
        ("fun display", "显示空间重排"),
        ("fun uaToOriginal", "显示→原始换算（落库/判分的唯一出口）"),
        ("fun uaToDisplay", "原始→显示（已答高亮回显）"),
        ("fun newSalt", "会话盐"),
    ]:
        if _tok not in _fsrc:
            print(f"[FAIL] OptionShuffle 缺 {_tok}（{_why}）")
            fail = True
    # 判断题不打乱：位置语义固定，全 app 约定 A=正确 B=错误
    if "QuestionTypes.JUDGE" not in _fsrc:
        print("[FAIL] OptionShuffle 必须显式排除判断题（正确/错误位置语义固定，打乱会破坏判分与错题本呈现）")
        fail = True

# 换算出口必须真的在作答回调里，且判分/落库用原始空间
for _tok, _why in [
    ("OptionShuffle.uaToOriginal(uaShownUp, rawQ, perm)", "作答回调必须先把显示空间换回原始空间"),
    ("judgeAnswer(rawQ, ua)", "判分必须用原始题目 rawQ，不能用乱序后的显示题"),
    ("saveExamAnswer(examId, rawQ.id, ua, correct)", "落库必须用原始题目 id"),
    ("OptionShuffle.uaToDisplay(details[rawQ.id], rawQ, perm)", "已答高亮需把原始作答映射到显示位置"),
    ("if (!hideExplanation) ParseBlock", "打乱生效时必须隐藏解析（否则解析里的原始字母与乱序选项对不上）"),
]:
    if _tok not in _osrc:
        print(f"[FAIL] 模考选项打乱：{_why}（缺 {_tok}）")
        fail = True

# 即时判定与打乱必须解耦：不得出现「开打乱就强制关即时判定」的联动
if "setExamInstantJudge(false)" in _osrc:
    print("[FAIL] 打乱开关不得强制关闭即时判定——两者完全解耦，判定与正确答案照常，只隐藏解析文字")
    fail = True

# 盐要落库，否则进程重启后恢复考试会变成原序
if "optSalt = if (shuffleOptions) OptionShuffle.newSalt() else 0L" not in _rsrc:
    print("[FAIL] startExam 须把会话盐写入 exam_records.optSalt（恢复考试据此还原相同选项顺序）")
    fail = True

# 7.z2) 发展历程页（v2.19.34）
#      数据表 AppHistory.kt 由 scripts/gen_history.py 从 git log 生成，
#      若两者脱节，页面就会开始讲历史里没有的事——比不写还糟。
#      static_check 无 git 环境时跳过比对（--check 单独在发版流程里跑）。
_hsrc = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/HistoryScreen.kt")
if not _hsrc:
    print("[FAIL] HistoryScreen.kt 缺失——关于页「历程」入口会跳空")
    fail = True
else:
    for _tok, _why in [
        ("AppHistory.ALL", "时间轴数据源"),
        ("HistoryScreen(backdrop: Backdrop", "须传 backdrop，否则玻璃卡没有折射层"),
    ]:
        if _tok not in _hsrc:
            print(f"[FAIL] 发展历程页：{_why}（缺 {_tok}）")
            fail = True
_dsrc = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/AppHistory.kt")
if not _dsrc:
    print("[FAIL] AppHistory.kt 缺失——请跑 python3 scripts/gen_history.py")
    fail = True
else:
    # 单选正确答案只给字母（v2.19.34 用户反馈：选项已在上方摆着，不必再抄一遍选项全文）
    _r2 = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/repo/Repo.kt")
    if "fun correctAnswerText(brief: Boolean = false)" not in _r2:
        print("[FAIL] correctAnswerText 缺 brief 参数——判定区会继续把选项全文抄一遍")
        fail = True
    # 搜索页折叠态只显示题干、选项看不到，那里必须保留全文
    _ss = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/SearchScreen.kt")
    if "correctAnswerText(brief = true)" in _ss:
        print("[FAIL] 搜索页折叠态不给选项，只写字母用户无从对照，须保留全文（brief 不要传 true）")
        fail = True

# 7.z3) 模考抽题优先级（v2.19.35 错题优先）
#      抽题在 startExam 里内联（要拿 DAO），单测复刻了同一套逻辑；
#      两边若不一致，单测就锁不住真实行为——所以核对优先级顺序的关键标记。
_r3 = load(_repo_root + "/app/src/main/java/com/drone/quiz/data/repo/Repo.kt")
if not _r3:
    print("[FAIL] Repo.kt 读不到")
    fail = True
else:
    # 优先级必须严格是 错题 → 未答 → 其余；顺序反了就是行为回退
    _i_wrong = _r3.find("for (pool in listOf(wrong, unseen, rest.toSet()))")
    if _i_wrong < 0:
        print("[FAIL] startExam 的逐级回填循环缺失或结构已变（期望 listOf(wrong, unseen, rest.toSet())）")
        fail = True
    # 错题本查询必须走 wDao（错题相关 DAO 挂在 WrongDao 上，用 qDao 编译不过，
    # 但改成别的名字仍会静默拿到空集 —— 这条防的是后者）
    if "wDao.activeWrongIds(bankId, type, null)" not in _r3:
        print("[FAIL] 错题优先必须用 wDao.activeWrongIds 取题（错题查询在 WrongDao 上）")
        fail = True
    # removed=1 的不算错题（连续答够次数已毕业）
    if "preferWrong: Boolean = false" not in _r3:
        print("[FAIL] startExam 缺 preferWrong 参数（错题优先开关）")
        fail = True
# 两个优先级开关的说明文案须互不矛盾：错题开启时未答开关要说明自己在"补位"
_e3 = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/ExamScreens.kt")
if "preferWrong ->" not in _e3:
    print("[FAIL] 模考配置页：错题优先开启时，「优先未答」的说明文案未切换为补位提示（用户会以为两个都抢第一）")
    fail = True

# 7.z4) 选项圆标居中 + 搜索页答案精简（v2.19.36）
#      字母在圆圈里偏下的根因：Android 的 PlatformTextStyle.includeFontPadding
#      默认给 Text 补上下行距，行盒比字号高，圆圈按行盒居中 → 字形视觉偏下。
#      v2.19.16 在 QuestionBody 修过一次（lineHeight 收到同字号 + 关 fontPadding），
#      搜索页那段是同一份代码的另一份拷贝，当时漏了——本次一并修并抽成共享字形。
_ccc = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/common/QuestionBody.kt")
_sc = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/SearchScreen.kt")
_jg = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/common/JudgeGlyph.kt")
_hs = load(_repo_root + "/app/src/main/java/com/drone/quiz/screens/HistoryScreen.kt")

for _f, _src, _who in [("QuestionBody.kt", _ccc, "刷题"), ("SearchScreen.kt", _sc, "搜索页")]:
    if "includeFontPadding = false" not in _src:
        print(f"[FAIL] {_who}选项圆标的字母未关 includeFontPadding——字形在圆圈里会偏下（v2.19.16 修过一次，勿回退）")
        fail = True
if not _jg or "fun JudgeBadgeGlyph" not in _jg:
    print("[FAIL] JudgeBadgeGlyph.kt 缺失——判断题圆标自绘字形的共用实现")
    fail = True
else:
    for _f, _src, _who in [("QuestionBody.kt", _ccc, "刷题页"), ("SearchScreen.kt", _sc, "搜索页")]:
        if "JudgeGlyph(" in _src.replace("JudgeBadgeGlyph(", ""):
            print(f"[FAIL] {_who}仍在引用旧的私有 JudgeGlyph（应统一用 JudgeBadgeGlyph）")
            fail = True

# 搜索页折叠态：答案只给字母 + 限一行。原样带全文会重复抄选项并把卡片撑高。
if "correctAnswerText(brief = true)" not in _sc:
    print("[FAIL] 搜索页折叠态的答案须用 brief=true（用户反馈：重复抄选项导致页面高度不断增加）")
    fail = True
if "maxLines = 1, overflow = TextOverflow.Ellipsis,\n                    modifier = Modifier.weight(1f, fill = false)" not in _sc:
    print("[FAIL] 搜索页折叠态答案须限一行（长选项文字会横向撑高卡片）")
    fail = True

# 历程页：节点去橙 + 展开动画 + 自动生成
if _hs:
    if "ui.accent" in _hs:
        print("[FAIL] 历程页时间轴节点不要用 ui.accent 橙（用户反馈太跳，压过内容）")
        fail = True
    if "enter = expandVertically() + fadeIn()" not in _hs:
        print("[FAIL] 历程页展开须有动画（须与错题本同款 expandVertically + fadeIn）")
        fail = True
_wf = load(_repo_root + "/.github/workflows/build.yml")
if "python3 scripts/gen_history.py" not in _wf:
    print("[FAIL] 发版 workflow 须自动重跑 gen_history.py（用户反馈：不要写死、要自动拉）")
    fail = True
elif "fetch-depth: 0" not in _wf:
    print("[FAIL] 发版 workflow 的 checkout 须 fetch-depth: 0——默认浅克隆只有最后一个提交，历程会只剩一条")
    fail = True
