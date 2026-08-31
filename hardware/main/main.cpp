#include "NimBLEDevice.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include <string>

// definiciones BT
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E" // identificador del servicio principal
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // caracteristica RX (recepción de la ESP, la app escribe)
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // caracteristica TX (transmisión de la ESP, la app lee/notifica)

static const char *TAG = "BLE_APP";



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


    int tickCount = 0;
    while (true){

        vTaskDelay(1000 / portTICK_PERIOD_MS); 

        if (deviceConnected) {
            tickCount++;
            
            // 5 segs
            if (tickCount >= 5) {
                std::string mensaje = "01045020";
                

                // el mensaje se pone en la caracteristica y se notifica a la app que esa ahi
                pTxCharacteristic->setValue(mensaje);
            
                pTxCharacteristic->notify();
                                
                // Reiniciamos el contador
                tickCount = 0; 
            }
        }
        // no hay app conectada 
        else {
            tickCount = 0; 
        }
    }
}