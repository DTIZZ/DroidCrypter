cmake_minimum_required(VERSION 3.22.1)
project(securewipe CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(securewipe SHARED secure_wipe.cpp)
target_compile_options(securewipe PRIVATE -O2 -fvisibility=hidden -Wall -Wextra -Werror)
# 16 KB page alignment (Android 15+ devices with 16 KB pages).
target_link_options(securewipe PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
