# Architecture

## PoC: local-first

```text
┌─────────────────────────────────────────┐
│ Android/desktop browser                 │
│ app/index.html + app.js + styles.css    │
│                                         │
│  Profile/Calculator                     │
│  Deterministic Programme                │
│  Safety Gate                            │
│  LocalStorage Log Store                 │
│  Prompt/JSON Builder                    │
└───────────────┬─────────────────────────┘
                │ manual copy/export
                ▼
       External LLM chat/provider
```

No sensitive API secret is stored in the client. The base programme remains usable without an LLM.

## Recommended production architecture

```text
PWA / Android wrapper
       │ HTTPS + OAuth/session
       ▼
API Gateway
       │
       ├── Profile & Log Service
       ├── Deterministic Safety Engine
       ├── Programme Rules Engine
       ├── Evidence Catalog Service
       └── LLM Orchestrator
              │
              ├── provider adapter
              ├── structured-output request
              ├── schema validator
              ├── evidence/exercise allowlist validator
              ├── side-specific policy checker
              └── audit record
```

## Provider adapter contract

The adapter receives a provider-neutral request:

```json
{
  "model_profile": "reasoning-medium",
  "system_prompt_version": "1.0.0",
  "input": {},
  "response_schema": {},
  "temperature": 0.1,
  "max_output_tokens": 8000
}
```

It converts this into OpenAI/Anthropic/Gemini/etc. specific calls. The provider is not trusted to enforce safety; post-validation is mandatory.

## Evidence architecture

The evidence catalog is not free-form RAG. It is a versioned, curated allowlist. Each claim in a generated plan must point to an `evidence_id`. The UI resolves IDs to full citations. This prevents invented DOI/PMID strings from being displayed as legitimate references.

A later version may add retrieval over full abstracts, but the model still cannot cite anything not present in the catalog.

## Android delivery options

1. **Installable PWA** — lowest effort, current PoC.
2. **Trusted Web Activity** — Android package wrapping the hosted PWA.
3. **Capacitor** — native shell with local files and filesystem export.
4. **React Native/Flutter** — only justified if native sensors, notifications or Health Connect are required.

For current scope, PWA is the rational choice. A custom native stack adds maintenance without solving a user problem.

## Offline strategy

- app shell cached by service worker when served via HTTP(S);
- profile/log data in localStorage;
- deterministic programme embedded;
- evidence catalog embedded;
- LLM generation unavailable offline, but prompt export remains available.

## Versioning

- app version: semantic version;
- schema version: independent semantic version;
- programme version;
- evidence catalog date/hash;
- prompt version;
- safety policy version.

A saved plan records all five so future changes can be audited.
