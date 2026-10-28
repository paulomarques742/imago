cmake_minimum_required(VERSION 3.22)
project(imago_raw LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(zlib_archive "${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/zlib-1.3.2.zip")
file(SHA256 "${zlib_archive}" zlib_hash)
if(NOT zlib_hash STREQUAL "e8bf55f3017aa181690990cb58a994e77885da140609fc8f94abe9b65d2cae28")
    message(FATAL_ERROR "The zlib source archive checksum does not match.")
endif()
set(zlib_root "${CMAKE_CURRENT_BINARY_DIR}/vendor/zlib-1.3.2")
if(NOT EXISTS "${zlib_root}/zlib.h")
    file(ARCHIVE_EXTRACT INPUT "${zlib_archive}" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/vendor")
endif()
set(ZLIB_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ZLIB_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZLIB_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${zlib_root}" "${CMAKE_CURRENT_BINARY_DIR}/zlib" EXCLUDE_FROM_ALL)

# Keep the original, unmodified sources available for the CDDL distribution mode.
set(archive "${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/LibRaw-0.22.2.zip")
file(SHA256 "${archive}" archive_hash)
if(NOT archive_hash STREQUAL "f4448371523f9960d26a131ed9dc21bee49f967adbcc4fb6f56b9b47c2ba87c6")
    message(FATAL_ERROR "The LibRaw source archive checksum does not match.")
endif()
set(raw_root "${CMAKE_CURRENT_BINARY_DIR}/vendor/LibRaw-0.22.2")
if(NOT EXISTS "${raw_root}/libraw/libraw.h")
    file(ARCHIVE_EXTRACT INPUT "${archive}" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/vendor")
endif()
file(GLOB_RECURSE raw_sources CONFIGURE_DEPENDS "${raw_root}/src/*.cpp")
# LibRaw ships alternative placeholder implementations that its Makefile does not compile.
list(FILTER raw_sources EXCLUDE REGEX "_ph\\.cpp$")
add_library(imago_libraw STATIC ${raw_sources})
target_include_directories(imago_libraw PUBLIC "${raw_root}")
target_compile_definitions(imago_libraw PUBLIC LIBRAW_NODLL PRIVATE LIBRAW_LIBRARY_BUILD USE_ZLIB)
target_link_libraries(imago_libraw PRIVATE zlibstatic)
if(MSVC)
    target_compile_definitions(imago_libraw PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX)
    target_compile_options(imago_libraw PRIVATE /w)
else()
    # Optimised in every build type: nobody steps through LibRaw, and unoptimised it develops a RAW
    # several times slower, so a debug build measured the wrong thing. The flag comes after the build
    # type's own, and the last -O wins.
    target_compile_options(imago_libraw PRIVATE -w -O3)
    target_compile_options(zlibstatic PRIVATE -O3)
endif()
if(ANDROID)
    # LibRaw's Fuji and CR3 decoders, its X-Trans demosaic and raw2image run their loops in parallel
    # with OpenMP; without -fopenmp the pragmas are ignored and a phone develops on one core. The
    # parallel loops only poll LibRaw's own cancel flag, never the JNI progress callback, so no other
    # thread touches the JVM. The runtime is linked in statically: there is no libomp on the device.
    target_compile_options(imago_libraw PRIVATE -fopenmp)
endif()

add_library(imago_raw SHARED RawBridge.cpp)
target_link_libraries(imago_raw PRIVATE imago_libraw)
if(NOT MSVC)
    target_compile_options(imago_raw PRIVATE -O3)
endif()
if(ANDROID)
    target_link_options(imago_raw PRIVATE -fopenmp -static-openmp)
endif()
if(NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(imago_raw PRIVATE ${JNI_INCLUDE_DIRS})
endif()

