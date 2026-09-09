package com.obd.api.car;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CarRepository extends JpaRepository<Car, UUID> {

    // Derived from the entity property (carOwnerId), not the column (owner_id).
    List<Car> findByCarOwnerId(UUID carOwnerId);
}
