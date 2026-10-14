# `:camera` bundled model assets

## `selfie_segmentation.maml`

Apache-2.0, from https://huggingface.co/onnx-community/mediapipe_selfie_segmentation
@ `be49485c8e027524be38591817fc5cd31bd9d00e` (`onnx/model.onnx`, 462,352 B fp32,
SHA-256 `3241ac4a…4531aad`) — an ONNX export of Google's MediaPipe Selfie
Segmentation, the model purpose-built for exactly this job.

Not the ONNX itself: `scripts/ml/fetch_and_convert.sh` converts it to the fp16
`.maml` weights container that `:library:ml` reads, which is half the size and the
only form anything here loads. The source ONNX SHA-256 is in the `.maml` header, so
a shipped asset traces back to this line without needing the ONNX on disk.

Run at 256×256 RGB, rescale 1/255 and **no** mean/std (`do_normalize: false`
upstream), output a single 256×256 alpha channel through a sigmoid. Consumed by
`SelfieSegmenter` from `BokehAnalyzer` and `PortraitBokeh.StillBokehRenderer`.

This replaces `erdnet.{param,bin}`, which was added in `72dde80df` with no upstream
URL, license or conversion recipe. It was Caffe-derived from nihui's ncnn demo
family, had no obtainable ONNX, and so could not be attributed or rebuilt — see
`SUPPLY_CHAIN_RISKS.md`. The masks differ from erdnet's; this is a different model,
not a port.

Update: `./scripts/ml/fetch_and_convert.sh --update`, which reports the moved
SHA-256 to re-pin. If the ordered layer table changed too, the converter says so
and the hardcoded forward pass in
`library/ml/src/main/rust/src/nets/selfie.rs` has to be updated with it.

This file is inside `assets/`, so it is packaged into the APK. That is deliberate
rather than overlooked: it is ~1 KB, and Apache-2.0 §4 asks that attribution
notices travel with the distributed work, which here is the weights next to it.

## `modnet.maml`

Apache-2.0, from the official MODNet repository https://github.com/ZHKKKe/MODNet
(MobileNetV2 backbone, `modnet_photographic_portrait_matting` weights), via the
mirror https://huggingface.co/DavG25/modnet-pretrained-models
(`models/modnet_photographic_portrait_matting.onnx`, 25,888,640 B fp32,
SHA-256 `07c308cf…84df9`) — MODNet portrait matting, the model purpose-built
for trimap-free portrait alpha with hair detail.

Not the ONNX itself: `scripts/ml/modnet_fold.py` folds the export's Slice-fed
batch norms into their convolutions and rewrites the SE block's MatMuls as 1x1
convolutions, then converts to the fp16 `.maml` weights container that
`:library:ml` reads. The source ONNX SHA-256 is in the `.maml` header, so
a shipped asset traces back to this line without needing the ONNX on disk.

Run at 512×512 RGB, `(value / 255 - 0.5) / 0.5` (the demo's
`ToTensor` + `Normalize((0.5, 0.5, 0.5), (0.5, 0.5, 0.5))`), output a single
512×512 alpha channel through a sigmoid. Consumed by `ModnetSegmenter` from
`PortraitBokeh.StillBokehRenderer` — still capture only; the 15 fps preview
keeps `selfie_segmentation.maml` above.

Update: re-download the mirror file, verify the SHA-256, then
`./scripts/ml/modnet_fold.py model.onnx --graph modnet -o
camera/src/main/assets/modnet.maml`, which reports a moved digest to re-pin.
If the ordered layer table changed too, the converter says so
and the hardcoded forward pass in
`library/ml/src/main/rust/src/nets/modnet.rs` has to be updated with it.
