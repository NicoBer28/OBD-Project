#include "NimBLEDevice.h"
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include <string>
#include <vector>

#include "obd_can.h"

// ============================================================================
// DEFINICIONES Y CONSTANTES
// ============================================================================
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

static const char *TAG_BLE = "BLE_TASK";
static const char *TAG_OBD = "OBD_TASK";
static const char *TAG_SYS = "MAIN2";

// PINES SPI & MCP
#define MCP2515_MISO_PIN GPIO_NUM_19
#define MCP2515_MOSI_PIN GPIO_NUM_23
#define MCP2515_CLK_PIN  GPIO_NUM_18
#define MCP2515_CS_PIN   GPIO_NUM_5
#define MCP2515_INT_PIN  GPIO_NUM_4

// ============================================================================
// RECURSOS COMPARTIDOS
// ============================================================================
bool deviceConnected = false;
NimBLECharacteristic* pTxCharacteristic = nullptr;

SemaphoreHandle_t can_rx_semaphore = NULL;  
QueueHandle_t ble_tx_queue = NULL;          

spi_device_handle_t spi_handle;
OBD_CAN_Interface* obd_interface = nullptr;

// ============================================================================
// AUXILIARES Y CALLBACKS
// ============================================================================
static void IRAM_ATTR gpioInterruptCan(void *args) {
    BaseType_t xHigherPriorityTaskWoken = pdFALSE;
    if (can_rx_semaphore != NULL) {
        xSemaphoreGiveFromISR(can_rx_semaphore, &xHigherPriorityTaskWoken);
    }
    if (xHigherPriorityTaskWoken) {
        portYIELD_FROM_ISR();
    }
}

class MyServerCallbacks: public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
        deviceConnected = true;
        ESP_LOGI(TAG_BLE, "> Dispositivo conectado");
    }

    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override
    {
        deviceConnected = false;
        ESP_LOGI(TAG_BLE, "> Dispositivo desconectado, reiniciando advertising...");
        NimBLEDevice::startAdvertising();
    }
};

class MyRxCallbacks: public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {
        std::string rxValue = pCharacteristic->getValue();
        if (rxValue.length() > 0) {
            ESP_LOGI(TAG_BLE, "========= NUEVO CONTRATO DE PIDs RECIBIDO =========");
            std::vector<uint8_t> pids;
            for (int i = 0; i < rxValue.length(); i++) {
                pids.push_back(static_cast<uint8_t>(rxValue[i]));
                ESP_LOGI(TAG_BLE, "PID agregado para polling: 0x%02X", pids.back());
            }
            if (obd_interface != nullptr) {
                obd_interface->set_polling_pids(pids);
            }
            ESP_LOGI(TAG_BLE, "===================================================");
        }
    }
};

static MyServerCallbacks serverCallbacks;
static MyRxCallbacks rxCallbacks;

// ============================================================================
// INITS
// ============================================================================
void init_spi_and_can() {
    // 1. Inicializar bus SPI
    spi_bus_config_t buscfg = {};
    buscfg.miso_io_num = MCP2515_MISO_PIN;
    buscfg.mosi_io_num = MCP2515_MOSI_PIN;
    buscfg.sclk_io_num = MCP2515_CLK_PIN;
    buscfg.quadwp_io_num = -1;
    buscfg.quadhd_io_num = -1;
    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST, &buscfg, SPI_DMA_CH_AUTO));

    // 2. Agregar dispositivo SPI
    spi_device_interface_config_t devcfg = {};
    devcfg.clock_speed_hz = 10000000;
    devcfg.mode = 0;
    devcfg.spics_io_num = MCP2515_CS_PIN;
    devcfg.queue_size = 1;
    ESP_ERROR_CHECK(spi_bus_add_device(SPI2_HOST, &devcfg, &spi_handle));

    // 3. Configurar ISR
    gpio_install_isr_service(0);
    gpio_config_t io_conf = {};
    io_conf.intr_type = GPIO_INTR_NEGEDGE;
    io_conf.pin_bit_mask = (1ULL << MCP2515_INT_PIN);
    io_conf.mode = GPIO_MODE_INPUT;
    io_conf.pull_up_en = GPIO_PULLUP_ENABLE;
    io_conf.pull_down_en = GPIO_PULLDOWN_DISABLE;
    gpio_config(&io_conf);
    gpio_isr_handler_add(MCP2515_INT_PIN, gpioInterruptCan, NULL);

    // 4. Inyectar dependencias y levantar interfaz OBD (Dumb Gateway)
    obd_interface = new OBD_MCP2515(&spi_handle, MCP2515_INT_PIN, can_rx_semaphore);
    obd_interface->set_tx_queue(ble_tx_queue); // Inyectamos la cola para TX transparente
    
    if (obd_interface->init()) {
        ESP_LOGI(TAG_SYS, "Interfaz OBD (MCP2515) inicializada correctamente en modo Gateway.");
    } else {
        ESP_LOGE(TAG_SYS, "Error al inicializar la interfaz OBD.");
    }
}

