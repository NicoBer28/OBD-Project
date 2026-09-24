package com.obd.api.model;

import com.obd.api.model.dto.ModelDTO;
import com.obd.api.model.exception.ModelAlreadyExistsException;
import com.obd.api.support.RepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The model catalog against a real database, on top of the eight rows
 * V2__cars_and_models.sql seeds.
 */
@RepositoryTest
@Import(ModelService.class)
class ModelServiceTest {

    @Autowired
    private ModelService modelService;
    @Autowired
    private ModelRepository modelRepository;

    @Test
    void theCatalogIsOrderedByBrandThenModel() {
        List<ModelDTO.Read> models = modelService.getModels();

        assertThat(models).hasSize(8);
        assertThat(models).extracting(ModelDTO.Read::modelBrand)
                .isSorted();
        // Both Volkswagens are adjacent and ordered by model, not insertion.
        assertThat(models).extracting(m -> m.modelBrand() + " " + m.modelName())
                .containsSubsequence("Volkswagen Gol", "Volkswagen Golf")
                .startsWith("Chevrolet Onix");
    }

    @Test
    void createAddsToTheCatalog() {
        ModelDTO.Read created = modelService.create(
                new ModelDTO.Create("Honda", "Civic", "ISO 15765-4 (CAN)"));

        assertThat(created.modelId()).isNotNull();
        assertThat(created.modelBrand()).isEqualTo("Honda");
        assertThat(modelRepository.findById(created.modelId())).isPresent();
        assertThat(modelService.getModels()).hasSize(9);
    }

    @Test
    void createTrimsTheBrandAndDropsABlankProtocol() {
        // An older car's protocol may be unknown; blank means "not known",
        // so it is stored as null rather than as an empty string.
        ModelDTO.Read created = modelService.create(
                new ModelDTO.Create("  Honda ", "Fit", "   "));

        assertThat(created.modelBrand()).isEqualTo("Honda");
        assertThat(created.modelProtocol()).isNull();
    }

    @Test
    void aDuplicateBrandAndModelIsRefused() {
        // Fiat Punto is in the seed; ux_models_brand_model refuses a second one.
        assertThatThrownBy(() -> modelService.create(
                new ModelDTO.Create("Fiat", "Punto", "ISO 9141-2")))
                .isInstanceOf(ModelAlreadyExistsException.class);
    }

    @Test
    void theSameModelNameUnderAnotherBrandIsFine() {
        // Uniqueness is (brand, model), not model alone.
        ModelDTO.Read created = modelService.create(
                new ModelDTO.Create("Lada", "Punto", "ISO 9141-2"));

        assertThat(created.modelName()).isEqualTo("Punto");
        assertThat(created.modelBrand()).isEqualTo("Lada");
    }
}
