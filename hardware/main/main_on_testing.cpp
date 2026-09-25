#include "NimBLEDevice.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "driver/gpio.h"
#include <string>
#include <vector>
#include "mcp2515.h"
#include "can.h"
#include "esp_log.h"

// ============================================================================
// DEFINICIONES Y CONSTANTES
// ============================================================================
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

static const char *TAG_BLE = "BLE_TASK";
static const char *TAG_OBD = "OBD_TASK";
static const char *TAG_SYS = "MAIN";

// PINES SPI & MCP
#define MCP2515_MISO_PIN GPIO_NUM_19
#define MCP2515_MOSI_PIN GPIO_NUM_23
#define MCP2515_CLK_PIN  GPIO_NUM_18
#define MCP2515_CS_PIN   GPIO_NUM_5
#define MCP2515_INT_PIN  GPIO_NUM_4

// PIDs OBD2 a utilizar
#define PID_SUPPORTED_00_20 0x00
#define PID_ENGINE_LOAD     0x04
#define PID_ENGINE_TEMP     0x05
#define PID_MAP             0x0B
#define PID_ENGINE_RPM      0x0C
#define PID_VEHICLE_SPEED   0x0D
#define PID_MAF             0x10
#define PID_FUEL_LEVEL      0x2F

// ============================================================================
// ESTRUCTURAS DE DATOS UNIFICADAS
// ============================================================================

// Flags para indicar qué datos están disponibles y fueron leídos al menos una vez
enum ValidDataFlags {
    FLAG_SPEED = (1 << 0),
    FLAG_RPM   = (1 << 1),
    FLAG_TEMP  = (1 << 2),
    FLAG_FUEL  = (1 << 3),
    FLAG_MAP   = (1 << 4),
    FLAG_MAF   = (1 << 5),
    FLAG_LOAD  = (1 << 6)
};

// Paquete unificado y empaquetado para mandar siempre del mismo tamaño por BT
struct __attribute__((packed)) OBDPacket {
    uint8_t packet_id = 0x10;   // Identificador de paquete de estado general
    uint8_t valid_flags = 0;    // Bitmask indicando qué datos del struct son válidos/están disponibles
    
    // Datos básicos ya existentes
    uint8_t speed = 0;
    uint16_t rpm = 0;
    uint8_t engine_temp = 0;
    uint8_t fuel_level = 0;
    
    // Datos técnicos extra sugeridos para evaluar consumo/decaída de combustible
    uint8_t map = 0;            // Presión absoluta del colector de admisión (kPa)
    uint16_t maf = 0;           // Flujo de masa de aire (gramos/segundo, escalado x100)
    uint8_t engine_load = 0;    // Carga calculada del motor (%)
};

// ============================================================================
// VARIABLES GLOBALES (Recursos compartidos)
// ============================================================================
bool deviceConnected = false;
NimBLECharacteristic* pTxCharacteristic = nullptr;

// RTOS Primitives
SemaphoreHandle_t can_rx_semaphore = NULL;  // Semáforo para despertar la tarea OBD tras interrupción
QueueHandle_t ble_tx_queue = NULL;          // Cola para enviar los paquetes a la tarea BLE
SemaphoreHandle_t obd_data_mutex = NULL;        // Mutex para proteger la estructura de datos unificada si es necesario

spi_device_handle_t spi_handle;             // Handle del bus SPI
MCP2515* mcp2515_ptr = nullptr;             // Puntero global para el driver MCP

// Estado actual global (protegido si se accede desde múltiples tareas)
OBDPacket current_obd_data;
uint32_t supported_pids_01_20 = 0; // Bitmask de PIDs soportados (obtenido de PID 0x00)

// ============================================================================
// FUNCIONES AUXILIARES & CALLBACKS
// ============================================================================

// ISR para la interrupción del MCP2515 (cuando llega un mensaje CAN)
static void IRAM_ATTR gpioInterruptCan(void *args) {
    BaseType_t xHigherPriorityTaskWoken = pdFALSE;
    if (can_rx_semaphore != NULL) {
        // Despierta a la tarea OBD que está esperando el semáforo
        xSemaphoreGiveFromISR(can_rx_semaphore, &xHigherPriorityTaskWoken);
    }
    if (xHigherPriorityTaskWoken) {
        portYIELD_FROM_ISR();
    }
}

