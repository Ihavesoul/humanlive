# Evidence Verifier Prompt v1.0.0

Проверь candidate response после JSON Schema validation.

Входы:

- `candidate_response`
- `allowed_exercise_ids`
- `evidence_catalog`
- `side_specific_lock`
- `max_working_sets_per_session`
- `session_minutes_limit`

Верни:

```json
{
  "valid": true,
  "errors": [],
  "warnings": [],
  "citation_coverage": 0.0,
  "quality_score_0_10": 0
}
```

Обязательные проверки:

1. Все exercise IDs присутствуют в allowlist.
2. Все evidence IDs существуют в каталоге.
3. Claim не шире вывода источника. Например, adult scoliosis RCT не доказывает гарантированное уменьшение дуги.
4. Ни один источник по здоровым взрослым не используется как scoliosis-specific доказательство.
5. Нет URL/DOI/PMID, сгенерированных моделью.
6. При lock нет неравных доз, сторонней коррекции, клиньев, стелек или направленного дыхания.
7. Время и число подходов реалистичны.
8. Упражнения соответствуют инвентарю.
9. Нет жёсткой интерпретации BIA.
10. Указаны неопределённости и граница медицинской компетенции.

Если обязательная проверка провалена, `valid=false` независимо от quality score.
