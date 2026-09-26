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
    uint8_t dlc;       // Data Length Code (0-8)
    uint8_t data[8];   // Payload del bus CAN puro
};

/**
 * @brief Abstract Base Class for OBD CAN interfaces.
 * Provides a common interface for different CAN hardware implementations (e.g., MCP2515, TWAI).
 */
class OBD_CAN_Interface {
public:
    virtual ~OBD_CAN_Interface() = default;

    /**
     * @brief Initialize the CAN interface hardware.
     * @return true if initialization is successful.
     */
    virtual bool init() = 0;

    /**
     * @brief Set the list of PIDs to be periodically polled.
     * @param pids Vector of PID bytes (e.g., {0x0C, 0x0D}).
     */
    void set_polling_pids(const std::vector<uint8_t>& pids) {
        polling_pids = pids;
        current_pid_index = 0;
    }

    /**
     * @brief Process incoming CAN messages and handle periodic PID requests.
     * This method must be called repeatedly in the main loop or task.
     */
    virtual void process() = 0;

    /**
     * @brief Inyectar la cola TX para transmisión asíncrona hacia BLE.
     * @param queue Cola configurada para elementos del tipo BleCanPacket.
     */
    void set_tx_queue(QueueHandle_t queue) {
        this->tx_queue = queue;
    }

protected:
    std::vector<uint8_t> polling_pids;
    size_t current_pid_index = 0;
    
    QueueHandle_t tx_queue = NULL;
};

class MCP2515; // Forward declaration

/**
 * @brief OBD Interface implementation using the MCP2515 CAN controller via SPI.
 */
class OBD_MCP2515 : public OBD_CAN_Interface {
public:
    /**
     * @brief Construct a new OBD_MCP2515 object.
     * 
     * @param spi_handle Pointer to a configured and initialized SPI device handle.
     * @param int_pin GPIO pin connected to the MCP2515 INT pin. 
     * @param rx_sem Optional semaphore to wait for interrupts efficiently during init().
     */
    OBD_MCP2515(spi_device_handle_t* spi_handle, gpio_num_t int_pin, SemaphoreHandle_t rx_sem = NULL);
    ~OBD_MCP2515();

    bool init() override;
    void process() override;

    /**
     * @brief Set the interval between PID requests.
     * @param ms Interval in milliseconds.
     */
    void set_request_interval(uint32_t ms) { request_interval_ms = ms; }

private:
    spi_device_handle_t* spi_handle;
    gpio_num_t int_pin;
    SemaphoreHandle_t rx_sem;
    MCP2515* mcp;

    uint32_t last_request_time = 0;
    uint32_t request_interval_ms = 50;

    void request_pid(uint8_t pid);
};

/**
 * @brief OBD Interface implementation using the ESP32 built-in TWAI controller.
 * (Declaration only, implementation reserved for future use).
 */
class OBD_TWAI : public OBD_CAN_Interface {
public:
    OBD_TWAI() {}
    ~OBD_TWAI() {}
    bool init() override { return false; }
    void process() override {}
};
