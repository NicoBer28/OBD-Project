package com.obd.api.model;

import com.obd.api.model.dto.ModelDTO;
import com.obd.api.model.exception.ModelAlreadyExistsException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ModelService {

    private final ModelRepository modelRepository;


    @Transactional
    public ModelDTO.Read create (ModelDTO.Create request){
        Model model = Model.builder()
                .modelBrand(request.modelBrand().trim())
                .modelProtocol(blankToNull(request.modelProtocol().trim()))
                .modelName(request.modelName())
                .build();

        Model save;
        try {
            save = modelRepository.saveAndFlush(model);
        }catch (DataIntegrityViolationException e){
            throw new ModelAlreadyExistsException(model);
        }

        return ModelDTO.Read.from(save);
    }

    public List<ModelDTO.Read> getModels(){
        return modelRepository.findAllByOrderByModelBrandAscModelNameAsc().stream().map(ModelDTO.Read::from).toList();
    }

    private static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
