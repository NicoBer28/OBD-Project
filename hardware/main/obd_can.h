#pragma once

#include <stdint.h>
#include <vector>
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"




struct __attribute__((packed)) BleCanPacket {
    uint32_t can_id;   // ID del mensaje (Soporta UDS Modo 22 y CAN Sniffing)
    uint8_t dlc;       // DLC (0-8)
    uint8_t data[8];
};

/**
 * @brief Clase abstracta para interfaces CAN OBD.
 * Interfaz común para diferentes implementaciones de hardware CAN.
 */
class OBD_CAN_Interface {
public:
    virtual ~OBD_CAN_Interface() = default;

    /**
     * @brief Inicializa el hardware de la interfaz CAN.
     * @return true si la inicialización es exitosa.
     */
    virtual bool init() = 0;

    /**
     * @brief Establece la lista de paquetes que serán consultados periódicamente.
     * @param pids Vector de BleCanPacket
     */
    void set_polling_pids(const std::vector<BleCanPacket>& pids) {
        polling_pids = pids;
        current_pid_index = 0;
    }

    /**
     * @brief Procesa los mensajes CAN entrantes y maneja las peticiones periódicas de PIDs.
     * Este método debe llamarse repetidamente en el bucle principal o en una tarea.
     */
    virtual void process() = 0;

    /**
     * @brief Inyecta la cola de TX para transmisión asíncrona por BLE.
     * @param queue Cola configurada para elementos BleCanPacket.
     */
    void set_tx_queue(QueueHandle_t queue) {
        this->tx_queue = queue;
    }

protected:
    // PIDs que se le pediran al auto
    std::vector<BleCanPacket> polling_pids;
    size_t current_pid_index = 0;
    
    // cola para enviar datos de lectura CAN a BT
    QueueHandle_t tx_queue = NULL;
};

#if CONFIG_OBD_USE_MCP2515
class MCP2515;

/**
 * @brief Implementación de la interfaz OBD usando el MCP2515 vía SPI.
 */
class OBD_MCP2515 : public OBD_CAN_Interface {
public:
    /**
     * @brief Construye un nuevo objeto OBD_MCP2515.
     * 
     * @param spi_handle Puntero a handler de dispositivo SPI configurado e inicializado.
     * @param int_pin Pin GPIO conectado al pin INT del MCP2515. 
     * @param rx_sem Semáforo opcional para esperar interrupciones durante el init().
     */
    OBD_MCP2515(spi_device_handle_t* spi_handle, gpio_num_t int_pin, SemaphoreHandle_t rx_sem = NULL);
    ~OBD_MCP2515();

    bool init() override;
    void process() override;

    /**
     * @brief Establece el intervalo entre peticiones de PIDs.
     * @param ms Intervalo en milisegundos.
     */
    void set_request_interval(uint32_t ms) { request_interval_ms = ms; }

private:

    spi_device_handle_t* spi_handle;
    // lo usa el mcp para avisar que llego dato
    gpio_num_t int_pin;

    SemaphoreHandle_t rx_sem;
    // objeto mcp
    MCP2515* mcp;

    uint32_t last_request_time = 0;
    uint32_t request_interval_ms = 50;

    void request_pid(BleCanPacket packet);
};
#endif // CONFIG_OBD_USE_MCP2515

#if CONFIG_OBD_USE_TWAI
#include "esp_twai.h"
#include "esp_twai_onchip.h"

/**
 * @brief Implementación de la interfaz OBD usando el controlador TWAI integrado en el ESP32.
 */
class OBD_TWAI : public OBD_CAN_Interface {
public:
    OBD_TWAI(gpio_num_t tx_pin, gpio_num_t rx_pin);
    ~OBD_TWAI();

    bool init() override;
    void process() override;

    void set_request_interval(uint32_t ms) { request_interval_ms = ms; }

private:
    // pines serial
    gpio_num_t tx_pin;
    gpio_num_t rx_pin;

    // nodo necesario para twai
    twai_node_handle_t node_hdl = NULL;

    // cola para recepcion de datos por twai
    QueueHandle_t rx_queue = NULL;

    uint32_t last_request_time = 0;
    uint32_t request_interval_ms = 50;
    uint8_t tx_fail_count = 0;

    void request_pid(BleCanPacket packet);
};
#endif // CONFIG_OBD_USE_TWAI

#if CONFIG_OBD_USE_DUMMY
/**
 * @brief Implementación Dummy de la interfaz OBD.
 * Genera datos aleatorios en lugar de consultar hardware real.
 */
class OBD_DUMMY : public OBD_CAN_Interface {
public:
    OBD_DUMMY();
    ~OBD_DUMMY();

    bool init() override;
    void process() override;

    void set_request_interval(uint32_t ms) { request_interval_ms = ms; }

private:
    uint32_t last_request_time = 0;
    uint32_t request_interval_ms = 50;

    void simulate_response(BleCanPacket request);
};
#endif // CONFIG_OBD_USE_DUMMY
