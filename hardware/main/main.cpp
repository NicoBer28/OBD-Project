#include "NimBLEDevice.h"
#include "driver/gpio.h"
#include "esp_log.h"
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
#define PID_ENGINE_TEMP     0x05
#define PID_ENGINE_RPM      0x0C
#define PID_VEHICLE_SPEED   0x0D
#define PID_FUEL_LEVEL      0x2F

// ============================================================================
// ESTRUCTURAS DE DATOS
// ============================================================================
enum PacketType {
    ID_SPEED_RPM = 0x01,
    ID_ENGINE_STATUS = 0x02
};

struct __attribute__((packed)) SpeedRpmPacket {
    uint8_t id = ID_SPEED_RPM;
    uint8_t speed = 0;
    uint16_t rpm = 0;
};

struct __attribute__((packed)) EngTempFuelPacket {
    uint8_t id = ID_ENGINE_STATUS;
    uint8_t temp = 0;
    uint8_t fuel_level = 0;
};

struct QueueMessage {
    uint8_t packet_type; 
    SpeedRpmPacket speed_rpm;
    EngTempFuelPacket eng_temp_fuel;
};

// ============================================================================
// RECURSOS COMPARTIDOS
// ============================================================================
bool deviceConnected = false;
NimBLECharacteristic* pTxCharacteristic = nullptr;

SemaphoreHandle_t can_rx_semaphore = NULL;  
QueueHandle_t ble_tx_queue = NULL;          
SemaphoreHandle_t obd_data_mutex = NULL;        

spi_device_handle_t spi_handle;
MCP2515* mcp2515_ptr = nullptr;             

SpeedRpmPacket currentSpeedRpm;
EngTempFuelPacket currentEngTempFuel;
uint32_t supported_pids_01_20 = 0; 

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
// INITS
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

    gpio_install_isr_service(0);
    gpio_config_t io_conf = {};
    io_conf.intr_type = GPIO_INTR_NEGEDGE;
    io_conf.pin_bit_mask = (1ULL << MCP2515_INT_PIN);
    io_conf.mode = GPIO_MODE_INPUT;
    io_conf.pull_up_en = GPIO_PULLUP_ENABLE;
    io_conf.pull_down_en = GPIO_PULLDOWN_DISABLE;
    gpio_config(&io_conf);
    gpio_isr_handler_add(MCP2515_INT_PIN, gpioInterruptCan, NULL);

    mcp2515_ptr = new MCP2515(&spi_handle);
    mcp2515_ptr->reset();
    mcp2515_ptr->setBitrate(CAN_500KBPS, MCP_8MHZ);
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
// OBD
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

bool is_pid_supported(uint8_t pid) {
    if (pid >= 0x01 && pid <= 0x20) {
        uint8_t shift = 32 - pid; 
        return (supported_pids_01_20 & (1UL << shift)) != 0;
    }
    return true; 
}

