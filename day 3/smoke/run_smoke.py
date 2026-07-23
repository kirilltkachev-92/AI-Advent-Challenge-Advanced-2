#!/usr/bin/env python3
"""
Smoke-раннер UI мотиватора (day 1) — исполняемая форма сценариев из
day 3/smoke-scenarios.md. Гоняет реальный Chrome (Playwright, channel="chrome",
headless), на каждом шаге — скриншот, на выходе — markdown-отчёт PASS/FAIL
с указанием, где именно сломалось.

Запуск:  python3 run_smoke.py [--base http://127.0.0.1:8080] [--tag v1]
Сервис должен быть уже поднят (cd "day 1" && ./run.sh).
"""
import argparse
import datetime
import pathlib
import sys
import traceback

from playwright.sync_api import expect, sync_playwright

DAY3 = pathlib.Path(__file__).resolve().parent.parent
SHOTS = DAY3 / "screenshots"

parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://127.0.0.1:8080")
parser.add_argument("--tag", default="v1", help="метка прогона: имена скриншотов/отчёта")
args = parser.parse_args()

BASE = args.base
TAG = args.tag
results = []  # (id, title, PASS/FAIL, steps_done, failure)


class Step:
    """Контекст сценария: нумерует шаги и снимает скриншот после каждого."""

    def __init__(self, page, sid):
        self.page, self.sid, self.n, self.done = page, sid, 0, []

    def do(self, title, fn=None):
        self.n += 1
        if fn:
            fn()
        self.page.screenshot(path=str(SHOTS / f"{TAG}-{self.sid}-step{self.n}.png"))
        self.done.append(f"{self.n}. {title}")


def scenario(sid, title):
    def wrap(fn):
        fn.sid, fn.title = sid, title
        return fn
    return wrap


@scenario("S1", "Страница открывается и готова к работе")
def s1(page, st):
    st.do("Открыть /", lambda: page.goto(BASE + "/"))
    st.do("Форма на месте", lambda: (
        expect(page.locator("#taskInput")).to_be_visible(),
        expect(page.locator("#motivateBtn")).to_be_visible(),
        expect(page.locator("#historyList")).to_be_attached(),
    ))
    st.do("Ошибок нет", lambda: expect(page.locator("#errorBox")).to_be_empty())


TASK_S2 = "выйти на пробежку в дождь"


@scenario("S2", "Генерация мотивации (happy path)")
def s2(page, st):
    st.do("Открыть /", lambda: page.goto(BASE + "/"))
    st.do("Ввести задачу", lambda: page.fill("#taskInput", TASK_S2))
    st.do("Нажать «Мотивировать»", lambda: page.click("#motivateBtn"))
    st.do("Фраза получена, ошибок нет", lambda: (
        expect(page.locator("#phraseBox")).not_to_be_empty(timeout=90_000),
        expect(page.locator("#errorBox")).to_be_empty(),
    ))


@scenario("S3", "Запись появляется в истории")
def s3(page, st):
    item = page.locator("#historyList .history-item", has_text=TASK_S2).first
    st.do("Запись из S2 в истории", lambda: expect(item).to_be_visible())
    st.do("Перезагрузить страницу", lambda: page.reload())
    st.do("Запись пережила F5 (живёт на сервере)", lambda: expect(
        page.locator("#historyList .history-item", has_text=TASK_S2).first
    ).to_be_visible(timeout=10_000))


@scenario("S4", "Ошибка валидации показывается пользователю")
def s4(page, st):
    st.do("Открыть /", lambda: page.goto(BASE + "/"))
    st.do("Пустая задача", lambda: page.fill("#taskInput", "   "))
    st.do("Нажать «Мотивировать»", lambda: page.click("#motivateBtn"))
    st.do("Ошибка показана человеку", lambda:
          expect(page.locator("#errorBox")).not_to_be_empty(timeout=10_000))
    st.do("Сервис жив: повторная генерация работает", lambda: (
        page.fill("#taskInput", "проверка после ошибки"),
        page.click("#motivateBtn"),
        expect(page.locator("#phraseBox")).to_contain_text(
            "", timeout=90_000),  # ждём непустоту ниже
        expect(page.locator("#phraseBox")).not_to_be_empty(timeout=90_000),
        expect(page.locator("#errorBox")).to_be_empty(),
    ))


@scenario("S5", "Очистка истории (фича v2)")
def s5(page, st):
    st.do("Открыть /", lambda: page.goto(BASE + "/"))
    if page.locator("#historyList .history-item").count() == 0:
        st.do("История пуста — генерируем запись", lambda: (
            page.fill("#taskInput", "запись для проверки очистки"),
            page.click("#motivateBtn"),
            expect(page.locator("#phraseBox")).not_to_be_empty(timeout=90_000),
        ))
    st.do("Кнопка очистки видна", lambda:
          expect(page.locator("#clearHistoryBtn")).to_be_visible())
    st.do("Нажать «Очистить историю»", lambda: page.click("#clearHistoryBtn"))
    st.do("История пуста, ошибок нет", lambda: (
        expect(page.locator("#historyList .history-item")).to_have_count(0),
        expect(page.locator("#errorBox")).to_be_empty(),
    ))
    st.do("F5: пусто и после перезагрузки", lambda: (
        page.reload(),
        expect(page.locator("#historyList .history-item")).to_have_count(0),
    ))


SCENARIOS = [s1, s2, s3, s4, s5]


def main():
    SHOTS.mkdir(exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True)
        page = browser.new_page(viewport={"width": 1280, "height": 900})
        page.set_default_timeout(10_000)
        for sc in SCENARIOS:
            st = Step(page, sc.sid)
            try:
                sc(page, st)
                results.append((sc.sid, sc.title, "PASS", st.done, None))
            except Exception:
                page.screenshot(path=str(SHOTS / f"{TAG}-{sc.sid}-FAIL.png"))
                fail = traceback.format_exc(limit=1).strip().splitlines()[-1]
                results.append((sc.sid, sc.title, "FAIL",
                                st.done, f"упал шаг {st.n}: {fail}"))
        browser.close()

    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    lines = [
        f"# Smoke-прогон {TAG} — {now}", "",
        f"UI: {BASE} · Chrome (Playwright, headless) · скриншоты: `screenshots/{TAG}-*.png`", "",
        "| Сценарий | Вердикт | Шаги |",
        "|---|---|---|",
    ]
    for sid, title, verdict, done, fail in results:
        mark = "✅ PASS" if verdict == "PASS" else "❌ FAIL"
        lines.append(f"| {sid}. {title} | {mark} | {len(done)} |")
    for sid, title, verdict, done, fail in results:
        lines += ["", f"## {sid}. {title} — {verdict}", ""]
        lines += [f"- {d} → `screenshots/{TAG}-{sid}-step{i + 1}.png`"
                  for i, d in enumerate(done)]
        if fail:
            lines += ["", f"**Где проблема:** {fail}",
                      f"Скриншот падения: `screenshots/{TAG}-{sid}-FAIL.png`"]
    report = DAY3 / f"smoke-report-{TAG}.md"
    report.write_text("\n".join(lines) + "\n")
    failed = [r for r in results if r[2] == "FAIL"]
    print(f"{len(results) - len(failed)}/{len(results)} PASS → {report}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
