workspace "OBD-C" "Estructura central del funcionamiento del OBD-C" {

    !identifiers hierarchical

    model {
        archetypes {
            application = container
            hardware = container
        }

        u = person "Usuario" "Monitorea el estado actual y el historial"

        ns = element "Servicio de Notificaciones Push" "Firebase Cloud Messaging" "Gestiona y enruta notificaciones móviles"

        ss = softwareSystem "Sistema de Monitoreo Vehicular" {
            app = application "App Mòvil" "Recibe y muestra el estado del vehiculo" "Flutter" {
                tags "APP"
            }

            dvc = hardware "Dispositivo OBD2" "Lee el estado del auto a traves del CAN bus" "ESP32" {
                tags "hardwareDevice"
            }
          
            api = container "API Backend" "Procesa telemetría y provee datos históricos" "Java / Spring Boot" {
                tags "API"
            }


            db = container "Base de Datos" "Registra estado y datos del vehiculo" "PostgreSQL" {
                tags "Database"
            }

            dvc -> app "Envia telemetria en tiempo real" "BLE / Bluetooth"
            app -> api "Sincroniza y envia lotes de telemetria" "HTTPS"
            api -> db "Inserta registros de telemetría" "SQL (Escritura)"

            api -> db "Consulta telemetría historica" "SQL (Lectura)"
            app -> api "Solicita estadisticas historicas" "HTTPS / JSON"
        }

        ns -> ss.app "Entrega notificación push" "TCP/IP"
        ss.app -> u "Muestra alerta en pantalla" "UI"
        u -> ss.app "Visualiza datos y estadisticas" "UI / UX"
        ss.api -> ns "Dispara alerta" "HTTPS / Webhook"
    }

    views {
        systemContext ss "Nivel1" {
            include *
        }

        container ss "Nivel2" {
            include * "->ns->"
        }

        styles {
            element "Element" {
                color #000000
                stroke #0773af
                strokeWidth 7
                shape roundedbox
            }
            element "Person" {
                shape person
            }
            element "Database" {
                shape cylinder
            }
            element "Boundary" {
                strokeWidth 5
            }
            relationship "Relationship" {
                thickness 4
            }

            element "APP" {
                shape MobileDevicePortrait
                background #117fba
            }

            element "API" {
                shape hexagon
            }

            element "hardwareDevice" {
                shape robot
                background #20c267
                stroke #26a45d
            }
        }
    }

    configuration {
        scope softwaresystem
    }

}