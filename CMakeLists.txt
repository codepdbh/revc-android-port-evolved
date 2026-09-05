cmake_minimum_required(VERSION 3.14)

set(EXECUTABLE reVC)
set(PROJECT REVC)

if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    if(CMAKE_GENERATOR STREQUAL "Xcode")
        set(CMAKE_OSX_ARCHITECTURES "arm64;x86_64" CACHE STRING "macOS architectures")
        if(NOT CMAKE_OSX_DEPLOYMENT_TARGET)
            set(CMAKE_OSX_DEPLOYMENT_TARGET "10.12" CACHE STRING
                "Minimum macOS version" FORCE)
        endif()
        if(CMAKE_OSX_DEPLOYMENT_TARGET VERSION_LESS "11.0")
            set("CMAKE_XCODE_ATTRIBUTE_MACOSX_DEPLOYMENT_TARGET[arch=arm64]" "11.0")
        endif()
    elseif(NOT CMAKE_OSX_DEPLOYMENT_TARGET)
        if(CMAKE_OSX_ARCHITECTURES STREQUAL "x86_64" OR
           (NOT CMAKE_OSX_ARCHITECTURES AND
            CMAKE_HOST_SYSTEM_PROCESSOR STREQUAL "x86_64"))
            set(CMAKE_OSX_DEPLOYMENT_TARGET "10.12" CACHE STRING
                "Minimum macOS version" FORCE)
        else()
            set(CMAKE_OSX_DEPLOYMENT_TARGET "11.0" CACHE STRING
                "Minimum macOS version" FORCE)
        endif()
    endif()
endif()

project(${EXECUTABLE} C CXX)
set(${PROJECT}_AUTHOR "${PROJECT} Team")
list(APPEND CMAKE_MODULE_PATH "${PROJECT_SOURCE_DIR}/cmake")

include(GetGitRevisionDescription)
get_git_head_revision(GIT_REFSPEC GIT_SHA1 "ALLOW_LOOKING_ABOVE_CMAKE_SOURCE_DIR")
message(STATUS "Building ${CMAKE_PROJECT_NAME} GIT SHA1: ${GIT_SHA1}")

