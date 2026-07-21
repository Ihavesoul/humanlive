---
title: LLM Generator Harness
tags: [llm, prompts, safety, engineering]
---

# Зачем LLM вообще нужен

Базовый план лучше держать детерминированным. LLM полезен для:

- перестановки дней;
- выбора регрессии из allowlist;
- объяснения техники простым языком;
- адаптации объёма к логам;
- формирования недельного отчёта;
- перевода/форматирования.

LLM не должен решать, какая сторона дуги требует деротации, и не должен создавать научную базу «из памяти».

# Поток

1. Профиль → JSON Schema.
2. Красные флаги → детерминированный gate.
3. Allowlist упражнений и evidence IDs.
4. Prompt builder.
5. Structured JSON output.
6. Schema validation.
7. Проверка IDs, сторонних инструкций, сетов и времени.
8. Только после этого рендер пользователю.

# Практический запуск

1. Открыть приложение.
2. Заполнить профиль и safety checklist.
3. Перейти в «LLM Prompt».
4. Скопировать prompt и JSON.
5. Передать провайдеру, поддерживающему structured output.
6. Сохранить ответ.
7. Провалидировать по `data/schemas/workout_response.schema.json` и `prompts/evidence_verifier_prompt_ru.md`.

# Production

API-ключ должен жить на backend. Статический PWA с ключом — не PoC, а утечка. Архитектура описана в `../specs/Architecture.md`.
