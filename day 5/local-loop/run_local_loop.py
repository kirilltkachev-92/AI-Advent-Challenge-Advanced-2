#!/usr/bin/env python3
"""
Локальный execution loop (день 5): тот же пул задач, что у облака, на day-coder
(qwen2.5-coder:7b + правила проекта в SYSTEM, Ollama).

Протокол честности: у локальной модели нет инструментов, поэтому харнесс сам
даёт ей содержимое относящихся к задаче файлов; модель обязана вернуть полные
изменённые файлы блоками === FILE: path === ... === END ===. Харнесс применяет
патч в git-worktree и гоняет ./gradlew test. Loop останавливается на первой
задаче, которую модель не вытянула (это и есть «сколько продержалась»).

Запуск: python3 run_local_loop.py <worktree-path>
"""
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.request

WT = pathlib.Path(sys.argv[1]).resolve()
DAY1 = WT / "day 1"
OLLAMA = "http://127.0.0.1:11434/api/chat"
MODEL = "day-coder"
JAVA_HOME = "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
OUT = pathlib.Path(__file__).resolve().parent / "out"

FILE_RE = re.compile(r"=== FILE: (.+?) ===\n(.*?)\n=== END ===", re.S)

# (номер, краткое имя, текст задачи, файлы-контекст относительно day 1)
TASKS = [
    (1, "banner", "Баг: при HISTORY_SIZE<=0 (история отключена, см. HistoryStore.add) стартовый баннер Main.kt печатает «последние 0, в памяти». Сделай: при historySize()<=0 баннер сообщает, что история отключена; при дефолте — как раньше.",
     ["src/main/kotlin/Main.kt", "src/main/kotlin/Config.kt", "src/main/kotlin/HistoryStore.kt"]),
    (2, "ui-time", "Баг: в веб-UI время записи истории показывается сырой ISO-строкой (2026-07-23T10:45:44+03:00). Сделай отображение ЧЧ:ММ:СС (форматирование на стороне JS, контракт API не менять).",
     ["src/main/kotlin/WebUi.kt"]),
    (3, "demo-codes", "Баг: demo.sh не проверяет HTTP-коды ответов; не-JSON роняет json.tool без объяснений. Сделай: каждый шаг проверяет ожидаемый код (200/200/200/400/405/200), при расхождении понятная ошибка и exit 1.",
     ["demo.sh"]),
    (4, "sealed", "Рефакторинг: sealed interface MotivationResult { Done(phrase); Failed(reason) } в Motivator; motivate() возвращает его (исключения клиента гасятся в домене); HttpApi мапит Failed → 502 без try/catch вокруг домена; внешний контракт API прежний; обнови MotivatorTest под новый контракт.",
     ["src/main/kotlin/Motivator.kt", "src/main/kotlin/HttpApi.kt", "src/test/kotlin/MotivatorTest.kt"]),
    (5, "history-dto", "Рефакторинг: GET /v1/history собирает JSON конкатенацией строк — переведи на kotlinx.serialization (@Serializable DTO), формат {\"count\":N,\"entries\":[...]} прежний.",
     ["src/main/kotlin/HttpApi.kt", "src/main/kotlin/HistoryStore.kt"]),
    (6, "limits", "Фича: GET /v1/limits → 200 {\"max_task_chars\":..,\"max_body_bytes\":..,\"history_size\":..,\"model\":\"..\"} из Config; не-GET → 405 в обычном формате ошибок; добавь интеграционный тест в HttpApiTest.",
     ["src/main/kotlin/HttpApi.kt", "src/main/kotlin/Config.kt", "src/test/kotlin/HttpApiTest.kt"]),
    (7, "healthz-count", "Фича: в ответ /healthz добавь history_count (текущее число записей истории); обнови/добавь тест.",
     ["src/main/kotlin/HttpApi.kt", "src/main/kotlin/HistoryStore.kt", "src/test/kotlin/HttpApiTest.kt"]),
    (8, "copy-btn", "Фича: в веб-UI кнопка «Скопировать» (id=copyBtn) рядом с фразой, копирует фразу в буфер (navigator.clipboard), видима только когда фраза есть, ошибки в #errorBox; добавь copyBtn в контрактный список id в тесте страницы.",
     ["src/main/kotlin/WebUi.kt", "src/test/kotlin/HttpApiTest.kt"]),
    (9, "timeout-env", "Фича: вынеси таймаут запроса DeepSeek в env DEEPSEEK_TIMEOUT_SEC (дефолт 60): Config.deepSeekTimeoutSec() + использование в DeepSeekClient.timeout(...).",
     ["src/main/kotlin/Config.kt", "src/main/kotlin/DeepSeekClient.kt"]),
    (10, "request-id", "Фича: X-Request-Id — короткий id на каждый запрос, заголовок во всех ответах (включая ошибки), id в access-логе; добавь интеграционный тест наличия заголовка.",
     ["src/main/kotlin/HttpApi.kt", "src/test/kotlin/HttpApiTest.kt"]),
    (11, "dotenv-tests", "Тесты: вынеси парсинг строки .env из Config.loadDotEnv в чистую функцию parseDotEnvLine(line): Pair<String,String>? (комментарии, пустые, без '=', кавычки, пробелы) и создай ConfigTest.kt с >=5 кейсами; loadDotEnv использует функцию.",
     ["src/main/kotlin/Config.kt"]),
    (12, "http-tests", "Тесты: дополни HttpApiTest: POST /healthz → 405; POST / → 405; запрос с Content-Length больше max_body_bytes → 413.",
     ["src/test/kotlin/HttpApiTest.kt", "src/main/kotlin/HttpApi.kt"]),
    (13, "sanitize", "Тесты+код: расширь sanitize в Motivator — снимать «ёлочки» («...»), схлопывать множественные пробелы/переводы строк в один пробел; тесты на кейсы в MotivatorTest.",
     ["src/main/kotlin/Motivator.kt", "src/test/kotlin/MotivatorTest.kt"]),
    (14, "readme-tests", "Докс: добавь в README секцию «Тесты»: как запускать (./gradlew test), что покрыто по классам (Config/Motivator/HistoryStore/HttpApi), ссылка на smoke ../day 3/smoke/run_smoke.py. Верни ПОЛНЫЙ обновлённый README.md.",
     ["README.md"]),
    (15, "changelog", "Докс: создай CHANGELOG.md дня 1 по приложенной git-истории (группируй по дням марафона, каждая запись с хэшем, без выдумок).",
     ["README.md"]),  # + git log добавляется отдельно
    (16, "research", "Research: по приложенным HttpApi.kt и HttpApiTest.kt перечисли, какие ветви обработчиков НЕ покрыты тестами, списком «строка → ветвь → покрыта/нет». Кода не меняй, верни отчёт файлом === FILE: research-local.md ===.",
     ["src/main/kotlin/HttpApi.kt", "src/test/kotlin/HttpApiTest.kt"]),
]


