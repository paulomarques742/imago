"""Reference masks from the official ONNX Runtime, with the preprocessing core/ml implements in Kotlin.

Usage: python reference_masks.py MODELS_DIR OUT_DIR PHOTO...

Writes PHOTO_subject.png and PHOTO_sky.png for each photo. Run it with the official `onnxruntime` wheel
of the version in prebuilt/ to refresh src/testFixtures/resources/masks.
"""
import os, sys, numpy as np, onnxruntime as ort
from PIL import Image, ImageOps

MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])

def area_weights(src, dst):
    # Exact box resampling: output i covers source [i*src/dst, (i+1)*src/dst).
    w = np.zeros((dst, src))
    s = src / dst
    for i in range(dst):
        a, b = i * s, (i + 1) * s
        for j in range(int(np.floor(a)), min(src, int(np.ceil(b)))):
            w[i, j] = min(b, j + 1) - max(a, j)
        w[i] /= w[i].sum()
    return w

def tensor(rgb, w, h):
    a = rgb.astype(np.float64) / 255.0
    a = np.einsum("yx,xwc->ywc", area_weights(rgb.shape[0], h), a)
    a = np.einsum("xw,ywc->yxc", area_weights(rgb.shape[1], w), a)
    return ((a - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)

def sky_size(w, h, short=768):
    k = short / min(w, h)
    return [max(32, int(round(v * k / 32)) * 32) for v in (w, h)]

models, out = sys.argv[1], sys.argv[2]
u2 = ort.InferenceSession(os.path.join(models, "u2netp.onnx"))
sky = ort.InferenceSession(os.path.join(models, "sky_base.onnx"))
for path in sys.argv[3:]:
    name = os.path.splitext(os.path.basename(path))[0]
    rgb = np.asarray(ImageOps.exif_transpose(Image.open(path)).convert("RGB"))
    h, w = rgb.shape[:2]
    d = u2.run(None, {"input.1": tensor(rgb, 320, 320)})[0][0, 0]
    d = (d - d.min()) / max(d.max() - d.min(), 1e-12)
    sw, sh = sky_size(w, h)
    s = sky.run(None, {"x": tensor(rgb, sw, sh)})[0][0, 0]
    for kind, m in (("subject", d), ("sky", s)):
        Image.fromarray(np.round(np.clip(m, 0, 1) * 255).astype(np.uint8)).save(os.path.join(out, f"{name}_{kind}.png"))
        print(name, kind, m.shape, float(m.mean()))
