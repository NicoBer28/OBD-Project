package com.obd.api.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ModelRepository extends JpaRepository<Model, UUID> {

    // The catalog as a picker shows it: grouped by brand, then by model.
    List<Model> findAllByOrderByModelBrandAscModelNameAsc();
}
