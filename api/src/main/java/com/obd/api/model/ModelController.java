package com.obd.api.model;


import com.obd.api.auth.UserPrincipal;
import com.obd.api.model.dto.ModelDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ModelDTO.Read create(@RequestBody @Valid ModelDTO.Create request){
        return modelService.create(request);
    }

    @GetMapping
    public List<ModelDTO.Read> getModels(){
        return modelService.getModels();
    }
}
