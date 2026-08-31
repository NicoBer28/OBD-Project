# Este es el archivo CMakeLists.txt principal del proyecto.
# Le dice al sistema de construcción (CMake) cómo configurar todo el proyecto.

# Define la versión mínima de CMake requerida por ESP-IDF.
cmake_minimum_required(VERSION 3.16)

# Incluye la configuración estándar de proyectos de ESP-IDF.
# Esto trae todas las reglas necesarias para compilar código para la ESP32.
include($ENV{IDF_PATH}/tools/cmake/project.cmake)

# Define el nombre de nuestro proyecto. 
# Esto determinará el nombre del archivo binario final (por ejemplo, bt_basico.bin).
project(obdc)

