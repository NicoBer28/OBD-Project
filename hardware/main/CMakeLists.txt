# Este archivo le indica a CMake cómo compilar el código fuente específico de la carpeta 'main'.

# idf_component_register es una función propia de ESP-IDF para registrar esta carpeta como un "componente".
# SRCS: Lista los archivos de código fuente que deben compilarse (en este caso, solo main.c).
# INCLUDE_DIRS: Directorios donde el compilador debe buscar archivos de cabecera (.h). "." es el directorio actual.
# REQUIRES: Lista de componentes de ESP-IDF que nuestro código necesita. 
#   - 'nvs_flash': Para guardar datos no volátiles (requerido internamente por el Bluetooth).
#   - 'bt': El componente oficial de Bluetooth de ESP-IDF.
idf_component_register(SRCS "main.cpp"
                       INCLUDE_DIRS "."
                       REQUIRES mcp2515)

