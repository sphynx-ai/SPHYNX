#!/usr/bin/env python3
# Phase 9 M9: rebuilds app/src/main/assets/model_catalog.json from Hugging Face (size, SHA-256 and the pinned commit of
# every file), for a PR that changes the download list. Edit the list W below, then run:
#   python3 tools/model-catalog/build.py app/src/main/assets/model_catalog.json
# Gated models (that need a Hugging Face login) are refused. Set "checked" to the day you ran it.
import json,urllib.request,sys
# (id, title, repo, file, saveAs, job, pictures, note, mmproj file, mmproj saveAs)
W=[
("gemma4-e2b","Gemma 4 E2B (LiteRT-LM)","litert-community/gemma-4-E2B-it-litert-lm","gemma-4-E2B-it.litertlm",None,"strategic",True,"The strategic model Sphynx is built around: reads pictures. Needs about 3 GB of free memory.",None,None),
("qwen3-0.6b-lite","Qwen3 0.6B (LiteRT-LM)","litert-community/Qwen3-0.6B","Qwen3-0.6B.litertlm",None,"tactical",False,"A small text-only model for the tactical job.",None,None),
("qwen2.5-1.5b-lite","Qwen2.5 1.5B (LiteRT-LM)","litert-community/Qwen2.5-1.5B-Instruct","Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",None,"strategic",False,"Text only; a strategic model for phones without room for Gemma 4.",None,None),
("smolvlm-256m","SmolVLM 256M (GGUF, pictures)","ggml-org/SmolVLM-256M-Instruct-GGUF","SmolVLM-256M-Instruct-Q8_0.gguf",None,"tactical",True,"The smallest model that reads pictures; for the tactical job.","mmproj-SmolVLM-256M-Instruct-Q8_0.gguf",None),
("smolvlm2-500m","SmolVLM2 500M (GGUF, pictures)","ggml-org/SmolVLM2-500M-Video-Instruct-GGUF","SmolVLM2-500M-Video-Instruct-Q8_0.gguf",None,"tactical",True,"A small model that reads pictures; for the tactical job.","mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf",None),
("qwen2.5-0.5b","Qwen2.5 0.5B (GGUF)","Qwen/Qwen2.5-0.5B-Instruct-GGUF","qwen2.5-0.5b-instruct-q4_k_m.gguf",None,"tactical",False,"A small, fast text-only model.",None,None),
("gemma3-1b","Gemma 3 1B (GGUF)","ggml-org/gemma-3-1b-it-GGUF","gemma-3-1b-it-Q4_K_M.gguf",None,"tactical",False,"Text only.",None,None),
("llama3.2-1b","Llama 3.2 1B (GGUF)","bartowski/Llama-3.2-1B-Instruct-GGUF","Llama-3.2-1B-Instruct-Q4_K_M.gguf",None,"tactical",False,"Text only. Llama 3.2 licence.",None,None),
("qwen3-1.7b","Qwen3 1.7B (GGUF)","unsloth/Qwen3-1.7B-GGUF","Qwen3-1.7B-Q4_K_M.gguf",None,"strategic",False,"Text only; a stronger small strategic model.",None,None),
("qwen2.5-vl-3b","Qwen2.5-VL 3B (GGUF, pictures)","ggml-org/Qwen2.5-VL-3B-Instruct-GGUF","Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",None,"strategic",True,"Reads pictures; for phones with 8 GB of memory or more.","mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf",None),
("gemma3-4b","Gemma 3 4B (GGUF, pictures)","ggml-org/gemma-3-4b-it-GGUF","gemma-3-4b-it-Q4_K_M.gguf",None,"strategic",True,"Reads pictures; for phones with 8 GB of memory or more.","mmproj-model-f16.gguf","mmproj-gemma-3-4b-it-f16.gguf"),
]
cache={}
def info(repo):
    if repo not in cache:
        d=json.load(urllib.request.urlopen(f"https://huggingface.co/api/models/{repo}?blobs=true",timeout=30))
        assert d.get("gated") in (False,None), repo
        cache[repo]=d
    return cache[repo]
def f(repo,name,save):
    d=info(repo); s=next(x for x in d["siblings"] if x["rfilename"]==name)
    o={"file":name,"size":s["size"],"sha256":s["lfs"]["sha256"]}
    if save: o["saveAs"]=save
    return o
out=[]
for (i,t,repo,fn,sv,job,pic,note,mp,mps) in W:
    d=info(repo)
    e={"id":i,"title":t,"repo":repo,"revision":d["sha"],"license":(d.get("cardData") or {}).get("license"),"job":job,"pictures":pic,"note":note}
    e.update(f(repo,fn,sv))
    if mp: e["mmproj"]=f(repo,mp,mps)
    out.append(e)
json.dump({"schema":1,"checked":"2026-11-25","models":out},open(sys.argv[1],"w"),indent=1)
print(len(out))
