#!/usr/bin/env python3
"""Export Maia3-5M to ONNX for the `:games:chess` ORT port.

Reads the pinned `maia3-5m.pt` checkpoint through CSSLab/maia3's own `MAIA3Model`
(`build/maia/src/maia3_models.py`), renames the checkpoint's `smolgen_*` names to the
module's `gab_*` names (as `maia_parity.py` does), and exports a single static graph:

    tokens    float32 [1, 64, 97]   square-major planes x8 plies + clk column
    self_elo  float32 [1]           0..5000
    oppo_elo  float32 [1]           0..5000
    -> moves  float32 [1, 4352]     from*64+to, then file-promotion head

Only the move head is exported: value/ponder never leave the engine. Dropout is disabled
by `eval()` + `torch.no_grad()`; the export is fp32 (the app quantises nothing — the old
`.maml` was int8, the ONNX runs fp32 at 5.2M params, ~21 MB).

Usage:
    python scripts/ml/maia_to_onnx.py --checkpoint build/maia/ckpt/maia3-5m.pt \
        -o analysis/onnx_work/maia/maia3-5m.onnx
"""

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

CONFIG = {
    "history": 8,
    "use_padding": True,
    "include_time_info": False,
    "dim_emb": 128,
    "num_blocks": 8,
    "mlp_ratio": 2.0,
    "dropout": 0.0,
    "use_gab": True,
    "use_relative_bias": False,
    "use_absolute_pe": False,
    "use_rms_norm": True,
    "omit_qkv_biases": True,
    "activation": "gelu",
    "dim_vit": 256,
    "head_hid_dim": 256,
    "num_heads": 8,
    "gab_gen_size": 64,
    "gab_per_square_dim": 0,
    "gab_intermediate_dim": 64,
}


class Spec:
    def __init__(self, held):
        self.__dict__.update(held)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("-o", "--out", required=True)
    args = parser.parse_args()

    import torch
    import torch.nn as nn

    sys.path.insert(0, os.path.join(ROOT, "build", "maia", "src"))
    from maia3_models import MAIA3Model

    model = MAIA3Model(Spec(CONFIG))
    state = torch.load(args.checkpoint, map_location="cpu", weights_only=True)
    renamed = {
        name.replace("smolgen_shared_weight", "gab_shared_weight").replace(
            "self_attn.smolgen_weight", "self_attn.gab_weight"
        ): value
        for name, value in state.items()
    }
    model.load_state_dict(renamed, strict=True)
    model.eval()

    # The TorchScript exporter has no `aten::rms_norm` symbol. Replace every RMSNorm with
    # the same math spelled out of portable ops (weight * x / sqrt(mean(x^2) + eps)).
    class ManualRMSNorm(nn.Module):
        def __init__(self, rms):
            super().__init__()
            self.weight = rms.weight
            # `eps=None` means torch's default: float32 eps (2^-23), matching nets::maia.
            self.eps = float(rms.eps) if rms.eps is not None else 1.1920929e-7

        def forward(self, x):
            mean_square = x.pow(2).mean(dim=-1, keepdim=True)
            normalized = x * torch.rsqrt(mean_square + self.eps)
            return normalized * self.weight

    def swap_rms(module):
        for name, child in list(module.named_children()):
            if isinstance(child, nn.RMSNorm):
                module.add_module(name, ManualRMSNorm(child))
            else:
                swap_rms(child)

    swap_rms(model)

    tokens = torch.zeros(1, 64, 97)
    self_elo = torch.tensor([1500.0])
    oppo_elo = torch.tensor([1500.0])

    class MoveHead(torch.nn.Module):
        def __init__(self, base):
            super().__init__()
            self.base = base

        def forward(self, tokens, self_elos, oppo_elos):
            move, _value, _ponder = self.base(tokens, self_elos, oppo_elos)
            return move

    wrapped = MoveHead(model)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    torch.onnx.export(
        wrapped,
        (tokens, self_elo, oppo_elo),
        args.out,
        input_names=["tokens", "self_elo", "oppo_elo"],
        output_names=["moves"],
        dynamic_axes={
            "tokens": {0: "batch"},
            "self_elo": {0: "batch"},
            "oppo_elo": {0: "batch"},
            "moves": {0: "batch"},
        },
        opset_version=17,
        dynamo=False,
    )
    print(f"wrote {args.out}")


if __name__ == "__main__":
    raise SystemExit(main())