// Callbacks Servidor BLE
class MyServerCallbacks: public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
        deviceConnected = true;
        ESP_LOGI(TAG_BLE, "> Dispositivo conectado");
    }

    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
        deviceConnected = false;
        ESP_LOGI(TAG_BLE, "> Dispositivo desconectado, reiniciando advertising...");
        NimBLEDevice::startAdvertising();
    }
};

// Callbacks Característica RX (App -> ESP32)
class MyRxCallbacks: public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {
        std::string rxValue = pCharacteristic->getValue();
        if (rxValue.length() > 0) {
            ESP_LOGI(TAG_BLE, "========= DATO RECIBIDO =========");
            std::string textData = "";
            for (int i = 0; i < rxValue.length(); i++) {
                textData += rxValue[i];
            }
            ESP_LOGI(TAG_BLE, "Data: %s", textData.c_str());
            ESP_LOGI(TAG_BLE, "=================================");
        }
    }
};

// ============================================================================
// MÓDULOS DE INICIALIZACIÓN
// ============================================================================

void init_spi_and_mcp() {
    spi_bus_config_t buscfg = {};
    buscfg.miso_io_num = MCP2515_MISO_PIN;
    buscfg.mosi_io_num = MCP2515_MOSI_PIN;
    buscfg.sclk_io_num = MCP2515_CLK_PIN;
    buscfg.quadwp_io_num = -1;
    buscfg.quadhd_io_num = -1;
    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST, &buscfg, SPI_DMA_CH_AUTO));

    spi_device_interface_config_t devcfg = {};
    devcfg.clock_speed_hz = 10000000;
    devcfg.mode = 0;
    devcfg.spics_io_num = MCP2515_CS_PIN;
    devcfg.queue_size = 1;
    
    ESP_ERROR_CHECK(spi_bus_add_device(SPI2_HOST, &devcfg, &spi_handle));

    // Inicializar pin de interrupción del MCP
    gpio_install_isr_service(0);
    gpio_config_t io_conf = {};
    io_conf.intr_type = GPIO_INTR_NEGEDGE;
    io_conf.pin_bit_mask = (1ULL << MCP2515_INT_PIN);
    io_conf.mode = GPIO_MODE_INPUT;
    io_conf.pull_up_en = GPIO_PULLUP_ENABLE;
    io_conf.pull_down_en = GPIO_PULLDOWN_DISABLE;
    gpio_config(&io_conf);
    gpio_isr_handler_add(MCP2515_INT_PIN, gpioInterruptCan, NULL);

    // Init driver
    mcp2515_ptr = new MCP2515(&spi_handle);
    mcp2515_ptr->reset();
    mcp2515_ptr->setBitrate(CAN_500KBPS, MCP_8MHZ); // Ajustar según el vehículo (a veces 500KBPS)
    mcp2515_ptr->setNormalMode();
    mcp2515_ptr->clearInterrupts();
    mcp2515_ptr->setInterruptMask(MCP2515::CANINTF_RX0IF | MCP2515::CANINTF_RX1IF);
    
    ESP_LOGI(TAG_SYS, "SPI y MCP2515 inicializados correctamente.");
}

void init_ble() {
    NimBLEDevice::init("OBD-C");
    NimBLEDevice::setPower(ESP_PWR_LVL_P9); 

    NimBLEServer* pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());
    
    NimBLEService* pService = pServer->createService(SERVICE_UUID);

    NimBLECharacteristic* pRxCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID_RX,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
    );
    pRxCharacteristic->setCallbacks(new MyRxCallbacks());

    pTxCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID_TX,
        NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::READ
    );

    NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->setName("OBD-C");
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->enableScanResponse(true);
    pAdvertising->start();

    ESP_LOGI(TAG_SYS, "BLE inicializado.");
}

// ============================================================================
// FUNCIONES OBD2 UNIFICADAS
// ============================================================================

void request_obd_pid(uint8_t pid) {
    if (!mcp2515_ptr) return;
    struct can_frame tx_frame;
    tx_frame.can_id = 0x7DF; 
    tx_frame.can_dlc = 8;
    tx_frame.data[0] = 0x02;
    tx_frame.data[1] = 0x01;
    tx_frame.data[2] = pid;
    for(int i = 3; i < 8; i++) {
        tx_frame.data[i] = 0xCC;
    }
    mcp2515_ptr->sendMessage(&tx_frame);
}

