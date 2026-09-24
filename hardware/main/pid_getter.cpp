#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "mcp2515.h"
#include "can.h"
#include <string.h>

static const char *TAG = "OBD_SCAN";

// pines SPI y MCP
#define MCP2515_MISO_PIN GPIO_NUM_19
#define MCP2515_MOSI_PIN GPIO_NUM_23
#define MCP2515_CLK_PIN GPIO_NUM_18
#define MCP2515_CS_PIN GPIO_NUM_5

bool requestVIN(MCP2515 &mcp2515) {
    can_frame request = {};
    request.can_id = 0x7DF;
    request.can_dlc = 8;
    request.data[0] = 0x02; // length
    request.data[1] = 0x09; // Mode 09
    request.data[2] = 0x02; // VIN
    for(int i = 3; i < 8; i++) request.data[i] = 0xCC; // padding

    if (mcp2515.sendMessage(&request) != MCP2515::ERROR_OK) {
        ESP_LOGE(TAG, "Error al enviar request VIN");
        return false;
    }

    int64_t start = esp_timer_get_time();
    int total_payload_len = 0;
    int received_len = 0;
    uint8_t payload[64]; 
    uint32_t ecu_rx_id = 0;

    while ((esp_timer_get_time() - start) < 2000000) { // 2s timeout max
        can_frame response;
        if (mcp2515.readMessage(&response) == MCP2515::ERROR_OK) {
            if (response.can_id >= 0x7E8 && response.can_id <= 0x7EF) {
                uint8_t pci = response.data[0] >> 4;
                
                if (pci == 0) { // Single Frame
                    int len = response.data[0] & 0x0F;
                    if (len >= 3 && response.data[1] == 0x49 && response.data[2] == 0x02) {
                        for(int i = 0; i < len; i++) {
                            payload[received_len++] = response.data[1+i];
                        }
                        break;
                    }
                } 
                else if (pci == 1) { // First Frame (VIN)
                    total_payload_len = ((response.data[0] & 0x0F) << 8) | response.data[1];
                    if (response.data[2] == 0x49 && response.data[3] == 0x02) {
                        ecu_rx_id = response.can_id - 8;
                        for(int i = 0; i < 6; i++) {
                            payload[received_len++] = response.data[2+i];
                        }
                        
                        // FCF para pedir el resto de la data
                        can_frame fc = {};
                        fc.can_id = ecu_rx_id;
                        fc.can_dlc = 8;
                        fc.data[0] = 0x30; // continue to send
                        fc.data[1] = 0x00; // send all
                        fc.data[2] = 0x00; // no delay
                        for(int i = 3; i < 8; i++) fc.data[i] = 0xCC;
                        mcp2515.sendMessage(&fc);
                        start = esp_timer_get_time(); // reset timeout
                    }
                } 
                else if (pci == 2) { // frame siguiente
                    for (int i = 1; i < 8 && received_len < total_payload_len; i++) {
                        payload[received_len++] = response.data[i];
                    }
                    if (received_len >= total_payload_len) break;
                    start = esp_timer_get_time(); // Reset timeouts
                }
            }
        }
        vTaskDelay(pdMS_TO_TICKS(1));
    }
    
    // parseo payload
    if (received_len > 3 && payload[0] == 0x49 && payload[1] == 0x02) {
        char vin_str[18];
        memset(vin_str, 0, sizeof(vin_str));
        
        int vin_len = received_len - 3;
        if (vin_len > 17) vin_len = 17; // VIN son 17 caracteres
        for (int i = 0; i < vin_len; i++) {
            vin_str[i] = payload[3 + i];
        }
        ESP_LOGI(TAG, "========================");
        ESP_LOGI(TAG, "==== VIN ENCONTRADO ====");
        ESP_LOGI(TAG, "VIN: %s", vin_str);
        ESP_LOGI(TAG, "========================");
        return true;
    }
    
    ESP_LOGE(TAG, "No se pudo leer el VIN. Bytes recibidos: %d", received_len);
    return false;
}

void querySupportedPIDs(MCP2515 &mcp2515) {
    uint8_t pids_to_check[] = {0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0};
    
    for (int p = 0; p < sizeof(pids_to_check); p++) {
        uint8_t base_pid = pids_to_check[p];
        can_frame request = {};
        request.can_id = 0x7DF;
        request.can_dlc = 8;
        request.data[0] = 0x02;
        request.data[1] = 0x01;
        request.data[2] = base_pid;
        for(int i = 3; i < 8; i++) request.data[i] = 0xCC; // padding

        if (mcp2515.sendMessage(&request) != MCP2515::ERROR_OK) {
            ESP_LOGE(TAG, "Fallo al enviar request para PID %02X", base_pid);
            continue;
        }

        int64_t start = esp_timer_get_time();
        bool found = false;
        
        while ((esp_timer_get_time() - start) < 1000000) { // 1s timeout
            can_frame response;
            if (mcp2515.readMessage(&response) == MCP2515::ERROR_OK) {
                if (response.can_id >= 0x7E8 && response.can_id <= 0x7EF) {
                    if (response.data[1] == 0x41 && response.data[2] == base_pid) {
                        uint32_t mask = (response.data[3] << 24) | (response.data[4] << 16) | 
                                        (response.data[5] << 8) | response.data[6];
                        
                        ESP_LOGI(TAG, "=========================");
                        ESP_LOGI(TAG, "==== MASCARA PID %02X ====", base_pid);
                        for (int i = 0; i < 32; i++) {
                            int pid = base_pid + i + 1;
                            bool supported = (mask & (1U << (31 - i))) != 0;
                            if (supported) {
                                ESP_LOGI(TAG, "PID %02X: SOPORTADO", pid);
                            }
                        }
                        ESP_LOGI(TAG, "=========================");
                        
                        found = true;
                        
                        if ((mask & 1) == 0) {
                            return; 
                        }
                        break; 
                    }
                }
            }
            vTaskDelay(pdMS_TO_TICKS(1));
        }
        
        if (!found) {
            ESP_LOGW(TAG, "Timeout esperando respuesta para mascara de PID %02X", base_pid);
            break; 
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

extern "C" void app_main(void)
{
    ESP_LOGI(TAG, "Inicializando SPI...");
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

    spi_device_handle_t spi_handle;
    ESP_ERROR_CHECK(spi_bus_add_device(SPI2_HOST, &devcfg, &spi_handle));

    ESP_LOGI(TAG, "Inicializando MCP2515 a 500KBPS...");
    MCP2515 mcp2515(&spi_handle);
    mcp2515.reset();
    mcp2515.setBitrate(CAN_500KBPS, MCP_8MHZ);
    mcp2515.setNormalMode();

    vTaskDelay(pdMS_TO_TICKS(2000));

    ESP_LOGI(TAG, "==== INICIANDO SCAN OBD2 ====");

    ESP_LOGI(TAG, ">> Buscando VIN (Mode 09 PID 02)...");
    requestVIN(mcp2515);

    vTaskDelay(pdMS_TO_TICKS(500));

    ESP_LOGI(TAG, ">> Escaneando PIDs soportados...");
    querySupportedPIDs(mcp2515);

    ESP_LOGI(TAG, "==== SCAN COMPLETADO ====");
    
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
