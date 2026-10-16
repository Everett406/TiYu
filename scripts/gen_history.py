#!/usr/bin/env python3
"""从 git 历史生成题屿发展历程数据表（app/src/main/java/com/drone/quiz/data/AppHistory.kt）。

每次发版跑一次：历史变了、这张表也跟着变，不会和现实脱节。

用法：python3 scripts/gen_history.py [--check]
  --check  只比对现有文件是否为最新（CI 用；不一致则退出码 1）
"""
import collections
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/java/com/drone/quiz/data/AppHistory.kt")

HEADER = '''package com.drone.quiz.data

/**
 * 题屿发展历程（由 scripts/gen_history.py 从 git 历史自动生成，勿手改）。
 *
 * 数据来源：git log 全量提交，按 commit message 里的版本号归并。
 * [points] 是该版本提交信息里的要点，逐条摘出；不是二次加工的功能宣传。
 */
object AppHistory {
    data class Entry(
        val version: String,
        val date: String,      // yyyy-MM-dd（该版本首个提交日）
        val endDate: String,   // 该版本最后一个提交日
        val title: String,
        val points: List<String>,
        val commits: Int,
        val reverted: Boolean
    )

    /** 从新到旧排列（时间轴由近及远看） */
    val ALL: List<Entry> = listOf(
'''

FOOTER = '''    )

    val FIRST_DATE: String = ALL.last().date
    val LAST_DATE: String = ALL.first().date
    val VERSION_COUNT: Int = ALL.size
    val COMMIT_COUNT: Int = ALL.sumOf { it.commits }
}
'''


def git(*args):
    return subprocess.run(
        ["git", "-C", ROOT, *args], capture_output=True, text=True, check=True
    ).stdout


def bullets(body):
    out = []
    for line in body.split("\n"):
        line = line.strip()
        if not line:
            continue
        if line.startswith(("-", "*", "·", "#")):
            out.append(re.sub(r"^[-*·#\s]+", "", line))
        elif re.match(r"^\d+[.、)]", line):
            out.append(re.sub(r"^\d+[.、)]\s*", "", line))
    return out


# 历史里记着"删掉 X"，而 static_check 正在扫"X 是否残留"——
# 生成物原样带着那句话，会把检查自己绊倒（真出现过：v2.11.2 删果冻动效的
# 提交信息里带着 ui/gooey/ 路径，直接让 gooey 残留检查失败）。
# 这类「已删除对象」在展示层没有意义，生成时直接脱敏。
DELETED_HISTORY = re.compile(
    r"(ui/gooey/[A-Za-z]+\.kt|com\.drone\.quiz\.ui\.gooey\b|\bGooey[A-Za-z]*\b|\bgooey\b)",
    re.I,
)


def clean(s, n):
    s = re.sub(r"\s+", " ", s).strip()
    s = DELETED_HISTORY.sub("（已删除的组件）", s)
    return s if len(s) <= n else s[:n].rstrip() + "…"


def title_of(s):
    s = re.sub(r"^(v?\d[\w.\-]*\s*[:：]\s*)", "", s.strip())
    s = re.sub(
        r"^(fix|feat|ci|chore|docs|perf|refactor|test)(\([^)]*\))?\s*[:：]\s*",
        "",
        s,
        flags=re.I,
    )
    return DELETED_HISTORY.sub("（已删除的组件）", clean(s, 90))


def esc(s):
    return (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\n", " ")
        .replace("$", "\\$")
    )


def build():
    raw = git("log", "--reverse", "--format=@@@%H|%ad|%s", "--date=format:%Y-%m-%d")
    recs = []
    for chunk in raw.split("@@@"):
        chunk = chunk.strip()
        if not chunk:
            continue
        sha, date, subj = chunk.split("|", 2)
        body = git("log", "-1", "--format=%b", sha).strip()
        recs.append((sha, date, subj, body))

    byver = collections.OrderedDict()
    cur = "2.0"
    for sha, date, subj, body in recs:
        m = re.search(r"(?:^|[\s:(])(v?\d+\.\d+\.\d+(?:-preview)?)[\s:,)]", subj)
        ver = None
        if m and not re.search(
            r"shapes|kyant|backdrop|compileSdk|AGP|gradle|compose|room|AGSL",
            subj,
            re.I,
        ):
            ver = m.group(1).lstrip("v")
        if ver:
            cur = ver
        byver.setdefault(cur, []).append(
            dict(sha=sha[:7], date=date, subj=subj, pts=bullets(body))
        )

    lines = [HEADER]
    for ver, its in reversed(list(byver.items())):
        main = max(its, key=lambda x: (0 if re.match(r"^v?\d", x["subj"]) else 1, -len(x["subj"])))
        pts = []
        for it in its:
            for p in it["pts"]:
                if p not in pts:
                    pts.append(p)
        rev = "true" if any("revert" in i["subj"].lower() for i in its) else "false"
        points = ", ".join(f'"{esc(clean(p, 150))}"' for p in pts[:7])
        lines.append(
            f'        Entry("{esc(ver)}", "{its[0]["date"]}", "{its[-1]["date"]}", '
            f'"{esc(title_of(main["subj"]))}",'
        )
        lines.append(f"            listOf({points}), {len(its)}, {rev}),")
    lines.append(FOOTER)
    return "\n".join(lines), len(byver), sum(len(v) for v in byver.values())


def main():
    content, nver, ncommit = build()
    if "--check" in sys.argv:
        cur = open(OUT, encoding="utf-8").read() if os.path.exists(OUT) else ""
        if cur != content:
            print("[FAIL] AppHistory.kt 与 git 历史不符，请跑 python3 scripts/gen_history.py")
            return 1
        print(f"[OK] AppHistory.kt 已是最新（{nver} 版本 / {ncommit} 提交）")
        return 0
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"已生成 {OUT}（{nver} 个版本 / {ncommit} 个提交）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
