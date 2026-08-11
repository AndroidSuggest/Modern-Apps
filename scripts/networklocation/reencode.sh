#!/usr/bin/env bash
set -euo pipefail
cd /home/vayun/geocoder-scratch
echo ==\> Compiling
g++ -O2 -fopenmp -std=c++17 -I build /mnt/c/Users/Vayun/Documents/code/Modern-Apps/scripts/geocoder_gen.cpp build/simdjson.cpp /lib/x86_64-linux-gnu/libzstd.so.1 -o build/geocoder_gen2
echo ==\> Reencoding
build/geocoder_gen2 reencode geocoder.geodb geocoder2.geodb
echo ==\> Result
ls -la geocoder2.geodb
sha256sum geocoder2.geodb
