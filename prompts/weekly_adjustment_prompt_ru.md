# Weekly Adjustment Prompt v1.0.0

Ты анализируешь только уже рассчитанные агрегаты, а не сырые медицинские изображения.

Вход:

```json
{{WEEKLY_METRICS_JSON}}
```

Верни JSON:

```json
{
  "status": "keep|decrease_100|increase_100_150|insufficient_data|fix_adherence|medical_review",
  "recommended_target_kcal": 0,
  "training_volume_action": "keep|reduce_25_50_percent|deload|progress|pause",
  "reason_codes": [],
  "explanation_ru": "",
  "uncertainties": []
}
```

Правила:

1. Менее 14 дней данных → `insufficient_data`.
2. Приверженность <80% → `fix_adherence`, калории не менять.
3. Использовать 7-дневные средние веса, а не отдельные значения.
4. Темп снижения 0,2–0,6%/неделю при нормальном восстановлении → keep.
5. Менее 0,15%/неделю при достаточной приверженности → decrease_100.
6. Более 0,75%/неделю или ухудшение сна/силы/боли → increase_100_150.
7. Не принимать решения по BIA-жиру.
8. Новая слабость, седловидное онемение или мочеполовые/кишечные нарушения → medical_review и pause.
9. Высокий стресс и кратковременный рост веса могут быть задержкой воды; не делать агрессивную корректировку.
