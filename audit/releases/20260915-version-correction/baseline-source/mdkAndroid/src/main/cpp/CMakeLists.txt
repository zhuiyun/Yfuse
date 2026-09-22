cmake_minimum_required(VERSION 3.22.1)
project(yfuse_mdk_android LANGUAGES CXX)

get_filename_component(
    MDK_SDK_DIR
    "${CMAKE_CURRENT_LIST_DIR}/../../../../composeApp/libs/mdk-sdk"
    ABSOLUTE
)

if(NOT EXISTS "${MDK_SDK_DIR}/lib/cmake/FindMDK.cmake")
    message(FATAL_ERROR "MDK SDK not found. Run scripts/fetch-engines.sh first.")
endif()

include("${MDK_SDK_DIR}/lib/cmake/FindMDK.cmake")

# MDK loads dav1d dynamically on Android, so FindMDK does not add it to the
# imported target graph. Add it explicitly to make Gradle package the runtime.
if(MDK_DAV1D AND EXISTS "${MDK_DAV1D}")
    add_library(yfuse-mdk-dav1d SHARED IMPORTED)
    set_target_properties(
        yfuse-mdk-dav1d
        PROPERTIES IMPORTED_LOCATION "${MDK_DAV1D}"
    )
    target_link_libraries(mdk INTERFACE yfuse-mdk-dav1d)
endif()

add_library(yfuse-mdk-jni SHARED MDKPlayerJNI.cpp)
target_compile_features(yfuse-mdk-jni PRIVATE cxx_std_17)
target_link_options(
    yfuse-mdk-jni
    PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)
target_link_libraries(yfuse-mdk-jni PRIVATE mdk log android)
