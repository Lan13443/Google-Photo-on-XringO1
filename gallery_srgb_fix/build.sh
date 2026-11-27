#!/usr/bin/env bash
set -euo pipefail

# Build pure-C Zygisk Next Native module (zero Termux RUNPATH, bionic-only NEEDED: liblog.so, libdl.so, libc.so)
OUT_DIR="${OUT_DIR:-out}"
mkdir -p "${OUT_DIR}/lib"

clang -target aarch64-linux-android30 -c -fPIC -O2 -mno-outline-atomics \
  -Wall -Wextra -fvisibility=hidden -o "${OUT_DIR}/module.o" module.c

ld.lld -z now -z relro -z max-page-size=16384 --hash-style=gnu -shared \
  -soname libgallery_srgb_fix.so -L/system/lib64 "${OUT_DIR}/module.o" \
  -llog -ldl -lc -o "${OUT_DIR}/lib/arm64-v8a.so"

cp module.prop zn_modules.txt "${OUT_DIR}/"
rm -f "${OUT_DIR}/module.o"

echo "Built ${OUT_DIR}/lib/arm64-v8a.so"
