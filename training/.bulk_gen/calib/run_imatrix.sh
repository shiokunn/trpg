#!/usr/bin/env bash
# Kaggle 環境で llama-imatrix を実行するスクリプト。
#
# 使い方:
#   1. このディレクトリの calib.txt (または calib_raw.txt) を Kaggle にアップロードする
#   2. Kaggle ノートブックで:
#        !cp /kaggle/input/<dataset>/calib.txt /kaggle/working/calib.txt
#   3. このスクリプトを実行:
#        !bash /kaggle/working/run_imatrix.sh
#
# ※ OUT とモデル名は実際の環境に合わせて変更してください。

set -euo pipefail

# キャリブレーション入力（ラベル付き版 / 生テキスト版を切り替え可能）
CALIB="${CALIB:-/kaggle/working/calib.txt}"

# 出力先ディレクトリとモデル（ご提示の例の値）
OUT="${OUT:-/kaggle/working/out}"
MODEL="${MODEL:-$OUT/LFM3-TRPG-3418-F16.gguf}"
IMATRIX="$OUT/LFM3-TRPG-3418-imatrix.dat"

if [ ! -f "$CALIB" ]; then
  echo "ERROR: calibration file not found: $CALIB" >&2
  exit 1
fi
if [ ! -f "$MODEL" ]; then
  echo "ERROR: model file not found: $MODEL" >&2
  exit 1
fi

cd /kaggle/working/llama.cpp

./build/bin/llama-imatrix \
  -m "$MODEL" \
  -f "$CALIB" \
  -o "$IMATRIX" \
  --chunks 100 \
  -ngl 99

echo "Done: $IMATRIX"
