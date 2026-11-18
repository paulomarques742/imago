#!/usr/bin/env bash
# Puts the mask models and manifest.json in the public `ml-models` bucket of the linked Supabase project.
#
# Usage, from backend/ (where the project link lives): ../core/ml/tools/upload-models.sh MODELS_DIR
#
# MODELS_DIR holds u2netp.onnx and sky_base.onnx, as a relative path: the CLI reads "C:/..." as a URL.
# Each file is checked against manifest.json first, so what is uploaded is exactly what the app will accept.
# The CLI asks before writing to a hosted project; running this script is that answer (--yes).
# A new model version goes to a new folder and a new
# manifest entry; files already published are never overwritten, because a recipe names the version it used.
set -euo pipefail

# On Git Bash, Windows paths throughout, and no conversion of arguments: it turns ss:///bucket into a local path.
HERE="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"
export MSYS_NO_PATHCONV=1
MODELS="${1:?Usage: upload-models.sh MODELS_DIR}"
MANIFEST="$HERE/manifest.json"
# For the CLI, the manifest by the relative path the script was called by.
MANIFEST_UPLOAD="$(dirname "$0")/manifest.json"

python - "$MANIFEST" "$MODELS" <<'EOF'
import hashlib, json, os, sys
manifest, models = sys.argv[1], sys.argv[2]
for model in json.load(open(manifest))["models"]:
    path = os.path.join(models, os.path.basename(model["file"]))
    data = open(path, "rb").read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != model["sha256"] or len(data) != model["size"]:
        sys.exit(f"{path} does not match the manifest ({digest}, {len(data)} bytes)")
    print(f"{model['id']} {model['version']}: matches")
EOF

npx supabase --experimental storage cp "$MODELS/u2netp.onnx" ss:///ml-models/u2netp/1/u2netp.onnx \
    --remote --yes --content-type application/octet-stream --cache-control "max-age=31536000, immutable"
npx supabase --experimental storage cp "$MODELS/sky_base.onnx" ss:///ml-models/ppmobileseg-base-ade20k-sky/1/sky_base.onnx \
    --remote --yes --content-type application/octet-stream --cache-control "max-age=31536000, immutable"
# The manifest changes when a model is added, so it is cached briefly.
npx supabase --experimental storage cp "$MANIFEST_UPLOAD" ss:///ml-models/manifest.json \
    --remote --yes --content-type application/json --cache-control "max-age=300"
