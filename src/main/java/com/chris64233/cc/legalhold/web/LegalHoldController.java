package com.chris64233.cc.legalhold.web;

import com.chris64233.cc.legalhold.service.LegalHoldService;
import com.chris64233.cc.legalhold.web.dto.HoldRequest;
import com.chris64233.cc.legalhold.web.dto.HoldResultView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/legal-holds")
public class LegalHoldController {

    private final LegalHoldService legalHoldService;

    public LegalHoldController(LegalHoldService legalHoldService) {
        this.legalHoldService = legalHoldService;
    }

    @PostMapping("/apply")
    public HoldResultView apply(@Valid @RequestBody HoldRequest request) {
        return legalHoldService.apply(request);
    }

    @PostMapping("/release")
    public HoldResultView release(@Valid @RequestBody HoldRequest request) {
        return legalHoldService.release(request);
    }
}