void process_obd_response(const can_frame& frame) {
    if (frame.can_id < 0x7E8 || frame.can_id > 0x7EF || frame.data[1] != 0x41) return; 
    
    uint8_t pid = frame.data[2];
    
    if (xSemaphoreTake(obd_data_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        
        bool send_speed_rpm = false;
        bool send_temp_fuel = false;

        switch(pid) {
            case PID_SUPPORTED_00_20:
                supported_pids_01_20 = (frame.data[3] << 24) | (frame.data[4] << 16) | (frame.data[5] << 8) | frame.data[6];
                ESP_LOGI(TAG_OBD, "PIDs 01-20 soportados: 0x%08X", (unsigned int)supported_pids_01_20);
                break;
                
            case PID_VEHICLE_SPEED:
                currentSpeedRpm.speed = frame.data[3];
                send_speed_rpm = true;
                break;
                
            case PID_ENGINE_RPM:
                currentSpeedRpm.rpm = ((frame.data[3] * 256) + frame.data[4]) / 4;
                send_speed_rpm = true;
                break;
                
            case PID_ENGINE_TEMP:
                currentEngTempFuel.temp = frame.data[3] - 40; 
                send_temp_fuel = true;
                break;
                
            case PID_FUEL_LEVEL:
                currentEngTempFuel.fuel_level = (frame.data[3] * 100) / 255; 
                send_temp_fuel = true;
                break;
        }
        
        if (send_speed_rpm) {
            QueueMessage msg;
            msg.packet_type = ID_SPEED_RPM;
            msg.speed_rpm = currentSpeedRpm;
            xQueueSend(ble_tx_queue, &msg, 0); 
        } else if (send_temp_fuel) {
            QueueMessage msg;
            msg.packet_type = ID_ENGINE_STATUS;
            msg.eng_temp_fuel = currentEngTempFuel;
            xQueueSend(ble_tx_queue, &msg, 0); 
        }
        
        xSemaphoreGive(obd_data_mutex);
    }
}

// ============================================================================
// TASK OBD
// ============================================================================

void vOBDTask(void *pvParameters) {
    ESP_LOGI(TAG_OBD, "Tarea OBD Iniciada en core %d", xPortGetCoreID());
    
    std::vector<uint8_t> pids_to_poll = {
        PID_ENGINE_RPM, PID_VEHICLE_SPEED, PID_ENGINE_TEMP, PID_FUEL_LEVEL
    };
    uint8_t current_pid_index = 0;
    
    request_obd_pid(PID_SUPPORTED_00_20);
    vTaskDelay(pdMS_TO_TICKS(100)); 

    while(1) {
        uint8_t next_pid = pids_to_poll[current_pid_index];
        
        if (supported_pids_01_20 != 0 && !is_pid_supported(next_pid)) {
             // skip
        } else {
             request_obd_pid(next_pid);
        }
        
        current_pid_index = (current_pid_index + 1) % pids_to_poll.size();

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

                if (irq & (MCP2515::CANINTF_TX0IF | MCP2515::CANINTF_TX1IF | MCP2515::CANINTF_TX2IF)) {
                    mcp2515_ptr->clearTXInterrupts();
                }
                if (irq & MCP2515::CANINTF_MERRF) mcp2515_ptr->clearMERR();
                if (irq & MCP2515::CANINTF_ERRIF) mcp2515_ptr->clearERRIF();
                
                if (irq == 0) break;
            }
        }

        vTaskDelay(pdMS_TO_TICKS(50));
    }
}


// ============================================================================
// TASK BT
// ============================================================================
void vBLETask(void *pvParameters) {
    ESP_LOGI(TAG_BLE, "Tarea BLE Iniciada en core %d", xPortGetCoreID());
    QueueMessage rx_packet;
    
    while(1) {
        if (xQueueReceive(ble_tx_queue, &rx_packet, portMAX_DELAY) == pdTRUE) {
            if (deviceConnected && pTxCharacteristic != nullptr) {
                if (rx_packet.packet_type == ID_SPEED_RPM) {
                    pTxCharacteristic->setValue((uint8_t*)&rx_packet.speed_rpm, sizeof(SpeedRpmPacket));
                } else if (rx_packet.packet_type == ID_ENGINE_STATUS) {
                    pTxCharacteristic->setValue((uint8_t*)&rx_packet.eng_temp_fuel, sizeof(EngTempFuelPacket));
                }
                pTxCharacteristic->notify();
            }
        }
        
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

// ============================================================================
// START
// ============================================================================
extern "C" void app_main(void) {
    ESP_LOGI(TAG_SYS, "Arrancando sistema OBD2...");

    can_rx_semaphore = xSemaphoreCreateBinary(); 
    
    ble_tx_queue = xQueueCreate(10, sizeof(QueueMessage)); 
    
    obd_data_mutex = xSemaphoreCreateMutex();

    if (can_rx_semaphore == NULL || ble_tx_queue == NULL || obd_data_mutex == NULL) {
        ESP_LOGE(TAG_SYS, "Fallo al crear primitivas RTOS");
        return;
    }

    init_spi_and_mcp();
    init_ble();

    xTaskCreatePinnedToCore(vOBDTask, "OBD_Task", 4096, NULL, 5, NULL, 0);
    xTaskCreatePinnedToCore(vBLETask, "BLE_Task", 4096, NULL, 4, NULL, 1);

    ESP_LOGI(TAG_SYS, "Sistema corriendo.");
}
