#include "obd_can.h"
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
        uint8_t next_pid = polling_pids[current_pid_index];
        request_pid(next_pid);
        
        current_pid_index = (current_pid_index + 1) % polling_pids.size();
        last_request_time = current_time;
    }
}

void OBD_MCP2515::request_pid(uint8_t pid) {
    struct can_frame tx_frame;
    tx_frame.can_id = 0x7DF; 
    tx_frame.can_dlc = 8;
    tx_frame.data[0] = 0x02;
    tx_frame.data[1] = 0x01;
    tx_frame.data[2] = pid;
    for(int i = 3; i < 8; i++) {
        tx_frame.data[i] = 0xCC;
    }
    mcp->sendMessage(&tx_frame);
}