if(APPLE)
    macro(reVC_define_macos_dependency key alias formula library filename)
        list(APPEND ${PROJECT}_MACOS_DEPENDENCIES "${key}")
        set(${PROJECT}_MACOS_${key}_ALIAS "${alias}")
        set(${PROJECT}_MACOS_${key}_FORMULA "${formula}")
        set(${PROJECT}_MACOS_${key}_LIBRARY "${library}")
        set(${PROJECT}_MACOS_${key}_FILENAME "${filename}")
    endmacro()

    reVC_define_macos_dependency(OPENAL OpenAL::OpenAL
        openal-soft openal libopenal.1.dylib)
    reVC_define_macos_dependency(MPG123 MPG123::libmpg123
        mpg123 mpg123 libmpg123.0.dylib)
    reVC_define_macos_dependency(GLFW glfw
        glfw glfw libglfw.3.dylib)

    if(CMAKE_GENERATOR STREQUAL "Xcode")
        set(${PROJECT}_HOMEBREW_ARM64_PREFIX "/opt/homebrew" CACHE PATH "arm64 Homebrew prefix")
        set(${PROJECT}_HOMEBREW_X86_64_PREFIX "/usr/local" CACHE PATH "x86_64 Homebrew prefix")

        function(reVC_macos_dependency target alias dylibs formula library filename)
            if(EXISTS "/opt/local/lib/${filename}")
                add_library(${target} INTERFACE)
                add_library(${alias} ALIAS ${target})
                target_include_directories(${target} INTERFACE "/opt/local/include")
                target_link_libraries(${target} INTERFACE "/opt/local/lib/${filename}")
                set(${dylibs} "/opt/local/lib/${filename}" PARENT_SCOPE)
                return()
            endif()

            set(include_prefix)
            set(link_options)
            set(dependency_dylibs)
            foreach(architecture arm64 x86_64)
                if(architecture IN_LIST CMAKE_OSX_ARCHITECTURES)
                    string(TOUPPER "${architecture}" architecture_upper)
                    set(prefix "${${PROJECT}_HOMEBREW_${architecture_upper}_PREFIX}")
                    set(dylib "${prefix}/opt/${formula}/lib/${filename}")
                    if(NOT EXISTS "${dylib}")
                        message(FATAL_ERROR
                            "${architecture} ${formula} was not found under ${prefix}"
                        )
                    endif()
                    if(NOT include_prefix)
                        set(include_prefix "${prefix}")
                    endif()
                    list(APPEND dependency_dylibs "${dylib}")
                    list(APPEND link_options
                        "SHELL:-Xarch_${architecture} -L${prefix}/opt/${formula}/lib"
                    )
                endif()
            endforeach()

            add_library(${target} INTERFACE)
            add_library(${alias} ALIAS ${target})
            target_include_directories(${target} INTERFACE
                "${include_prefix}/opt/${formula}/include"
            )
            target_link_options(${target} INTERFACE ${link_options} "-l${library}")
            set(${dylibs} "${dependency_dylibs}" PARENT_SCOPE)
        endfunction()

        if("arm64" IN_LIST CMAKE_OSX_ARCHITECTURES OR
           "x86_64" IN_LIST CMAKE_OSX_ARCHITECTURES)
            foreach(dependency IN LISTS ${PROJECT}_MACOS_DEPENDENCIES)
                string(TOLOWER "${dependency}" dependency_lower)
                reVC_macos_dependency(${PROJECT}_macos_${dependency_lower}
                    ${${PROJECT}_MACOS_${dependency}_ALIAS}
                    ${PROJECT}_MACOS_${dependency}_DYLIBS
                    ${${PROJECT}_MACOS_${dependency}_FORMULA}
                    ${${PROJECT}_MACOS_${dependency}_LIBRARY}
                    ${${PROJECT}_MACOS_${dependency}_FILENAME})
            endforeach()
        endif()
    else()
        function(reVC_macos_dependency_dylib output formula filename)
            set(prefix "$ENV{HOMEBREW_PREFIX}")
            set(candidates "/opt/local/lib/${filename}")
            if(CMAKE_OSX_ARCHITECTURES STREQUAL "x86_64" OR
               (NOT CMAKE_OSX_ARCHITECTURES AND
                CMAKE_SYSTEM_PROCESSOR STREQUAL "x86_64"))
                list(APPEND candidates
                    "/usr/local/opt/${formula}/lib/${filename}"
                )
            else()
                if(prefix)
                    list(APPEND candidates "${prefix}/opt/${formula}/lib/${filename}")
                endif()
                list(APPEND candidates
                    "/opt/homebrew/opt/${formula}/lib/${filename}"
                    "/usr/local/opt/${formula}/lib/${filename}"
                )
            endif()
            list(REMOVE_DUPLICATES candidates)
            foreach(candidate IN LISTS candidates)
                if(EXISTS "${candidate}")
                    set(${output} "${candidate}" PARENT_SCOPE)
                    return()
                endif()
            endforeach()
        endfunction()

        foreach(dependency IN LISTS ${PROJECT}_MACOS_DEPENDENCIES)
            reVC_macos_dependency_dylib(
                ${PROJECT}_MACOS_${dependency}_DYLIBS
                ${${PROJECT}_MACOS_${dependency}_FORMULA}
                ${${PROJECT}_MACOS_${dependency}_FILENAME})
        endforeach()
    endif()
endif()


if(NINTENDO_SWITCH)
    list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake/nx")
    include(NXFunctions)
endif()

if(NOT COMMAND reVC_platform_target)
    function(reVC_platform_target)
    endfunction()
endif()

if(WIN32)
    set(${PROJECT}_AUDIOS "OAL" "MSS")
