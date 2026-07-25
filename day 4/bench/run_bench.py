#!/usr/bin/env python3
"""
Бенч дня 4: локальные модели против тех же задач, что решал облачный ассистент.

Задача A (день 1): сгенерировать сервис-мотиватор одним промптом.
Задача B (день 2): по симптому найти причину бага и дать минимальный фикс.

Для каждой пары модель×задача: вызов Ollama /api/chat (stream=false),
замер скорости из eval_count/eval_duration, сырой ответ в out/<model>-<task>.md,
метрики в out/metrics.json.

Запуск: python3 run_bench.py [--models m1,m2,...]
"""
import argparse
import json
import pathlib
import re
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT / "out"
OLLAMA = "http://127.0.0.1:11434/api/chat"

SYSTEM = (ROOT.parent / "Modelfile").read_text().split('SYSTEM """')[1].rsplit('"""', 1)[0]

TASK_A = """День 1. Сделай HTTP-сервис «мотиватор»:
- GET /healthz — статус сервиса;
- POST /v1/motivate — принимает {"task": "..."} и возвращает короткую мотивационную фразу для этой задачи через DeepSeek;
- GET /v1/history — последние 10 запросов и ответов (в памяти).
Порт 8080.

Выдай ПОЛНЫЙ код всех файлов src/main/kotlin (и build.gradle.kts). Каждый файл — отдельным блоком строго в формате:
=== FILE: <относительный путь> ===
<содержимое файла без markdown-ограждений>
=== END ===
"""

PREFIX_STORE = pathlib.Path(
    "/private/tmp/claude-501/-Users-kirill-Documents-AI-Advent-Challenge-Advanced-2/"
    "fcd67035-8a19-40e3-b66c-7355fdcf4b5d/scratchpad/HistoryStore.prefix.kt"
).read_text()

TASK_B = f"""Баг в нашем сервисе (Kotlin, JVM 17). Симптом: хотел отключить историю запросов — поставил HISTORY_SIZE=0 в .env. Теперь КАЖДЫЙ POST /v1/motivate отвечает 500 {{"error":{{"code":"internal",...}}}}, хотя по логу DeepSeek успешно вернул фразу. Если убрать HISTORY_SIZE (дефолт 10) — всё работает. Ожидание: HISTORY_SIZE=0 просто отключает историю, /v1/history отдаёт пустой список.

Контекст: Config.historySize() возвращает Int из env (HISTORY_SIZE, дефолт 10). В HttpApi.handleMotivate после успешного ответа LLM вызывается history.add(task, phrase); любое исключение из обработчика превращается обёрткой handle() в 500.

src/main/kotlin/HistoryStore.kt:
```kotlin
{PREFIX_STORE}```

Найди ТОЧНУЮ причину (файл:строка + механика) и дай минимальный фикс. Ответ строго:
1) секция «Причина» — 2-4 предложения со ссылкой на строку;
2) полный исправленный файл в формате:
=== FILE: src/main/kotlin/HistoryStore.kt ===
<содержимое>
=== END ===
"""

TASKS = {"taskA": TASK_A, "taskB": TASK_B}

# day-coder — из Modelfile (system зашит); остальным system передаём в запросе
MODELS = {
    "day-coder": {"model": "day-coder", "system": False, "num_ctx": 16384},
    "deepseek-coder-6.7b": {"model": "deepseek-coder:6.7b", "system": True, "num_ctx": 16384},
    "qwen2.5-14b": {"model": "qwen2.5:14b", "system": True, "num_ctx": 8192},
}


def call(model_cfg, prompt):
    messages = []
    if model_cfg["system"]:
        messages.append({"role": "system", "content": SYSTEM})
    messages.append({"role": "user", "content": prompt})
    body = json.dumps({
        "model": model_cfg["model"],
        "messages": messages,
        "stream": False,
        "options": {"temperature": 0.2, "top_p": 0.9, "num_ctx": model_cfg["num_ctx"],
                    "num_predict": 4096},
    }).encode()
    req = urllib.request.Request(OLLAMA, data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=1800) as r:
        return json.loads(r.read())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default=",".join(MODELS))
    ap.add_argument("--tasks", default="taskA,taskB")
    args = ap.parse_args()

    OUT.mkdir(exist_ok=True)
    metrics_path = OUT / "metrics.json"
    metrics = json.loads(metrics_path.read_text()) if metrics_path.exists() else {}

    for name in args.models.split(","):
        cfg = MODELS[name]
        for task in args.tasks.split(","):
            print(f"→ {name} × {task} ...", flush=True)
            resp = call(cfg, TASKS[task])
            text = resp["message"]["content"]
            (OUT / f"{name}-{task}.md").write_text(text)
            m = {
                "total_sec": round(resp.get("total_duration", 0) / 1e9, 1),
                "load_sec": round(resp.get("load_duration", 0) / 1e9, 1),
                "prompt_tokens": resp.get("prompt_eval_count"),
                "out_tokens": resp.get("eval_count"),
                "tok_per_sec": round(
                    resp.get("eval_count", 0) / (resp.get("eval_duration", 1) / 1e9), 1),
                "files_emitted": len(re.findall(r"=== FILE:", text)),
            }
            metrics[f"{name}-{task}"] = m
            metrics_path.write_text(json.dumps(metrics, indent=2, ensure_ascii=False))
            print(f"  {m}", flush=True)
    print(f"OK → {OUT}")


if __name__ == "__main__":
    main()
