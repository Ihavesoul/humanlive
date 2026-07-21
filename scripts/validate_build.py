#!/usr/bin/env python3
from __future__ import annotations
import json,sys,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REQUIRED=['README_RU.md','BUILD_STATUS.md','DISCLAIMER.md','excel/Body_Recomp_Scoliosis_POC.xlsx','app/index.html','app/app.js','app/styles.css','app/manifest.webmanifest','app/sw.js','data/profile.json','data/derived_metrics.json','data/program_12_weeks.json','data/exercises.json','data/evidence_catalog.json','data/safety_screening.json','prompts/system_prompt_ru.md','specs/SDD.md','obsidian/00_Index.md']
errors=[]
for rel in REQUIRED:
    p=ROOT/rel
    if not p.exists() or p.stat().st_size==0: errors.append(f'missing/empty: {rel}')
for p in ROOT.rglob('*.json'):
    try: json.loads(p.read_text(encoding='utf-8'))
    except Exception as exc: errors.append(f'invalid JSON {p.relative_to(ROOT)}: {exc}')
xlsx=ROOT/'excel/Body_Recomp_Scoliosis_POC.xlsx'
if xlsx.exists():
    try:
        with zipfile.ZipFile(xlsx) as z:
            if '[Content_Types].xml' not in z.namelist(): errors.append('XLSX package lacks [Content_Types].xml')
    except Exception as exc: errors.append(f'invalid XLSX ZIP: {exc}')
if errors: print('\n'.join(errors),file=sys.stderr); raise SystemExit(1)
print(f'Build OK: {sum(1 for p in ROOT.rglob("*") if p.is_file())} files')
