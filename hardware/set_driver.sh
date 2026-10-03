#!/bin/bash

# Script para preparar CMakeLists.txt, dependencias y target de compilacion

DRIVER=$1
TARGET=${2:-esp32}

if [ -z "$DRIVER" ]; then
    echo "Uso: ./set_driver.sh [twai|mcp|dummy] [target]"
    echo "Ejemplos:"
    echo "  ./set_driver.sh twai         (Usa esp32 por defecto)"
    echo "  ./set_driver.sh mcp esp32s3  (Configura MCP2515 para la placa ESP32-S3)"
    echo "  ./set_driver.sh dummy        (Configura version de prueba sin hardware)"
    exit 1
fi

# 1. Configurar el Target de ESP-IDF
echo "> Configurando el target a: $TARGET"
idf.py set-target $TARGET

# 2. Configurar CMake para elegir el driver
if [ "$DRIVER" == "twai" ]; then
    echo 'set(OBD_DRIVER "TWAI")' > main/driver_config.cmake
    echo "> Proyecto configurado para usar TWAI (Interno)."
elif [ "$DRIVER" == "mcp" ]; then
    echo 'set(OBD_DRIVER "MCP2515")' > main/driver_config.cmake
    echo "> Proyecto configurado para usar MCP2515 (SPI)."
elif [ "$DRIVER" == "dummy" ]; then
    echo 'set(OBD_DRIVER "DUMMY")' > main/driver_config.cmake
    echo "> Proyecto configurado para usar DUMMY (Random responses)."
else
    echo "x Driver desconocido: $DRIVER. Usa 'twai', 'mcp' o 'dummy'."
    exit 1
fi

echo "> Listo! Ejecuta: idf.py build"