def ollama(prompt):
    body = json.dumps({
        "model": MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "stream": False,
        "options": {"temperature": 0.2, "top_p": 0.9, "num_ctx": 16384, "num_predict": 6144},
    }).encode()
    req = urllib.request.Request(OLLAMA, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=1800) as r:
        return json.loads(r.read())


def gradle_test():
    r = subprocess.run(["./gradlew", "test", "-q", "--console=plain"], cwd=DAY1,
                       capture_output=True, text=True, timeout=900,
                       env={"PATH": "/usr/bin:/bin", "HOME": str(pathlib.Path.home()),
                            "JAVA_HOME": JAVA_HOME})
    errs = [l for l in (r.stdout + r.stderr).splitlines() if l.startswith("e: ") or "FAILED" in l]
    return r.returncode == 0, errs[:6]


def main():
    OUT.mkdir(exist_ok=True)
    log = []
    survived = 0
    for num, name, text, files in TASKS:
        t0 = time.time()
        ctx = "\n\n".join(
            f"=== ТЕКУЩИЙ {p} ===\n{(DAY1 / p).read_text()}" for p in files if (DAY1 / p).exists())
        if name == "changelog":
            gl = subprocess.run(["git", "log", "--oneline", "--", "day 1"], cwd=WT,
                                capture_output=True, text=True).stdout
            ctx += f"\n\n=== GIT LOG (day 1) ===\n{gl}"
        prompt = (
            f"Задача #{num} ({name}): {text}\n\n"
            "Верни ТОЛЬКО полные содержимые изменённых/новых файлов, каждый строго в формате:\n"
            "=== FILE: <путь относительно day 1> ===\n<содержимое без markdown-ограждений>\n=== END ===\n\n"
            f"{ctx}")
        resp = ollama(prompt)
        answer = resp["message"]["content"]
        (OUT / f"task{num:02d}-{name}.md").write_text(answer)
        gen_sec = round(resp.get("eval_count", 0) / (resp.get("eval_duration", 1) / 1e9), 1)

        files_out = FILE_RE.findall(answer)
        ok, errs = False, ["не выдала ни одного файла в требуемом формате"]
        if files_out:
            for path, bodytext in files_out:
                bodytext = re.sub(r"^```[a-z]*\n|```$", "", bodytext.strip(), flags=re.M)
                target = DAY1 / path.strip()
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(bodytext + "\n")
            if name == "research":
                ok, errs = bool(files_out), []
                # research не должен ломать сборку
                ok2, errs2 = gradle_test()
                ok, errs = ok and ok2, errs2
            else:
                ok, errs = gradle_test()
        dt = round(time.time() - t0)
        log.append({"task": num, "name": name, "ok": ok, "sec": dt,
                    "tok_per_sec": gen_sec, "files": len(files_out), "errors": errs})
        print(f"#{num} {name}: {'OK' if ok else 'FAIL'} за {dt}s ({gen_sec} ток/с)", flush=True)
        if not ok:
            print(f"   причина: {errs}", flush=True)
            break
        survived += 1
        subprocess.run(["git", "add", "-A"], cwd=WT)
        subprocess.run(["git", "commit", "-qm", f"local#{num}: {name}"], cwd=WT)
    (OUT / "local-log.json").write_text(json.dumps(log, indent=2, ensure_ascii=False))
    print(f"\nВыдержала подряд: {survived}/{len(TASKS)}")


if __name__ == "__main__":
    main()
