# Phase 11 — Configuration catalog (declared, v1)

This is the download list (`app/src/main/assets/model_catalog.json`, checked against Hugging Face on 2026-11-25), with each model's **declared** phone class. The class comes from the pairing method's memory rule: the file (plus its picture part, if any) plus 200 MB must fit in the room a class usually has for models next to the game:

| Class | Room for models |
|---|---|
| entry-level | 1.5 GB |
| midrange | 3.5 GB |
| flagship | 7 GB |

"Declared" means worked out from the size, not measured. Measured evidence comes from the Phase 10 tests on the phone: the RECOMMENDED PAIRS card uses it as soon as it exists, and an export (Debug data) records which evidence each recommendation used.

| Model | Job in the list | Pictures | Size, MB (with picture part) | Declared class | Source |
|---|---|---|---:|---|---|
| Gemma 4 E2B (LiteRT-LM) | strategic | yes | 2,468 | midrange | `litert-community/gemma-4-E2B-it-litert-lm` @ `2e12bfd869` |
| Qwen3 0.6B (LiteRT-LM) | tactical | no | 585 | entry-level | `litert-community/Qwen3-0.6B` @ `8d42ab5f1d` |
| Qwen2.5 1.5B (LiteRT-LM) | strategic | no | 1,523 | midrange | `litert-community/Qwen2.5-1.5B-Instruct` @ `60257f9f1d` |
| SmolVLM 256M (GGUF, pictures) | tactical | yes | 265 | entry-level | `ggml-org/SmolVLM-256M-Instruct-GGUF` @ `b9e4379657` |
| SmolVLM2 500M (GGUF, pictures) | tactical | yes | 520 | entry-level | `ggml-org/SmolVLM2-500M-Video-Instruct-GGUF` @ `ccd7aae53b` |
| Qwen2.5 0.5B (GGUF) | tactical | no | 468 | entry-level | `Qwen/Qwen2.5-0.5B-Instruct-GGUF` @ `9217f5db79` |
| Gemma 3 1B (GGUF) | tactical | no | 768 | entry-level | `ggml-org/gemma-3-1b-it-GGUF` @ `f9c28bcd85` |
| Llama 3.2 1B (GGUF) | tactical | no | 770 | entry-level | `bartowski/Llama-3.2-1B-Instruct-GGUF` @ `067b946cf0` |
| Qwen3 1.7B (GGUF) | strategic | no | 1,056 | entry-level | `unsloth/Qwen3-1.7B-GGUF` @ `d7f544eead` |
| Qwen2.5-VL 3B (GGUF, pictures) | strategic | yes | 2,646 | midrange | `ggml-org/Qwen2.5-VL-3B-Instruct-GGUF` @ `5037fcf163` |
| Gemma 3 4B (GGUF, pictures) | strategic | yes | 3,186 | midrange | `ggml-org/gemma-3-4b-it-GGUF` @ `d097622374` |

**A pair needs both models at once.** On this rule, Gemma 4 E2B (midrange) with a small fight model (entry-level) needs about 3.2 GB for models, which fits a midrange phone's 3.5 GB. Two midrange models together need a flagship phone.

**Measured so far:** nothing on a phone yet. The owner's first runs (Gemma 4 E2B on GPU and on CPU) will be the first measured entries; the closing record (plan section 7) will list them.
