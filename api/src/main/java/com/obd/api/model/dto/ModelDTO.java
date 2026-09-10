package com.obd.api.model.dto;

import com.obd.api.model.Model;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;


public class ModelDTO {

    public record Create(
            @NotNull @Size(max = 60) String modelBrand,
            @NotNull @Size(max =60) String modelName,
            @NotNull @Size(max =40) String modelProtocol
    ){}

    public record Read(
            UUID modelId,
            String modelBrand,
            String modelName,
            String modelProtocol
    ){

        public static Read from(Model model){
            return new Read(
                    model.getModelId(),
                    model.getModelBrand(),
                    model.getModelName(),
                    model.getModelProtocol()
            );
        }

    }
}
