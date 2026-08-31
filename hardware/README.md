# OBD-Project: ESP32 Firmware
Entorno de desarrollo de firmware en ESP-IDF utilizando Docker y VS Code Dev Containers.

## ⚙️ Setup del Entorno en Linux

### 1. Instalar Docker y Visual Studio Code. 

- Se puede evitar este último, pero en ese caso hay que abrir una terminal dentro docker para compilar

### 2. Descargar la imagen oficial de espressif
```bash
docker pull espressif/idf:latest
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

### 5. Presionar F1, escribir Dev Containers: Reopen in Container y presionar Enter (o usar el botón inferior derecho).
- Se va a abrir dentro de la carpeta hardware, esto es para facilitar el workflow
## 🛠️ Build & Flash
### 1. Con el contenedor ya cargado, abrir una nueva terminal integrada en VS Code
- Se va a abrir una terminal dentro del contenedor

### 2. Para compilar el firmware:
```bash
idf.py build
```
### 3. Flashear la ESP32 y abrir el monitor serial simultáneamente:

```bash
idf.py flash monitor
```
- (Para salir del monitor serial: `Ctrl + ]`)
