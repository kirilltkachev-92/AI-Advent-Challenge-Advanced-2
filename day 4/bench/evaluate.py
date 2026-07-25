#!/usr/bin/env python3
"""
Оценка ответов бенча.

Задача A: извлечь === FILE: === блоки → собрать песочный Gradle-проект
(обвязка из day 1: wrapper, settings) → ./gradlew compileKotlin.
Компилируем дважды: с build.gradle.kts модели и, если он падает/отсутствует,
с эталонным build-файлом day 1 — чтобы отделить «кривой build-скрипт»
от «кривого кода».

Задача B: извлечь исправленный HistoryStore.kt → подставить в копию day 1
(состояние до фикса нам не нужно — важно, компилируется ли и переживает ли
тест HistoryStoreTest, включая capacity<=0).

Запуск: python3 evaluate.py [--models ...] ; результат — out/eval.json
"""
import argparse
import json
import pathlib
import re
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT / "out"
REPO = ROOT.parent.parent
DAY1 = REPO / "day 1"
SANDBOX = pathlib.Path(
    "/private/tmp/claude-501/-Users-kirill-Documents-AI-Advent-Challenge-Advanced-2/"
    "fcd67035-8a19-40e3-b66c-7355fdcf4b5d/scratchpad/bench-sandbox")
JAVA_HOME = "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"

FILE_RE = re.compile(r"=== FILE: (.+?) ===\n(.*?)\n=== END ===", re.S)


def extract(text):
    files = {}
    for path, body in FILE_RE.findall(text):
        body = re.sub(r"^```[a-z]*\n|```$", "", body.strip(), flags=re.M)
        files[path.strip()] = body + "\n"
    return files


def gradle(project, *targs):
    return subprocess.run(
        ["./gradlew", *targs, "--console=plain", "-q"],
        cwd=project, capture_output=True, text=True, timeout=600,
        env={"PATH": "/usr/bin:/bin:/usr/sbin", "HOME": str(pathlib.Path.home()),
             "JAVA_HOME": JAVA_HOME},
    )


def scaffold(dst, with_reference_build):
    dst.mkdir(parents=True, exist_ok=True)
    for item in ["gradlew", "gradlew.bat", "gradle"]:
        src = DAY1 / item
        if src.is_dir():
            shutil.copytree(src, dst / item, dirs_exist_ok=True)
        else:
            shutil.copy2(src, dst / item)
    (dst / "settings.gradle.kts").write_text('rootProject.name = "bench"\n')
    if with_reference_build:
        shutil.copy2(DAY1 / "build.gradle.kts", dst / "build.gradle.kts")


def eval_task_a(name):
    text = (OUT / f"{name}-taskA.md").read_text()
    files = extract(text)
    kt = {p: b for p, b in files.items() if p.endswith(".kt")}
    res = {"files": len(files), "kt_files": len(kt)}
    if not kt:
        res["verdict"] = "no files emitted"
        return res
    for attempt in ("model-build", "reference-build"):
        proj = SANDBOX / name / attempt
        shutil.rmtree(proj, ignore_errors=True)
        scaffold(proj, with_reference_build=(attempt == "reference-build"))
        for path, body in files.items():
            if attempt == "reference-build" and path.endswith("build.gradle.kts"):
                continue
            target = proj / (path if path.startswith("src/") or path.endswith(".kts")
                             else f"src/main/kotlin/{pathlib.Path(path).name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body)
        if not (proj / "build.gradle.kts").exists():
            scaffold(proj, with_reference_build=True)
        r = gradle(proj, "compileKotlin")
        res[attempt] = "OK" if r.returncode == 0 else "FAIL"
        if r.returncode != 0:
            res[f"{attempt}-error"] = errors_of(r)
        if res[attempt] == "OK":
            break
    # быстрые проверки правил проекта
    joined = "\n".join(kt.values())
    res["rule-package"] = "FAIL" if re.search(r"^package ", joined, re.M) else "OK"
    res["rule-no-sdk"] = ("FAIL" if re.search(r"ktor|okhttp|retrofit|spring", joined, re.I)
                          else "OK")
    return res


CONFIG_STUB = """object Config {
    fun historySize(): Int = 10
}
"""

BUG_TEST = """import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Семантика фикса: capacity <= 0 отключает историю, кольцо не ломается. */
class BugFixCheck {
    @Test
    fun capacityZeroDoesNotThrowAndStaysEmpty() {
        val s = HistoryStore(0)
        repeat(3) { s.add("t", "p") }
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun negativeCapacityAlsoSafe() {
        val s = HistoryStore(-1)
        s.add("t", "p")
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun evictionKeepsLatestTwo() {
        val s = HistoryStore(2)
        s.add("a", "1"); s.add("b", "2"); s.add("c", "3")
        val snap = s.snapshot()
        assertEquals(2, snap.size)
        assertEquals("c", snap[0].task)
    }
}
"""


def errors_of(r):
    lines = [l for l in (r.stdout + "\n" + r.stderr).splitlines()
             if l.startswith("e: ") or "error:" in l or "FAILED" in l]
    return lines[:8] or (r.stderr or r.stdout).strip().splitlines()[-4:]


def eval_task_b(name):
    text = (OUT / f"{name}-taskB.md").read_text()
    files = extract(text)
    store = next((b for p, b in files.items() if "HistoryStore" in p), None)
    res = {"patch_emitted": bool(store)}
    low = text.lower()
    res["cause_found"] = bool(
        ("removefirst" in low or "капас" in low or "capacity" in low)
        and ("пуст" in low or "empty" in low or "0 == 0" in low or "== capacity" in low))
    if not store:
        return res
    # изолированная песочница: только патч модели + стаб Config + фокус-тест бага
    proj = SANDBOX / name / "taskB"
    shutil.rmtree(proj, ignore_errors=True)
    scaffold(proj, with_reference_build=True)
    main_dir = proj / "src/main/kotlin"
    test_dir = proj / "src/test/kotlin"
    main_dir.mkdir(parents=True); test_dir.mkdir(parents=True)
    (main_dir / "HistoryStore.kt").write_text(store)
    if "object Config" not in store:
        (main_dir / "Config.kt").write_text(CONFIG_STUB)
    (test_dir / "BugFixCheck.kt").write_text(BUG_TEST)
    r = gradle(proj, "test")
    res["tests"] = "OK" if r.returncode == 0 else "FAIL"
    if r.returncode != 0:
        res["tests-error"] = errors_of(r)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="day-coder,deepseek-coder-6.7b,qwen2.5-14b")
    args = ap.parse_args()
    eval_path = OUT / "eval.json"
    results = json.loads(eval_path.read_text()) if eval_path.exists() else {}
    for name in args.models.split(","):
        for task, fn in (("taskA", eval_task_a), ("taskB", eval_task_b)):
            if not (OUT / f"{name}-{task}.md").exists():
                continue
            print(f"eval {name} × {task}", flush=True)
            results[f"{name}-{task}"] = fn(name)
            eval_path.write_text(json.dumps(results, indent=2, ensure_ascii=False))
    print(json.dumps(results, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
