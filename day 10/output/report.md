# День 10 — отчёт: micro-model first vs all-LLM

Fallback/базлайн: `deepseek-v4-flash`; порог уверенности micro: 0.20 (пол top1 0.30, мин. знакомых токенов 2); дата: 2026-08-01 10:35.
Уверенность micro-model структурная (близость + отрыв top1−top2), а не самооценка: маленькие модели рапортуют о 100% уверенности (эффект Даннинга–Крюгера), верить их самоотчёту нельзя.

## Кейсы

| id | кейс | ожидание | micro: метка (score, статус) | финал | уровень | верно | мс | базлайн LLM | базлайн верно |
|----|------|----------|------------------------------|-------|---------|-------|----|-------------|---------------|
| q01 | баланс, ясно | BALANCE | BALANCE (0.37, OK) | BALANCE | micro | ✓ | 0 | BALANCE | ✓ |
| q02 | блокировка, утеря | CARD_BLOCK | CARD_BLOCK (0.52, OK) | CARD_BLOCK | micro | ✓ | 0 | CARD_BLOCK | ✓ |
| q03 | возврат, двойное списание | REFUND_CLAIM | REFUND_CLAIM (0.43, OK) | REFUND_CLAIM | micro | ✓ | 0 | REFUND_CLAIM | ✓ |
| q04 | перевод маме | TRANSFER | TRANSFER (0.27, OK) | TRANSFER | micro | ✓ | 0 | TRANSFER | ✓ |
| q05 | смена пин-кода | PIN_CHANGE | PIN_CHANGE (0.55, OK) | PIN_CHANGE | micro | ✓ | 0 | PIN_CHANGE | ✓ |
| q06 | оператор | OPERATOR | OPERATOR (0.37, OK) | OPERATOR | micro | ✓ | 0 | OPERATOR | ✓ |
| q07 | баланс, остаток | BALANCE | BALANCE (0.49, OK) | BALANCE | micro | ✓ | 0 | BALANCE | ✓ |
| q08 | блокировка, кража | CARD_BLOCK | CARD_BLOCK (0.49, OK) | CARD_BLOCK | micro | ✓ | 0 | CARD_BLOCK | ✓ |
| q09 | перевод на карту | TRANSFER | TRANSFER (0.33, OK) | TRANSFER | micro | ✓ | 0 | TRANSFER | ✓ |
| q10 | пин забыт | PIN_CHANGE | PIN_CHANGE (0.60, OK) | PIN_CHANGE | micro | ✓ | 0 | PIN_CHANGE | ✓ |
| q11 | сленг: чё по бабкам | BALANCE | BALANCE (0.18, UNSURE) | BALANCE | llm | ✓ | 1687 | BALANCE | ✓ |
| q12 | сленг: задвоилось, рэ | REFUND_CLAIM | REFUND_CLAIM (0.30, OK) | REFUND_CLAIM | micro | ✓ | 0 | REFUND_CLAIM | ✓ |
| q13 | сленг: спёрли | CARD_BLOCK | CARD_BLOCK (0.17, UNSURE) | CARD_BLOCK | llm | ✓ | 1934 | CARD_BLOCK | ✓ |
| q14 | сленг: закинь косарь | TRANSFER | TRANSFER (0.08, UNSURE) | TRANSFER | llm | ✓ | 1312 | TRANSFER | ✓ |
| q15 | оператор, эмоции | OPERATOR | OPERATOR (0.26, OK) | OPERATOR | micro | ✓ | 0 | OPERATOR | ✓ |
| q16 | опечатки: балланс | BALANCE | BALANCE (0.00, UNSURE) | BALANCE | llm | ✓ | 1344 | BALANCE | ✓ |
| q17 | пин, скобки | PIN_CHANGE | PIN_CHANGE (0.43, OK) | PIN_CHANGE | micro | ✓ | 0 | PIN_CHANGE | ✓ |
| q18 | возврат за отменённый заказ | REFUND_CLAIM | REFUND_CLAIM (0.25, OK) | REFUND_CLAIM | micro | ✓ | 0 | REFUND_CLAIM | ✓ |
| q19 | компрометация карты | CARD_BLOCK | CARD_BLOCK (0.24, OK) | CARD_BLOCK | micro | ✓ | 0 | CARD_BLOCK | ✓ |
| q20 | перевод по телефону, вопросом | TRANSFER | TRANSFER (0.22, OK) | TRANSFER | micro | ✓ | 0 | TRANSFER | ✓ |
| q21 | мульти-интент: перевод + блокировка | TRANSFER | TRANSFER (0.19, UNSURE) | TRANSFER | llm | ✓ | 1860 | TRANSFER | ✓ |
| q22 | простыня → блокировка | CARD_BLOCK | TRANSFER (0.10, UNSURE) | CARD_BLOCK | llm | ✓ | 1429 | CARD_BLOCK | ✓ |
| q23 | отрицание блокировки → возврат | REFUND_CLAIM | REFUND_CLAIM (0.41, OK) | REFUND_CLAIM | micro | ✓ | 0 | REFUND_CLAIM | ✓ |
| q24 | инъекция в тексте → оператор | OPERATOR | OPERATOR (0.30, OK) | OPERATOR | micro | ✓ | 0 | OPERATOR | ✓ |
| q25 | простыня → баланс | BALANCE | BALANCE (0.16, UNSURE) | BALANCE | llm | ✓ | 1940 | BALANCE | ✓ |
| q26 | пин без слова «пин» | PIN_CHANGE | PIN_CHANGE (0.16, UNSURE) | PIN_CHANGE | llm | ✓ | 1663 | PIN_CHANGE | ✓ |
| q27 | простыня → перевод | TRANSFER | TRANSFER (0.11, UNSURE) | TRANSFER | llm | ✓ | 1158 | TRANSFER | ✓ |
| q28 | возврат без слова «верните» | REFUND_CLAIM | REFUND_CLAIM (0.22, OK) | REFUND_CLAIM | micro | ✓ | 0 | REFUND_CLAIM | ✓ |
| q29 | оператор с отрицанием других тем | OPERATOR | BALANCE (0.10, UNSURE) | OPERATOR | llm | ✓ | 1264 | OPERATOR | ✓ |
| q30 | утеря кошелька, блокировка неявно | CARD_BLOCK | CARD_BLOCK (0.17, UNSURE) | CARD_BLOCK | llm | ✓ | 1432 | CARD_BLOCK | ✓ |

## Итоги

- micro-model закрыла сама: **19/30**; ушло в LLM-fallback: **11/30**
- вызовов большой LLM в конвейере: **11** (fallback; 30 вызовов базлайна — контрольная группа, в стоимость конвейера не входят)
- точность конвейера micro→LLM: **30/30**
- точность all-LLM-базлайна: **30/30**
- точность micro-model принудительно на всех кейсах (если бы fallback не было): 28/30

## Стоимость

| вариант | вызовы LLM | токены (prompt+completion) | латентность всего, мс | средняя на кейс, мс |
|---------|------------|----------------------------|-----------------------|---------------------|
| конвейер micro→LLM | 11 | 4946 (4039+907) | 17023 | 567 |
| all-LLM-базлайн | 30 | 13086 (10574+2512) | 47020 | 1567 |

## Вывод

- вызовы большой LLM срезаны с 30 до 11; средняя латентность 567 мс против 1567 мс у all-LLM (экономия токенов: 13086 → 4946, 62%)
- точность при этом 30/30 против 30/30 у all-LLM: micro-model берёт только кейсы, где уверена структурно (score = top1·(1+margin)/2), а сомнительное честно отдаёт большой модели
