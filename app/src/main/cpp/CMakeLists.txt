cmake_minimum_required(VERSION 3.22.1)
project(recon_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Real C++ core (geo.cpp) + thin JNI glue (native-lib.cpp) in one shared library.
add_library(recon-native SHARED
        native-lib.cpp
        geo.cpp)

# libc++ (bundled with the NDK) is linked automatically; log lib is handy for future use.
find_library(log-lib log)
target_link_libraries(recon-native ${log-lib})
