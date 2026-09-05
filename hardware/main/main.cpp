#include "NimBLEDevice.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include <string>
#include <time.h>

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


extern "C" void app_main(void){

    // random init, para demostraciones sin auto
    srand(time(NULL));


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

    while (true){

        vTaskDelay(1000 / portTICK_PERIOD_MS); 


        if (deviceConnected) {
            
            // velocidad random entre 40  y 60 km/h
            int random_speed = (rand() % (60 - 50 + 1)) + 50;
            int random_rpm = (rand() %(2500 - 2000 + 1) + 2000);
            int random_temp = (rand() %(105 - 100 + 1) + 100);

            currentSpeedRpm.speed = random_speed;
            currentSpeedRpm.rpm = random_rpm;

            currentEngTempFuel.temp = random_temp;
            currentEngTempFuel.fuel_level = 90;

            // el mensaje se pone en la caracteristica y se notifica a la app que esa ahi
            pTxCharacteristic->setValue((uint8_t*)&currentSpeedRpm, sizeof(currentSpeedRpm));
        
            pTxCharacteristic->notify();

            vTaskDelay(20 / portTICK_PERIOD_MS);
                            
            pTxCharacteristic->setValue((uint8_t*)&currentEngTempFuel, sizeof(currentEngTempFuel));
        
            pTxCharacteristic->notify();
        }
    }
}