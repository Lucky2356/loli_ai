# Офлайн-модель Лоли: llama.cpp (MIT) + тонкая JNI-обёртка. Исходники llama.cpp скачивает Gradle
# (фиксированная версия, проверка sha256) и передаёт путь в LLAMA_SRC.
cmake_minimum_required(VERSION 3.22.1)
project(loli_llm C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_SRC OR NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_SRC не задан или пуст: ${LLAMA_SRC}")
endif()

# Всё собирается статически в одну библиотеку libloli_llm.so, только нужное для вывода текста.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
# ARMv8.2 с dot product: все телефоны примерно с 2018 года. На более старых Лоли модель не предлагает (проверка в Kotlin).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_SRC} llama-build EXCLUDE_FROM_ALL)

add_library(loli_llm SHARED loli_llm.cpp)
target_link_libraries(loli_llm PRIVATE llama android log)
target_compile_options(loli_llm PRIVATE -O3)
