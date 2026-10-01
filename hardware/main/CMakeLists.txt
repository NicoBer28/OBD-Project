set(OBD_DRIVER "TWAI")
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/driver_config.cmake")
    include("${CMAKE_CURRENT_SOURCE_DIR}/driver_config.cmake")
endif()

if(OBD_DRIVER STREQUAL "MCP2515")
    set(main_src "main_mcp.cpp")
else()
    set(main_src "main_twai.cpp")
endif()

idf_component_register(SRCS "${main_src}" "obd_can.cpp"
                       INCLUDE_DIRS "."
                       REQUIRES esp_driver_twai
                       PRIV_REQUIRES esp_driver_gpio)

if(OBD_DRIVER STREQUAL "MCP2515")
    target_compile_definitions(${COMPONENT_LIB} PRIVATE CONFIG_OBD_USE_MCP2515=1)
else()
    target_compile_definitions(${COMPONENT_LIB} PRIVATE CONFIG_OBD_USE_TWAI=1)
endif()
