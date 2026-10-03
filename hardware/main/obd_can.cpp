#include "obd_can.h"
#include <stdlib.h>
#include <time.h>

#if CONFIG_OBD_USE_MCP2515
#include "mcp2515.h"
OBD_MCP2515::OBD_MCP2515(spi_device_handle_t* spi_handle, gpio_num_t int_pin, SemaphoreHandle_t rx_sem)
    : spi_handle(spi_handle), int_pin(int_pin), rx_sem(rx_sem) {
    mcp = new MCP2515(spi_handle);
}

OBD_MCP2515::~OBD_MCP2515() {
    delete mcp;
}

bool OBD_MCP2515::init() {
    mcp->reset();
    mcp->setBitrate(CAN_500KBPS, MCP_8MHZ);
    mcp->setNormalMode();
    mcp->clearInterrupts();
    mcp->setInterruptMask(MCP2515::CANINTF_RX0IF | MCP2515::CANINTF_RX1IF);
    return true;
}

void OBD_MCP2515::process() {
    uint32_t current_time = xTaskGetTickCount() * portTICK_PERIOD_MS;

    // leo mientras el int pin este activado
    while (gpio_get_level(int_pin) == 0) {
        // chequeo si hay data pendiente
        uint8_t irq = mcp->getInterrupts();
        
        // si no, salgo
        if (irq == 0) break;

        can_frame rx_frame;
        bool frame_read = false;

        // leo la data
        if (irq & MCP2515::CANINTF_RX0IF) {
            if (mcp->readMessage(MCP2515::RXB0, &rx_frame) == MCP2515::ERROR_OK) frame_read = true;
        } else if (irq & MCP2515::CANINTF_RX1IF) {
            if (mcp->readMessage(MCP2515::RXB1, &rx_frame) == MCP2515::ERROR_OK) frame_read = true;
        }
        
        // limpio flags
        if (irq & (MCP2515::CANINTF_TX0IF | MCP2515::CANINTF_TX1IF | MCP2515::CANINTF_TX2IF)) {
            mcp->clearTXInterrupts();
        }
        if (irq & MCP2515::CANINTF_MERRF) mcp->clearMERR();
        if (irq & MCP2515::CANINTF_ERRIF) mcp->clearERRIF();

        // mando paquete por queue
        if (frame_read && tx_queue != NULL) {
            BleCanPacket packet;
            packet.can_id = rx_frame.can_id;
            packet.dlc = rx_frame.can_dlc;
            for (int i = 0; i < 8; i++) {
                packet.data[i] = rx_frame.data[i];
            }
            xQueueSend(tx_queue, &packet, 0);
        }
    }
    // si paso el tiempo suficiente, pido el siguiente dato
    if (!polling_pids.empty() && (current_time - last_request_time > request_interval_ms)) {
        BleCanPacket next_pid = polling_pids[current_pid_index];
        request_pid(next_pid);
        
        current_pid_index = (current_pid_index + 1) % polling_pids.size();
        last_request_time = current_time;
    }
}

void OBD_MCP2515::request_pid(BleCanPacket packet) {
    struct can_frame tx_frame;
    // Asumimos frame estandar o extendido (el MCP2515 library maneja can_id con flag de extendido)
    tx_frame.can_id = packet.can_id; 
    if (packet.can_id > 0x7FF) {
        tx_frame.can_id |= CAN_EFF_FLAG; // asumiendo que MCP2515.h usa formato linux can.h
    }
    tx_frame.can_dlc = packet.dlc;
    for(int i = 0; i < packet.dlc; i++) {
        tx_frame.data[i] = packet.data[i];
    }
    mcp->sendMessage(&tx_frame);
}
#endif // CONFIG_OBD_USE_MCP2515


#if CONFIG_OBD_USE_TWAI
typedef struct
{
    uint32_t id;
    size_t len;
    uint8_t data[8];
} twai_can_rx_event_t;

