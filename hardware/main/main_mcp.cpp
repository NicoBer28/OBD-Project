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
        if (rxValue.length() > 0 && (rxValue.length() % sizeof(BleCanPacket) == 0)) {
            ESP_LOGI(TAG_BLE, "========= CONTRATO DE PAQUETES RECIBIDO =========");
            std::vector<BleCanPacket> packets;
            int count = rxValue.length() / sizeof(BleCanPacket);
            BleCanPacket* p_packets = (BleCanPacket*)rxValue.data();
            
            for (int i = 0; i < count; i++) {
                packets.push_back(p_packets[i]);
                ESP_LOGI(TAG_BLE, "Packet Polling -> ID: 0x%03lX, DLC: %d", (unsigned long)p_packets[i].can_id, p_packets[i].dlc);
            }
            
            if (obd_interface != nullptr) {
                obd_interface->set_polling_pids(packets);
            }
            ESP_LOGI(TAG_BLE, "=============================================");
        } else if (rxValue.length() > 0) {
            ESP_LOGW(TAG_BLE, "Payload BLE recibido invalido (longitud %d no es multiplo de 13 bytes)", rxValue.length());
        }
    }
};

static MyServerCallbacks serverCallbacks;
static MyRxCallbacks rxCallbacks;

// ============================================================================
// INITS
// ============================================================================
void init_spi_and_can() {
    // SPI init
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

    // INT init
    gpio_install_isr_service(0);
    gpio_config_t io_conf = {};
    io_conf.intr_type = GPIO_INTR_NEGEDGE;
    io_conf.pin_bit_mask = (1ULL << MCP2515_INT_PIN);
    io_conf.mode = GPIO_MODE_INPUT;
    io_conf.pull_up_en = GPIO_PULLUP_ENABLE;
    io_conf.pull_down_en = GPIO_PULLDOWN_DISABLE;
    gpio_config(&io_conf);
    gpio_isr_handler_add(MCP2515_INT_PIN, gpioInterruptCan, NULL);

    
    obd_interface = new OBD_MCP2515(&spi_handle, MCP2515_INT_PIN, can_rx_semaphore);
    obd_interface->set_tx_queue(ble_tx_queue);

    // Paquetes soportados por Audi para testing (default OBD2)
    std::vector<BleCanPacket> pids;
    uint8_t default_pids[] = {
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 
        0x0D, 0x0E, 0x0F, 0x11, 0x13, 0x15, 0x1C, 0x1F, 
        0x20, 0x21, 0x23, 0x2E, 0x30, 0x31, 0x33, 0x34, 
        0x3C, 0x40
    };
    for(uint8_t pid : default_pids) {
        BleCanPacket p;
        p.can_id = 0x7DF; // Standard OBD2 request ID
        p.dlc = 8;
        p.data[0] = 0x02; 
        p.data[1] = 0x01; 
        p.data[2] = pid;
        for(int i = 3; i < 8; i++) p.data[i] = 0xCC;
        pids.push_back(p);
    }
    obd_interface->set_polling_pids(pids);

    if (obd_interface->init()) {
        ESP_LOGI(TAG_SYS, "Interfaz OBD (MCP2515) inicializada");
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
    
    xSemaphoreTake(can_rx_semaphore, 0);

    while(1) {
        // espero int del can o timeout
        xSemaphoreTake(can_rx_semaphore, pdMS_TO_TICKS(20));

        // lee la interrupt o pide el siguiente dato, segun si hizo timeout o no
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
        
        // espero a que lleguen a la cola y voy tomando y contando los paquetes
        if (xQueueReceive(ble_tx_queue, &rx_packet, pdMS_TO_TICKS(100)) == pdTRUE) {
            tx_buffer[packet_count++] = rx_packet;
            
            
            printf("CAN RX | ID: 0x%03lX | DLC: %d | Data: ", (unsigned long)rx_packet.can_id, rx_packet.dlc);
            for (int i = 0; i < rx_packet.dlc; i++) {
                printf("%02X ", rx_packet.data[i]);
            }
            printf("\n");


            // cuando llegan a 5, los mando juntos
            if (packet_count >= 5) {
                if (deviceConnected && pTxCharacteristic != nullptr) {
                    pTxCharacteristic->setValue((uint8_t*)tx_buffer, sizeof(tx_buffer));
                    pTxCharacteristic->notify();
                }
                packet_count = 0;
            }
        }
        // si hizo timeout, mando lo que hay
        else {
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
    ESP_LOGI(TAG_SYS, "Arrancando...");

    can_rx_semaphore = xSemaphoreCreateBinary(); 

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
