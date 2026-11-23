# PHASE 8, step G3: makes a tiny random llama-family GGUF model (about 0.5 MB) with llama.cpp's own gguf-py writer, so the
# native test needs no download. Its answers are nonsense; the test checks the engine (load, stream, cancel, free), not words.
# Usage: python3 make_tiny_model.py <path to llama.cpp gguf-py> <out.gguf>
import sys, numpy as np
sys.path.insert(0, sys.argv[1])
import gguf
out = sys.argv[2]
w = gguf.GGUFWriter(out, "llama")
n_embd, n_head, n_layer, n_ff, ctx = 64, 4, 2, 128, 128
toks = ["<unk>", "<s>", "</s>"] + [f"<0x{i:02X}>" for i in range(256)] + ["▁Once", "▁upon", "▁a", "▁time", "▁word", "▁the"]
types = [2, 3, 3] + [6] * 256 + [1] * 6
w.add_name("tiny-test"); w.add_context_length(ctx); w.add_embedding_length(n_embd); w.add_block_count(n_layer)
w.add_feed_forward_length(n_ff); w.add_head_count(n_head); w.add_head_count_kv(n_head); w.add_rope_dimension_count(n_embd // n_head)
w.add_layer_norm_rms_eps(1e-5); w.add_file_type(0)
w.add_tokenizer_model("llama"); w.add_token_list(toks); w.add_token_scores([0.0] * len(toks)); w.add_token_types(types)
w.add_bos_token_id(1); w.add_eos_token_id(2); w.add_unk_token_id(0)
rng = np.random.default_rng(0)
V = len(toks)
def t(name, *shape): w.add_tensor(name, (rng.standard_normal(shape) * 0.02).astype(np.float32))
t("token_embd.weight", V, n_embd); w.add_tensor("output_norm.weight", np.ones(n_embd, np.float32)); t("output.weight", V, n_embd)
for i in range(n_layer):
    w.add_tensor(f"blk.{i}.attn_norm.weight", np.ones(n_embd, np.float32))
    for n in ("attn_q", "attn_k", "attn_v", "attn_output"): t(f"blk.{i}.{n}.weight", n_embd, n_embd)
    w.add_tensor(f"blk.{i}.ffn_norm.weight", np.ones(n_embd, np.float32))
    t(f"blk.{i}.ffn_gate.weight", n_ff, n_embd); t(f"blk.{i}.ffn_up.weight", n_ff, n_embd); t(f"blk.{i}.ffn_down.weight", n_embd, n_ff)
w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()
print("ok", out)
