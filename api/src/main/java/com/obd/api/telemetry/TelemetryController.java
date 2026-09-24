package com.obd.api.telemetry;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.telemetry.dto.TelemetryDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService telemetryService;

    /**
     * 200 rather than 201: a batch that turns out to be all duplicates - the
     * normal case after a flaky upload - creates nothing, and there is no
     * single resource for a Location header to point at. 200 is true every
     * time.
     */
    @PostMapping
    public TelemetryDTO.Ingested ingest(@AuthenticationPrincipal UserPrincipal principal,
                                        @RequestBody @Valid TelemetryDTO.Ingest request) {
        return telemetryService.ingest(principal.getId(), request);
    }

    /**
     * Query parameters bound to a record rather than three {@code @RequestParam}s
     * so that a bad {@code limit} or an unparseable {@code since} become field
     * errors on the same 400 as body validation, instead of falling through to
     * the generic 500.
     */
    @GetMapping
    public TelemetryDTO.Page history(@AuthenticationPrincipal UserPrincipal principal,
                                     @Valid @ModelAttribute TelemetryDTO.Query query) {
        return telemetryService.history(principal.getId(), query);
    }
}
