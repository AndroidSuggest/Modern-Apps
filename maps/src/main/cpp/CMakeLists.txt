cmake_minimum_required(VERSION 4.1.2)
project("offlinerouter")

add_library(offlinerouter SHARED native-lib.cpp)

target_compile_options(offlinerouter PRIVATE
        -O3                 # Maximum optimization
        -ffast-math         # Speed up coordinate math (breaks strict IEEE compliance)
        -fomit-frame-pointer # Frees up register x29 for general use
        -fno-plt            # Encourages direct calls over PLT jumps
)

if(${ANDROID_ABI} MATCHES "arm.*")
    target_compile_options(offlinerouter PRIVATE -mbranch-protection=none)
endif()

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(offlinerouter PRIVATE -march=armv8-a+simd+crypto)
endif()

find_library(log-lib log)
find_library(z-lib z)

# Android SQLite removed: maps offline router now memory-only.
# Previously bundled sqlite3.c (9MB) for traffic_cache.mbtiles MBTiles cache.
# Android's system libsqlite.so is private (not NDK-stable), and Room
# (amenities.db) already uses Android SQLite via androidx.sqlite.
# Traffic cache was just an optimization that invalidated tiles on every
# update (DELETE FROM tiles), so in-memory cache + NetworkClient is sufficient.
target_link_libraries(offlinerouter ${log-lib} ${z-lib})