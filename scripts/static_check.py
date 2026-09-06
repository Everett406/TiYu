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
    ("IntrinsicSize.Min", "双卡等高"),
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
    ("GooeyContainer", "果冻 gooey 融合容器(v2.9.3)"),
    ("createChainEffect", "gooey 官方链式管线 blur→阈值(v2.9.3)"),
    ("MODE_GOOEY", "特效三级体系·果冻模式(v2.9.3)"),
    ("acrylicMaterial", "亚克力表面材质·只模糊无折射(v2.9.3)"),
    ("rememberReducedMotion", "系统减弱动画时只去果冻动效(v2.9.3)"),
    ("GOOEY_SRC", "gooey AGSL alpha 阶跃着色器(v2.9.3)"),
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

print("PASS" if not fail else "STATIC CHECK FAILED")
sys.exit(1 if fail else 0)