// handler para la int de recepcion
static bool obd_twai_rx_handler(twai_node_handle_t handle, const twai_rx_done_event_data_t* edata, void* user_ctx)
{
    
    // creo buffer y lo paso para escritura
    uint8_t recv_buff[8];

    twai_frame_t rx_frame = {};
    rx_frame.buffer = recv_buff;
    rx_frame.buffer_len = sizeof(recv_buff);

    // si se recibio el paquete adecuadamente
    if (twai_node_receive_from_isr(handle, &rx_frame) == ESP_OK)
    {
        // obtengo id, len y escribo en received la data del buffer
        twai_can_rx_event_t received = {};
        received.id = rx_frame.header.id;
        received.len = rx_frame.buffer_len;

        for (size_t i = 0; i < received.len && i < sizeof(received.data); i++)
        {
            received.data[i] = recv_buff[i];
        }

        // mando por cola el paquete recibido y por ende mando a despertar a la tarea de lectura
        BaseType_t higher_priority_task_woken = pdFALSE;
        QueueHandle_t rx_queue = (QueueHandle_t)user_ctx;
        if (rx_queue != NULL) {
            xQueueSendFromISR(rx_queue, &received, &higher_priority_task_woken);
        }
        return higher_priority_task_woken == pdTRUE;
    }

    return false;
}

OBD_TWAI::OBD_TWAI(gpio_num_t tx_pin, gpio_num_t rx_pin)
    : tx_pin(tx_pin), rx_pin(rx_pin) {
    rx_queue = xQueueCreate(10, sizeof(twai_can_rx_event_t));
}

OBD_TWAI::~OBD_TWAI() {
    if (node_hdl != NULL) {
        twai_node_disable(node_hdl);
        twai_node_delete(node_hdl);
    }
    if (rx_queue != NULL) {
        vQueueDelete(rx_queue);
    }
}

bool OBD_TWAI::init() {
    if (rx_queue == NULL) {
        ESP_LOGE("OBD_TWAI", "Error: no se pudo crear rx_queue");
        return false;
    }

    // vars twai
    twai_onchip_node_config_t node_config = {};
    node_config.io_cfg.tx = tx_pin;
    node_config.io_cfg.rx = rx_pin;
    node_config.bit_timing.bitrate = 500000;
    node_config.tx_queue_depth = 5;

    twai_event_callbacks_t callbacks = {};
    callbacks.on_rx_done = obd_twai_rx_handler;

    esp_err_t err = twai_new_node_onchip(&node_config, &node_hdl);
    if (err != ESP_OK) {
        ESP_LOGE("OBD_TWAI", "Fallo al crear node_onchip: %s", esp_err_to_name(err));
        return false;
    }

    err = twai_node_register_event_callbacks(node_hdl, &callbacks, rx_queue);
    if (err != ESP_OK) {
        ESP_LOGE("OBD_TWAI", "Fallo al registrar callbacks: %s", esp_err_to_name(err));
        return false;
    }

    err = twai_node_enable(node_hdl);
    if (err != ESP_OK) {
        ESP_LOGE("OBD_TWAI", "Fallo al habilitar TWAI: %s", esp_err_to_name(err));
        return false;
    }

    ESP_LOGI("OBD_TWAI", "Driver TWAI (new API) inicializado a 500kbps");
    return true;
}

void OBD_TWAI::process() {
    uint32_t current_time = xTaskGetTickCount() * portTICK_PERIOD_MS;
    twai_can_rx_event_t received;
    
    // espero 10ms o a que me despierte la int
    if (xQueueReceive(rx_queue, &received, pdMS_TO_TICKS(10)) == pdTRUE) {
        // si hay cola de bt, mando la data por ahi
        if (tx_queue != NULL) {
            BleCanPacket packet;
            packet.can_id = received.id;
            packet.dlc = received.len;
            for (int i = 0; i < 8; i++) {
                packet.data[i] = received.data[i];
            }
            xQueueSend(tx_queue, &packet, 0);
        }
    }

    // si paso el tiempo suficiente, pido el siguiente dato
    if (!polling_pids.empty() && (current_time - last_request_time > request_interval_ms)) {
        BleCanPacket next_pid = polling_pids[current_pid_index];
        request_pid(next_pid);
        
        current_pid_index = (current_pid_index + 1) % polling_pids.size();
        last_request_time = current_time;
    }
}

