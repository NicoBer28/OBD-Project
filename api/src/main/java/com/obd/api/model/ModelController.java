package com.obd.api.model;


import com.obd.api.auth.UserPrincipal;
import com.obd.api.model.dto.ModelDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    // 201 like the other creates. No Location: there is no GET /models/{id}.
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ModelDTO.Read create(@RequestBody @Valid ModelDTO.Create request){
        return modelService.create(request);
    }

    @GetMapping
    public List<ModelDTO.Read> getModels(){
        return modelService.getModels();
    }
}
