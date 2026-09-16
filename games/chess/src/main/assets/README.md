# `maia3_vulkan_int8wo.pte`
Maia3-5M, the chess AI. Converted from **[`UofTCSSLab/Maia3-5M`](https://huggingface.co/UofTCSSLab/Maia3-5M)**
(`maia3-5m.pt`, 20.0 MB fp32) to an ExecuTorch export for the Vulkan delegate
(int8 weight-only, 6.3 MB, torch-level cos ≥ 0.9990 vs the ship w4 rung):
`tokens [1,64,97]` (square-major), `self_elo`/`oppo_elo [1]` in, `moves [1,4352]`
out. Exported with executorch 1.5.0+cpu — see `analysis/et-maia/export_maia.py`
(gitignored staging, not shipped). This is the only Maia runtime: a missing file,
an unlinked Vulkan delegate, or a failed run fails closed (no move), with no
`.tflite` fallback.
## What it is
An encoder-only transformer over 64 square tokens - width 256, 8 blocks, 8 heads, 5,230,084
parameters. Upstream calls the architecture "Chessformer"; the code is
[CSSLab/maia3](https://github.com/CSSLab/maia3).
It **predicts human moves** rather than searching for good ones. One forward pass per move, no
tree. Strength is a model input (`SelfElo` / `OppoElo`, 0..5000), so this one file covers all
four difficulty levels - see `Difficulty` in
`games/chess/src/main/java/com/vayunmathur/games/chess/util/MaiaEngine.kt`.
It replaces Stockfish and its 86 MB `nn-71d6d32cb962.nnue`, which is where the APK's ~79 MB
reduction comes from.
## Licence
Maia3's weights are released by the University of Toronto's Computational Social Science Lab.
See the model card at the link above for terms.
