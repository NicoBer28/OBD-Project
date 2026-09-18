#include "NimBLEDevice.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "driver/gpio.h"
#include <string>
#include <time.h>
#include "mcp2515.h"
#include "can.h"


// definiciones BT
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E" // identificador del servicio principal
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // caracteristica RX (recepción de la ESP, la app escribe)
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // caracteristica TX (transmisión de la ESP, la app lee/notifica)

static const char *TAG = "BLE_APP";


// estructuras para testear funcionalidades en la app
enum PacketType {
    ID_SPEED_RPM = 0x01,
    ID_ENGINE_STATUS = 0x02
};


struct __attribute__((packed)) SpeedRpmPacket {
    uint8_t id = ID_SPEED_RPM;
    uint8_t speed;
    uint16_t rpm;
};

struct __attribute__((packed)) EngTempFuelPacket {
    uint8_t id = ID_ENGINE_STATUS;
    uint8_t temp;
    uint8_t fuel_level;
};

bool deviceConnected = false;

// callbacks BT
// se activan en el evento definido en el nombre
class MyServerCallbacks: public NimBLEServerCallbacks {

    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
        deviceConnected = true;
        ESP_LOGI(TAG, "> ESP conectada a un dispositivo");
    }

    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
        deviceConnected = false;
        ESP_LOGI(TAG, "> ESP desconectada, en modo advertising...");

        NimBLEDevice::startAdvertising();
    }
};

// callback de caracteristica RX
class MyRxCallbacks: public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {
        // datos recibidos
        std::string rxValue = pCharacteristic->getValue();

        if (rxValue.length() > 0) {
            ESP_LOGI(TAG, "========= DATO RECIBIDO =========");
            
            std::string textData = "";

            // en este caso, se lee como string
            for (int i = 0; i < rxValue.length(); i++) {
                textData += rxValue[i];
            }
            
            ESP_LOGI(TAG, "Data: %s", textData.c_str());
            ESP_LOGI(TAG, "=================================");
        }
    }
};


// data SPI y MCP
#define MCP2515_MISO_PIN GPIO_NUM_19
#define MCP2515_MOSI_PIN GPIO_NUM_23
#define MCP2515_CLK_PIN  GPIO_NUM_18
#define MCP2515_CS_PIN   GPIO_NUM_5
#define MCP2515_INT_PIN  GPIO_NUM_4


volatile bool int_mcp = false;
can_frame frame;

// handle de la interrupt del pin del MCP
static void IRAM_ATTR gpioInterruptCan (void *args) {
    int_mcp = true;
}


void requestOBD2(MCP2515 *mcp, uint8_t pid) {
    struct can_frame tx_frame;
    tx_frame.can_id = 0x7DF; // id de pregunta a ECU
    tx_frame.can_dlc = 8;   // paquete de 8 bytes
    
    tx_frame.data[0] = 0x02;    // espacio de data
    tx_frame.data[1] = 0x01;    // pedir datos actuales
    tx_frame.data[2] = pid;     // PID solicitado
    
    // se rellena con ceros
    for(int i = 3; i < 8; i++) {
        tx_frame.data[i] = 0x00;
    }

    mcp->sendMessage(&tx_frame);
}