void init_ble() {
    NimBLEDevice::init("OBD-C");
    NimBLEDevice::setPower(ESP_PWR_LVL_P9); 

    NimBLEServer* pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(&serverCallbacks);
    
    NimBLEService* pService = pServer->createService(SERVICE_UUID);

    NimBLECharacteristic* pRxCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID_RX,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
    );
    pRxCharacteristic->setCallbacks(&rxCallbacks);

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
// TASK OBD
// ============================================================================
void vOBDTask(void *pvParameters) {
    ESP_LOGI(TAG_OBD, "Tarea OBD Iniciada en core %d", xPortGetCoreID());
    
    // Limpiar cualquier token residual en el semáforo antes de arrancar (Mitigación desincronización de boot)
    xSemaphoreTake(can_rx_semaphore, 0);

    while(1) {
        // Bloqueamos hasta que haya una interrupción del bus CAN, o pase un timeout corto 
        // para asegurarnos de llamar a process() y mantener vivo el polling
        xSemaphoreTake(can_rx_semaphore, pdMS_TO_TICKS(20));

        // process() lee tramas del hardware CAN y las encola directamente en ble_tx_queue,
        // además envía el siguiente PID a encuestar según la lista inyectada por BLE RX.
        obd_interface->process();
    }
}

// ============================================================================
// TASK BT
// ============================================================================
void vBLETask(void *pvParameters) {
    ESP_LOGI(TAG_BLE, "Tarea BLE Iniciada en core %d", xPortGetCoreID());
    
    BleCanPacket tx_buffer[5];
    int packet_count = 0;
    
    while(1) {
        BleCanPacket rx_packet;
        
        // Esperamos paquetes de CAN. El timeout (ej. 100ms) previene que los paquetes se
        // queden atascados en el buffer local si el auto dejó de enviar datos y el buffer no llegó a 5.
        if (xQueueReceive(ble_tx_queue, &rx_packet, pdMS_TO_TICKS(100)) == pdTRUE) {
            tx_buffer[packet_count++] = rx_packet;
            
            if (packet_count >= 5) {
                if (deviceConnected && pTxCharacteristic != nullptr) {
                    pTxCharacteristic->setValue((uint8_t*)tx_buffer, sizeof(tx_buffer));
                    pTxCharacteristic->notify();
                }
                packet_count = 0; // Reiniciar buffer
            }
        } else {
            // Timeout: Si tenemos paquetes acumulados y no llegan más, los despachamos
            if (packet_count > 0) {
                if (deviceConnected && pTxCharacteristic != nullptr) {
                    pTxCharacteristic->setValue((uint8_t*)tx_buffer, packet_count * sizeof(BleCanPacket));
                    pTxCharacteristic->notify();
                }
                packet_count = 0;
            }
        }
    }
}

// ============================================================================
// START
// ============================================================================
extern "C" void app_main(void) {
    ESP_LOGI(TAG_SYS, "Arrancando sistema OBD2 V2 (Dumb Gateway OOP)...");

    can_rx_semaphore = xSemaphoreCreateBinary(); 
    // Usamos tamaño 20 para almacenar de forma segura paquetes BleCanPacket individuales (13 bytes c/u)
    ble_tx_queue = xQueueCreate(20, sizeof(BleCanPacket)); 

    if (can_rx_semaphore == NULL || ble_tx_queue == NULL) {
        ESP_LOGE(TAG_SYS, "Fallo al crear primitivas RTOS");
        return;
    }

    init_spi_and_can();
    init_ble();

    xTaskCreatePinnedToCore(vOBDTask, "OBD_Task", 4096, NULL, 5, NULL, 0);
    xTaskCreatePinnedToCore(vBLETask, "BLE_Task", 4096, NULL, 4, NULL, 1);

    ESP_LOGI(TAG_SYS, "Sistema corriendo.");
}
