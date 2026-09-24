# OBD-Project: ESP32 Firmware
Entorno de desarrollo de firmware en ESP-IDF utilizando Docker y VS Code Dev Containers.

## ⚙️ Setup del Entorno en Linux

### 1. Instalar Docker y Visual Studio Code. 

- Se puede evitar este último, pero en ese caso hay que abrir una terminal dentro docker para compilar

### 2. Descargar la imagen oficial de espressif
```bash
docker pull espressif/idf:v6.1
```
### 3. Instalar la extensión Dev Containers en VS Code 
- Tambien llamada por ID `ms-vscode-remote.remote-containers`

### 4. Abrir la carpeta raíz del repositorio (OBD-Project) en VS Code

```bash
# desde el directorio previo al repositorio de git
code ./OBD-Project/
# o, desde dentro de OBD-Project
code .
```

### 5. Crear una carpeta llamada `.devcontainer` y adentro crear el archivo `devcontainer.json` con el siguiente contenido
```json
{
  "name": "ESP32 Lab",
  "image": "espressif/idf:latest",
  "runArgs": [
    "--network=host",
    "--privileged"
  ],
  "workspaceFolder": "/workspaces/OBD-Project/hardware",
  "mounts": [
    "source=/dev,target=/dev,type=bind"
  ],
  "customizations": {
    "vscode": {
      "extensions": [
        "ms-vscode.cpptools-extension-pack",
		"usernamehw.errorlens",
		"google.google-antigravity"
      ]
    }
  },
  "postCreateCommand": "echo 'source /opt/esp/idf/export.sh' >> ~/.bashrc"
}

```

### 6. Presionar F1, escribir Dev Containers: Reopen in Container y presionar Enter (o usar el botón inferior derecho).
- Se va a abrir dentro de la carpeta hardware, esto es para facilitar el workflow
## 🛠️ Build & Flash
### 1. Con el contenedor ya cargado, abrir una nueva terminal integrada en VS Code
- Se va a abrir una terminal dentro del contenedor

### 2. Para compilar el firmware:
```bash
idf.py build
```
- Se van a descargar todas las dependencias del proyecto
### 3. La libreria del mcp2515 utilizada tiene un error de dependencias en su `CMakeLists.txt`. Para corregirlo ejecutar, desde el directorio actual:

```bash
echo "idf_component_register(SRCS "mcp2515.cpp"
                    INCLUDE_DIRS "include"
                    REQUIRES esp_driver_spi)" > ./managed_components/mcp2515/CMakeLists.txt
```
- Tambien se puede editar directamente el archivo
### 4. Flashear la ESP32 y abrir el monitor serial simultáneamente:

```bash
idf.py flash monitor
```
- (Para salir del monitor serial: `Ctrl + ]`)