extern "C" void app_main(void){


    // spi init
    spi_bus_config_t buscfg = {};
    buscfg.miso_io_num = MCP2515_MISO_PIN;
    buscfg.mosi_io_num = MCP2515_MOSI_PIN;
    buscfg.sclk_io_num = MCP2515_CLK_PIN;
    buscfg.quadwp_io_num = -1;
    buscfg.quadhd_io_num = -1;
    
    // spi config
    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST, &buscfg, SPI_DMA_CH_AUTO));

    spi_device_interface_config_t devcfg = {};
    devcfg.clock_speed_hz = 10000000;
    devcfg.mode = 0;
    devcfg.spics_io_num = MCP2515_CS_PIN;
    devcfg.queue_size = 1;
    
    spi_device_handle_t spi_handle;
    ESP_ERROR_CHECK(spi_bus_add_device(SPI2_HOST, &devcfg, &spi_handle));

    // pin interrupt del MCP
    gpio_install_isr_service(0);

    gpio_config_t io_conf = {};
    io_conf.intr_type = GPIO_INTR_NEGEDGE;
    io_conf.pin_bit_mask = (1ULL << MCP2515_INT_PIN);
    io_conf.mode = GPIO_MODE_INPUT;
    io_conf.pull_up_en = GPIO_PULLUP_ENABLE;
    io_conf.pull_down_en = GPIO_PULLDOWN_DISABLE;
    gpio_config(&io_conf);

    gpio_isr_handler_add(MCP2515_INT_PIN, gpioInterruptCan, NULL);


    // mcp init
    MCP2515 mcp2515(&spi_handle);
    mcp2515.reset();
    mcp2515.setBitrate(CAN_125KBPS, MCP_8MHZ);
    mcp2515.setNormalMode();
    mcp2515.clearInterrupts();
    mcp2515.setInterruptMask(MCP2515::CANINTF_RX0IF | MCP2515::CANINTF_RX1IF);


    // init bt
    NimBLEDevice::init("OBD-C");
    
    // potencia alta
    NimBLEDevice::setPower(ESP_PWR_LVL_P9); 


    NimBLEServer* pServer = NimBLEDevice::createServer();
    
    // se pasan los callbacks de connect
    pServer->setCallbacks(new MyServerCallbacks());
    

    // servicio general
    NimBLEService* pService = pServer->createService(SERVICE_UUID);


    // init RX
    NimBLECharacteristic* pRxCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID_RX,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
    );

    pRxCharacteristic->setCallbacks(new MyRxCallbacks());

    // init TX
    NimBLECharacteristic *pTxCharacteristic;

    // notify permite hacer avisos a la app
    pTxCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID_TX,
        NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::READ
    );


    // se pone visible
    NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();


    pAdvertising->setName("OBD-C");
    
    // se informa el servicio usado
    pAdvertising->addServiceUUID(SERVICE_UUID);
    
    // se comparte info extra
    pAdvertising->enableScanResponse(true);
    
    pAdvertising->start();

    ESP_LOGI(TAG, "> BT iniciado");

    SpeedRpmPacket currentSpeedRpm;
    EngTempFuelPacket currentEngTempFuel;

    bool req_speed = true;
    TickType_t last_call = xTaskGetTickCount();
    const TickType_t every_ms = 500 / portTICK_PERIOD_MS;

    while(1){

        if ((xTaskGetTickCount() - last_call) >= every_ms) {
            if (req_speed) {
                requestOBD2(&mcp2515, 0x0D); // PID Velocidad
            } else {
                requestOBD2(&mcp2515, 0x0C); // PID RPM
            }
            req_speed = !req_speed;
            last_call = xTaskGetTickCount();
        }

        if(int_mcp){
            int_mcp = false;

            uint8_t irq = mcp2515.getInterrupts();

            if (irq & MCP2515::CANINTF_RX0IF) {
                if (mcp2515.readMessage(MCP2515::RXB0, &frame) == MCP2515::ERROR_OK) {
                    if (frame.can_id == 0x7E8 && frame.data[1] == 0x41) {
                        if (frame.data[2] == 0x0D) {
                            uint8_t speed_kmh = frame.data[3];
                            ESP_LOGI("OBD2", "Velocidad actual: %d km/h", speed_kmh);
                        } else if (frame.data[2] == 0x0C) {
                            uint16_t rpm = ((frame.data[3] * 256) + frame.data[4]) / 4;
                            ESP_LOGI("OBD2", "RPM actual: %d", rpm);
                        }
                    }   
                }
            }

            if (irq & MCP2515::CANINTF_RX1IF) {
                if (mcp2515.readMessage(MCP2515::RXB1, &frame) == MCP2515::ERROR_OK) {
                    if (frame.can_id == 0x7E8 && frame.data[1] == 0x41) {
                        if (frame.data[2] == 0x0D) {
                            uint8_t speed_kmh = frame.data[3];
                            ESP_LOGI("OBD2", "Velocidad actual: %d km/h", speed_kmh);
                        } else if (frame.data[2] == 0x0C) {
                            uint16_t rpm = ((frame.data[3] * 256) + frame.data[4]) / 4;
                            ESP_LOGI("OBD2", "RPM actual: %d", rpm);
                        }
                    } 
                }
            }

            if (irq & (MCP2515::CANINTF_TX0IF | MCP2515::CANINTF_TX1IF | MCP2515::CANINTF_TX2IF)) {
                mcp2515.clearTXInterrupts();
            }
            if (irq & MCP2515::CANINTF_MERRF) {
                mcp2515.clearMERR();
            }
            if (irq & MCP2515::CANINTF_ERRIF) {
                mcp2515.clearERRIF();
            }
        }

        vTaskDelay(10 / portTICK_PERIOD_MS);
    }

}