void OBD_TWAI::request_pid(BleCanPacket packet) {
    if (node_hdl == NULL) return;

    // armo paquete
    twai_frame_t tx_msg = {};
    tx_msg.header.id = packet.can_id;
    tx_msg.header.ide = (packet.can_id > 0x7FF) ? 1 : 0;
    tx_msg.header.rtr = 0;
    
    uint8_t tx_data[8] = {0};
    for(int i = 0; i < packet.dlc; i++) {
        tx_data[i] = packet.data[i];
    }
    tx_msg.buffer = tx_data;
    tx_msg.buffer_len = packet.dlc;

    // transmito
    esp_err_t err = twai_node_transmit(node_hdl, &tx_msg, 10);
    if (err == ESP_OK) {
        tx_fail_count = 0;
    }
    // si esta fallando consistentemente, aborto comunicacion 
    else {
        tx_fail_count++;
        ESP_LOGW("OBD_TWAI", "Fallo transmision ID 0x%03X, error: %s, fallos: %d", (unsigned int)packet.can_id, esp_err_to_name(err), tx_fail_count);
        if (tx_fail_count >= 3) {
            ESP_LOGE("OBD_TWAI", "Demasiados fallos seguidos. Abortando polling de PIDs.");

            polling_pids.clear();
            tx_fail_count = 0;
        }
    }
}
#endif // CONFIG_OBD_USE_TWAI

#if CONFIG_OBD_USE_DUMMY
OBD_DUMMY::OBD_DUMMY() {
    srand(time(NULL));
}

OBD_DUMMY::~OBD_DUMMY() {}

bool OBD_DUMMY::init() {
    ESP_LOGI("OBD_DUMMY", "Interfaz Dummy inicializada");
    return true;
}

void OBD_DUMMY::process() {
    uint32_t current_time = xTaskGetTickCount() * portTICK_PERIOD_MS;

    if (!polling_pids.empty() && (current_time - last_request_time > request_interval_ms)) {
        BleCanPacket next_pid = polling_pids[current_pid_index];
        simulate_response(next_pid);
        
        current_pid_index = (current_pid_index + 1) % polling_pids.size();
        last_request_time = current_time;
    }
}

void OBD_DUMMY::simulate_response(BleCanPacket request) {
    if (tx_queue == NULL) return;

    uint8_t mode = request.data[1];
    uint8_t pid = request.data[2];

    BleCanPacket response;
    response.can_id = 0x7E8; 
    response.dlc = 8;
    
    response.data[1] = mode + 0x40;
    response.data[2] = pid;
    
    for (int i = 3; i < 8; i++) response.data[i] = 0xAA;

    switch (pid) {
        case 0x04:
            response.data[0] = 3; 
            response.data[3] = rand() % 256;
            break;
        case 0x05: 
            response.data[0] = 3;
            response.data[3] = 40 + (rand() % 100); 
            break;
        case 0x0B: 
            response.data[0] = 3;
            response.data[3] = 20 + (rand() % 80); 
            break;
        case 0x0C: 
            response.data[0] = 4;
            {
                uint16_t rpm_val = 800 * 4 + (rand() % (2000 * 4)); 
                response.data[3] = (rpm_val >> 8) & 0xFF;
                response.data[4] = rpm_val & 0xFF;
            }
            break;
        case 0x0D: 
            response.data[0] = 3;
            response.data[3] = rand() % 120; 
            break;
        case 0x0F: 
            response.data[0] = 3;
            response.data[3] = 40 + (rand() % 40); 
            break;
        case 0x10: 
            response.data[0] = 4;
            {
                uint16_t maf_val = 500 + (rand() % 4500); 
                response.data[3] = (maf_val >> 8) & 0xFF;
                response.data[4] = maf_val & 0xFF;
            }
            break;
        case 0x11: 
            response.data[0] = 3;
            response.data[3] = rand() % 256;
            break;
        case 0x1F: 
            response.data[0] = 4;
            {
                uint16_t time_sec = xTaskGetTickCount() * portTICK_PERIOD_MS / 1000;
                response.data[3] = (time_sec >> 8) & 0xFF;
                response.data[4] = time_sec & 0xFF;
            }
            break;
        case 0x2F: 
            response.data[0] = 3;
            response.data[3] = 25 + (rand() % 205); 
            break;
        case 0x33: 
            response.data[0] = 3;
            response.data[3] = 90 + (rand() % 20); 
            break;
        default:
            response.data[0] = 3; 
            response.data[3] = rand() % 256;
            break;
    }

    xQueueSend(tx_queue, &response, 0);
}
#endif // CONFIG_OBD_USE_DUMMY

