package com.obd.api.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * An entry in the car model catalog: what a car is, and which OBD-II protocol
 * the adapter should speak to it.
 *
 * Curated reference data rather than user input - {@code POST /api/v1/cars}
 * takes a model id and rejects anything it does not recognise, so the table
 * cannot fill up with "VW" / "vw" / "Volkswagen" variants of the same car.
 */
@Entity
@Table(name = "models")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class Model {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID modelId;

    @Column(nullable = false, name = "brand")
    private String modelBrand;

    @Column(nullable = false, name = "model")
    private String modelName;

    // Nullable: the protocol for an older car is not always known up front.
    @Column(name = "protocol")
    private String modelProtocol;
}
