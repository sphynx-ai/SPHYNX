# The Model Lab: user guide (Phase 9)

Sphynx runs its AI on the phone. The **Model page** begins with the **Model Lab**, which lists every model file the app holds. This guide covers adding a model, where each file goes, picture parts (mmproj), reading a card, and freeing room.

## 1. Adding a model

There are three ways to add a model:

1. **Download it in the app.** Go to Model page → DOWNLOAD A MODEL → *Choose a model to download…*.
   - Pick a model. The question shows its size and its Hugging Face fingerprint (SHA-256) before anything downloads, and warns when you are not on Wi-Fi.
   - A GGUF model that reads pictures offers **Model and picture part** or **Model only**.
   - The file goes into `Sphynx-Storage/Input`. If the connection breaks or you tap *Pause the download*, it goes on from where it stopped the next time you download it.
   - When it is complete, the app checks the fingerprint and only then takes the file.
   - Nothing downloads by itself. The list is built into the app and changes only with an app update.
2. **Put a `.litertlm` file in `Sphynx-Storage/Input`.** The app copies it in at the next folder scan, which runs at start-up or with *Scan the folder now*.
   - The newest strategic file becomes the strategic job's model, and a small Gemma becomes the tactical one.
   - Every other `.litertlm` is kept too, and can be given a job in the Lab.
3. **Add a GGUF file by hand.** Use Model page → GGUF MODELS (LLAMA.CPP) → *Add a .gguf model*. A GGUF file put in Input is not read by itself.

The app copies each model into its own storage and checks it first:

- the file's header (a broken or half-copied file is refused, with the reason);
- the room left (256 MB is always kept free);
- the fingerprint (the same GGUF file is not kept twice).

## 2. Which file goes where

| File | What it is | Where |
|---|---|---|
| `*.litertlm` | A LiteRT-LM model: Gemma 4 E2B for strategy, Gemma 3 270M or Qwen3 0.6B for fights | `Sphynx-Storage/Input`, or a download |
| `*.gguf` | A llama.cpp model | *Add a .gguf model*, or a download |
| `mmproj-*.gguf` | The picture part of a GGUF model | Next to its model: add it the same way, or download it with the model |

## 3. Picture parts (mmproj)

A GGUF model reads pictures only through its separate **mmproj** file. The app pairs them by name, for example `gemma-3-4b-it-Q4_K_M.gguf` with `mmproj-gemma-3-4b-it-f16.gguf`.

- A download with its picture part is saved under a name that pairs by itself.
- A LiteRT-LM file carries its picture part inside it, if it has one. The Lab shows *reads pictures* or *text only* after its first load.

## 4. Reading a card

Each card shows:

- the file and its format;
- the family, quantization and size;
- whether it reads pictures;
- the length it was trained for;
- where it came from;
- its fingerprint;
- its job, and whether it is loaded and on which processor;
- its own settings.

The models in use come first.

- **Give it a job…** makes it the strategic or the tactical model. The usual checks apply: one GGUF at a time, the memory warning, and a note when a text-only model gets the strategic job.
- **Settings…** sets this model's own settings: context length (GGUF), temperature, threads (GGUF), 8-bit cache (GGUF), processor order, and the picture part.
  - An empty value means the default: the model then runs exactly as before.
  - A value outside the safe limits is refused, with the reason.
  - A change is used the next time the model loads.
- **Delete the app's copy** asks first, and is offered only for a model that has no job. The file in `Sphynx-Storage/Input` stays there and is not copied back unless it changes.

## 5. Room on the phone

The **STORAGE** card shows:

- how much room the models take, by format;
- how much is free;
- a warning when another model may not fit;
- **leftover files**: a copy that broke off, or a model no card points to.

*Delete leftover files* asks first. A copy that is still running is never counted as a leftover.

A downloaded file stays in `Sphynx-Storage/Input` as the original, so it takes room twice, the same as a file you put there yourself. You can delete the one in Input once the Lab shows the model.

## 6. For debugging

Debug data page → *Export the model registry now* saves every model, its settings and what the app learnt about it. The file goes to Recordings and is uploaded with the other debug data. It names files only, never folders.
