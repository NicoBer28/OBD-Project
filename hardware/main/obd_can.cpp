#include "obd_can.h"

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

static bool obd_twai_rx_handler(twai_node_handle_t handle, const twai_rx_done_event_data_t* edata, void* user_ctx)
{
    uint8_t recv_buff[8];

    twai_frame_t rx_frame = {};
    rx_frame.buffer = recv_buff;
    rx_frame.buffer_len = sizeof(recv_buff);

    if (twai_node_receive_from_isr(handle, &rx_frame) == ESP_OK)
    {
        twai_can_rx_event_t received = {};
        received.id = rx_frame.header.id;
        received.len = rx_frame.buffer_len;

        for (size_t i = 0; i < received.len && i < sizeof(received.data); i++)
        {
            received.data[i] = recv_buff[i];
        }

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
    
    // Leemos de la cola bloqueante (emula twai_receive) por 10ms
    if (xQueueReceive(rx_queue, &received, pdMS_TO_TICKS(10)) == pdTRUE) {
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

    twai_frame_t tx_msg = {};
    tx_msg.header.id = packet.can_id;
    // Asumimos que si el can_id es mayor a 0x7FF, es extendido (29 bits)
    tx_msg.header.ide = (packet.can_id > 0x7FF) ? 1 : 0;
    tx_msg.header.rtr = 0;
    
    uint8_t tx_data[8] = {0};
    for(int i = 0; i < packet.dlc; i++) {
        tx_data[i] = packet.data[i];
    }
    tx_msg.buffer = tx_data;
    tx_msg.buffer_len = packet.dlc;

    esp_err_t err = twai_node_transmit(node_hdl, &tx_msg, 10);
    if (err == ESP_OK) {
        tx_fail_count = 0;
    } else {
        tx_fail_count++;
        ESP_LOGW("OBD_TWAI", "Fallo transmision ID 0x%03X, error: %s, fallos: %d", (unsigned int)packet.can_id, esp_err_to_name(err), tx_fail_count);
        if (tx_fail_count >= 3) {
            ESP_LOGE("OBD_TWAI", "Demasiados fallos seguidos. Abortando polling de PIDs.");
            // No existe un clear_transmit_queue explicito en el nuevo API onchip.
            // Si estalla, simplemente abortamos el polling_pids
            polling_pids.clear();
            tx_fail_count = 0;
        }
    }
}
#endif // CONFIG_OBD_USE_TWAI