// Verifica en la bitmask si el PID está soportado por el auto
bool is_pid_supported(uint8_t pid) {
    if (pid >= 0x01 && pid <= 0x20) {
        // pid 1 está en el bit 31, pid 32 está en el bit 0 de la respuesta
        uint8_t shift = 32 - pid; 
        return (supported_pids_01_20 & (1UL << shift)) != 0;
    }
    return true; // Si es mayor a 0x20, asumimos temporalmente true hasta implementar lectura extendida (PID 0x20, 0x40...)
}

// Procesa una respuesta OBD genérica y actualiza la estructura unificada
void process_obd_response(const can_frame& frame) {
    if (frame.can_id < 0x7E8 || frame.can_id > 0x7EF || frame.data[1] != 0x41) return; // Filtro de respuesta OBD estándar
    
    uint8_t pid = frame.data[2];
    
    // Obtenemos el mutex para proteger la escritura de current_obd_data
    if (xSemaphoreTake(obd_data_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        
        switch(pid) {
            case PID_SUPPORTED_00_20:
                supported_pids_01_20 = (frame.data[3] << 24) | (frame.data[4] << 16) | (frame.data[5] << 8) | frame.data[6];
                ESP_LOGI(TAG_OBD, "PIDs 01-20 soportados: 0x%08X", (unsigned int)supported_pids_01_20);
                break;
                
            case PID_VEHICLE_SPEED:
                current_obd_data.speed = frame.data[3];
                current_obd_data.valid_flags |= FLAG_SPEED;
                break;
                
            case PID_ENGINE_RPM:
                current_obd_data.rpm = ((frame.data[3] * 256) + frame.data[4]) / 4;
                current_obd_data.valid_flags |= FLAG_RPM;
                break;
                
            case PID_ENGINE_TEMP:
                current_obd_data.engine_temp = frame.data[3] - 40; // Ecuación estándar OBD
                current_obd_data.valid_flags |= FLAG_TEMP;
                break;
                
            case PID_FUEL_LEVEL:
                current_obd_data.fuel_level = (frame.data[3] * 100) / 255; // Ecuación estándar: A * 100 / 255 (%)
                current_obd_data.valid_flags |= FLAG_FUEL;
                break;

            case PID_MAP:
                current_obd_data.map = frame.data[3]; // kPa
                current_obd_data.valid_flags |= FLAG_MAP;
                break;

            case PID_MAF:
                current_obd_data.maf = ((frame.data[3] * 256) + frame.data[4]) / 100; // gramos/seg (div 100, guardamos escalar si hace falta)
                current_obd_data.valid_flags |= FLAG_MAF;
                break;

            case PID_ENGINE_LOAD:
                current_obd_data.engine_load = (frame.data[3] * 100) / 255; // %
                current_obd_data.valid_flags |= FLAG_LOAD;
                break;
        }
        
        // Enviamos una copia de los datos al task de BLE si hay una actualización
        // (Podría optimizarse para no enviar en c/respuesta sino periódicamente)
        OBDPacket packet_to_send = current_obd_data;
        xQueueOverwrite(ble_tx_queue, &packet_to_send); // Sobreescribe para tener siempre el último dato
        
        xSemaphoreGive(obd_data_mutex);
    }
}

// ============================================================================
// TAREAS FREERTOS
// ============================================================================

// Tarea para manejar CAN / OBD2
void vOBDTask(void *pvParameters) {
    ESP_LOGI(TAG_OBD, "Tarea OBD Iniciada en core %d", xPortGetCoreID());
    
    // Lista de PIDs a ciclar (Priorizar los más importantes/rápidos)
    std::vector<uint8_t> pids_to_poll = {
        PID_ENGINE_RPM, PID_VEHICLE_SPEED, PID_ENGINE_LOAD, 
        PID_MAP, PID_MAF, PID_ENGINE_TEMP, PID_FUEL_LEVEL
    };
    uint8_t current_pid_index = 0;
    
    // Pedir los soportados al arrancar
    request_obd_pid(PID_SUPPORTED_00_20);
    vTaskDelay(pdMS_TO_TICKS(100)); // Esperar respuesta

    while(1) {
        // Pedir el siguiente PID de la lista
        uint8_t next_pid = pids_to_poll[current_pid_index];
        
        // Si ya evaluamos los soportados y sabemos que NO lo soporta, lo saltamos
        if (supported_pids_01_20 != 0 && !is_pid_supported(next_pid)) {
             // Avanzar y probar con otro la próxima
        } else {
             request_obd_pid(next_pid);
        }
        
        current_pid_index = (current_pid_index + 1) % pids_to_poll.size();

        // Esperar por la interrupción (o timeout por si falla un request)
        if (xSemaphoreTake(can_rx_semaphore, pdMS_TO_TICKS(100)) == pdTRUE || gpio_get_level(MCP2515_INT_PIN) == 0) {
            while (gpio_get_level(MCP2515_INT_PIN) == 0) {
                uint8_t irq = mcp2515_ptr->getInterrupts();
                can_frame rx_frame;

                if (irq & MCP2515::CANINTF_RX0IF) {
                    if (mcp2515_ptr->readMessage(MCP2515::RXB0, &rx_frame) == MCP2515::ERROR_OK) {
                        process_obd_response(rx_frame);
                    }
                }

                if (irq & MCP2515::CANINTF_RX1IF) {
                    if (mcp2515_ptr->readMessage(MCP2515::RXB1, &rx_frame) == MCP2515::ERROR_OK) {
                        process_obd_response(rx_frame);
                    }
                }

                // Limpiar otras interrupciones de error o transmisión
                if (irq & (MCP2515::CANINTF_TX0IF | MCP2515::CANINTF_TX1IF | MCP2515::CANINTF_TX2IF)) {
                    mcp2515_ptr->clearTXInterrupts();
                }
                if (irq & MCP2515::CANINTF_MERRF) mcp2515_ptr->clearMERR();
                if (irq & MCP2515::CANINTF_ERRIF) mcp2515_ptr->clearERRIF();

                if (irq == 0) break;
            }
        }

        // Pequeño delay entre requests para no saturar el bus CAN del vehículo
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}


// Tarea para manejar notificaciones BLE
void vBLETask(void *pvParameters) {
    ESP_LOGI(TAG_BLE, "Tarea BLE Iniciada en core %d", xPortGetCoreID());
    OBDPacket rx_packet;
    
    while(1) {
        // Esperamos que haya un paquete nuevo en la cola (bloqueante)
        if (xQueueReceive(ble_tx_queue, &rx_packet, portMAX_DELAY) == pdTRUE) {
            if (deviceConnected && pTxCharacteristic != nullptr) {
                // Notificar por bluetooth usando el struct unificado
                pTxCharacteristic->setValue((uint8_t*)&rx_packet, sizeof(OBDPacket));
                pTxCharacteristic->notify();
                // ESP_LOGI(TAG_BLE, "Notificando BT: RPM %d, Vel %d", rx_packet.rpm, rx_packet.speed);
            }
        }
        
        // Limitar la tasa de envío a max 10Hz (100ms) para no saturar BLE
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

// ============================================================================
// APP MAIN
// ============================================================================
extern "C" void app_main(void) {
    ESP_LOGI(TAG_SYS, "Arrancando sistema OBD2...");

    // Inicializar recursos RTOS
    // Se usa un semáforo binario para la ISR del MCP
    can_rx_semaphore = xSemaphoreCreateBinary(); 
    
    // Cola de tamaño 1 usando Overwrite para mantener el último estado sin retrasos
    ble_tx_queue = xQueueCreate(1, sizeof(OBDPacket)); 
    
    obd_data_mutex = xSemaphoreCreateMutex();

    if (can_rx_semaphore == NULL || ble_tx_queue == NULL || obd_data_mutex == NULL) {
        ESP_LOGE(TAG_SYS, "Fallo al crear primitivas RTOS");
        return;
    }

    // Inicializar hardware y subsistemas
    init_spi_and_mcp();
    init_ble();

    // Crear tareas y asginar núcleos (Core 0 para OBD/Comunicaciones hardware, Core 1 para BLE)
    xTaskCreatePinnedToCore(vOBDTask, "OBD_Task", 4096, NULL, 5, NULL, 0);
    xTaskCreatePinnedToCore(vBLETask, "BLE_Task", 4096, NULL, 4, NULL, 1);

    ESP_LOGI(TAG_SYS, "Sistema corriendo.");
}

