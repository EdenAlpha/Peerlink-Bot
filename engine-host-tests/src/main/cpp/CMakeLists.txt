cmake_minimum_required(VERSION 3.22.1)
project(peerlinkpatchtests)
set(CMAKE_CXX_STANDARD 17)
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
add_library(peerlinkpatchtests SHARED
    patch_test_jni.cpp
    "${REPO_ROOT}/app/src/main/jni/game/executable_patch.cpp"
    "${REPO_ROOT}/tests/native/executable_patch_checks.cpp")
target_include_directories(peerlinkpatchtests PRIVATE
    "${REPO_ROOT}/app/src/main/jni/game" "${REPO_ROOT}/tests/native")
target_compile_options(peerlinkpatchtests PRIVATE -Wall -Wextra -Werror)
