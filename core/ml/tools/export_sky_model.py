"""Turns PaddleSeg's PP-MobileSeg export into the sky model MaskModel.SKY expects.

Usage: python export_sky_model.py pp_mobileseg_base.onnx sky_base.onnx

The input is PaddleSeg 2.9's `tools/export.py` run on the ADE20K weights with `--output_op none` and no
`--input_shape` (dynamic size), converted by paddle2onnx 1.3.1 at opset 13:

    python tools/export.py --config configs/pp_mobileseg/pp_mobileseg_base_ade20k_512x512_80k.yml \
        --model_path model.pdparams --save_dir export --output_op none
    paddle2onnx --model_dir export --model_filename model.pdmodel \
        --params_filename model.pdiparams --save_file pp_mobileseg_base.onnx --opset_version 13

Its output is 150 classes at full input resolution, about 530 MB of floats at 768 × 1152. This cuts the
graph before that final Resize and appends a softmax and the slice of class 2 (sky), so the output is one
channel at 1/8 of the input. Needs the `onnx` package.
"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

SKY = 2


def main(source, target):
    model = onnx.load(source)
    graph = model.graph
    resize = next(n for n in graph.node if graph.output[0].name in n.output)
    assert resize.op_type == "Resize", resize.op_type
    logits = resize.input[0]
    graph.initializer.extend(
        numpy_helper.from_array(np.array(v, np.int64), name)
        for name, v in (("sky_start", [SKY]), ("sky_end", [SKY + 1]), ("sky_axis", [1]))
    )
    graph.node.extend([
        helper.make_node("Softmax", [logits], ["probabilities"], axis=1),
        helper.make_node("Slice", ["probabilities", "sky_start", "sky_end", "sky_axis"], ["sky"]),
    ])
    del graph.output[:]
    graph.output.append(helper.make_tensor_value_info("sky", TensorProto.FLOAT, ["n", 1, "h", "w"]))
    # Keep only what the new output needs; the Resize and its shape arithmetic go.
    needed, kept = {"sky"}, []
    for node in reversed(list(graph.node)):
        if any(o in needed for o in node.output):
            kept.append(node)
            needed.update(i for i in node.input if i)
    kept.reverse()
    del graph.node[:]
    graph.node.extend(kept)
    initializers = [i for i in graph.initializer if i.name in needed]
    del graph.initializer[:]
    graph.initializer.extend(initializers)
    onnx.checker.check_model(model)
    onnx.save(model, target)


if __name__ == "__main__":
    main(*sys.argv[1:3])