else()
    set(${PROJECT}_AUDIOS "OAL")
endif()

set(${PROJECT}_AUDIO "OAL" CACHE STRING "Audio")

option(${PROJECT}_WITH_OPUS "Build ${EXECUTABLE} with opus support" OFF)
option(${PROJECT}_WITH_LIBSNDFILE "Build ${EXECUTABLE} with libsndfile (instead of internal decoder)" OFF)

set_property(CACHE ${PROJECT}_AUDIO PROPERTY STRINGS ${${PROJECT}_AUDIOS})
message(STATUS "${PROJECT}_AUDIO = ${${PROJECT}_AUDIO} (choices=${${PROJECT}_AUDIOS})")
set("${PROJECT}_AUDIO_${${PROJECT}_AUDIO}" ON)
if(NOT ${PROJECT}_AUDIO IN_LIST ${PROJECT}_AUDIOS)
    message(FATAL_ERROR "Illegal ${PROJECT}_AUDIO=${${PROJECT}_AUDIO}")
endif()

option(${PROJECT}_VENDORED_LIBRW "Use vendored librw" ON)
if(${PROJECT}_VENDORED_LIBRW)
    if(NOT DEFINED LIBRW_PLATFORM AND NOT PS2)
        set(LIBRW_PLATFORM "GL3" CACHE STRING "librw platform")
    endif()
    add_subdirectory(vendor/librw)
else()
    find_package(librw REQUIRED)
endif()
add_subdirectory(src)

if(${PROJECT}_INSTALL)
    install(DIRECTORY gamefiles/ DESTINATION ".")
    if(LIBRW_PLATFORM_NULL)
        set(platform "-null")
    elseif(LIBRW_PLATFORM_PS2)
        set(platform "-ps2")
    elseif(LIBRW_PLATFORM_GL3)
        if(LIBRW_GL3_GFXLIB STREQUAL "GLFW")
            set(platform "-gl3-glfw")
        else()
            set(platform "-gl3-sdl2")
        endif()
    elseif(LIBRW_PLATFORM_D3D9)
        set(platform "-d3d9")
    endif()
    if(${PROJECT}_AUDIO_OAL)
        set(audio "-oal")
    elseif(${PROJECT}_AUDIO_MSS)
        set(audio "-mss")
    endif()
    if(${PROJECT}_WITH_OPUS)
        set(audio "${audio}-opus")
    endif()
    if(NOT LIBRW_PLATFORM_PS2)
        if(WIN32)
            set(os "-win")
        elseif(APPLE)
            set(os "-apple")
        elseif(UNIX)
            set(os "-linux")
        elseif(NINTENDO_SWITCH)
            set(os "-switch")
        else()
            set(compiler "-UNK")
            message(WARNING "Unknown os. Created cpack package will be wrong. (override using cpack -P)")
        endif()
    endif()

    set(CPACK_PACKAGE_NAME "${PROJECT_NAME}${platform}${audio}${os}${compiler}")
    set(CPACK_PACKAGE_DESCRIPTION_SUMMARY "GTA III reversed")
    set(CPACK_PACKAGE_VENDOR "GTAModding")
    # FIXME: missing license (https://github.com/GTAmodding/re3/issues/794)
    # set(CPACK_PACKAGE_DESCRIPTION_FILE "${PROJECT_SOURCE_DIR}/LICENSE")
    # set(CPACK_RESOURCE_FILE_LICENSE "${PROJECT_SOURCE_DIR}/LICENSE")
    set(CPACK_PACKAGE_INSTALL_DIRECTORY "${CPACK_PACKAGE_NAME}")
    set(CPACK_SOURCE_PACKAGE_FILE_NAME "${CPACK_PACKAGE_NAME}")
    set(CPACK_PACKAGE_FILE_NAME "${CPACK_PACKAGE_NAME}")
    set(CPACK_GENERATOR "TXZ")
    include(CPack)
endif()
