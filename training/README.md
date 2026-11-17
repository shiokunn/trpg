# TRPG Training Dataset Workspace

This directory stores synthetic SFT data generated with DeepSeek for the TRPG app.

## Workflow

1. Read the current implementation and prompt/schema definitions on the target branch before generating data.
2. Generate examples with the prompts in `prompts/`.
3. Save untouched DeepSeek responses in `raw/`, including generation metadata.
4. Normalize them into JSONL in `processed/`.
5. Validate JSON syntax, exact app schema, value constraints, IDs/references, and duplicates.
6. Split by scenario/source group into `splits/train.jsonl`, `validation.jsonl`, and `test.jsonl`. Never split near-duplicate variants across sets.
7. Train only from the validated training split. Keep validation/test data out of training.

## Dataset format

Each JSONL line is one SFT conversation:

```json
{"messages":[{"role":"system","content":"..."},{"role":"user","content":"..."},{"role":"assistant","content":"..."}],"metadata":{"task":"trpg_gm","source":"deepseek","license_review":"pending"}}
```

Use only `system`, `user`, and `assistant` roles. The assistant content must be the exact target output expected by the app. For JSON tasks, assistant content must contain one valid JSON object and no Markdown fences or surrounding commentary.

## Dataset groups

- `trpg_gm`: GM narration, choices, state changes, story memories, and NPC memories.
- `world_json`: natural-language requirements to a valid world/scenario draft.
- `extraction`: extracting only explicitly supported facts into structured JSON.

Keep these tasks separately identifiable in metadata and separate files. Do not mix app-incompatible JSON contracts.

## Quality and safety gates

- Treat the current Kotlin serializers, parsers, validators, and prompt contract on this branch as the source of truth.
- Do not invent schema keys, enum values, IDs, dice results, or game-state facts.
- Include both ordinary and edge cases, but never make invalid outputs the target answer.
- Avoid repetitive templates and superficial paraphrases.
- Exclude personal data, copyrighted passages, and outputs whose terms do not permit dataset use.
- Record the exact teacher model/provider, generation date, prompt version, and applicable license/terms review.
- Do not claim a license permits distillation until the model and API provider terms have been checked.

## Directory map

- `raw/`: untouched teacher outputs and generation manifests.
- `processed/`: normalized, validated JSONL datasets.
- `splits/`: fixed train/validation/test partitions.
- `prompts/`: versioned DeepSeek generation instructions.
- `schemas/`: dataset format and validation notes.
- `metadata/`: dataset manifest and provenance.

Generated data is not considered training-ready until validation and provenance checks pass.
