package com.chris64233.cc.legalhold.web;

import com.chris64233.cc.legalhold.service.DeletionService;
import com.chris64233.cc.legalhold.web.dto.DeletionConfirmView;
import com.chris64233.cc.legalhold.web.dto.DeletionRequestView;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deletions")
@Validated
public class DeletionController {

    private final DeletionService deletionService;

    public DeletionController(DeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @PostMapping("/request/{businessKey}")
    public DeletionRequestView request(@PathVariable @NotBlank String businessKey) {
        return deletionService.requestDeletion(businessKey);
    }

    @PostMapping("/confirm/{token}")
    public DeletionConfirmView confirm(@PathVariable @NotBlank String token) {
        return deletionService.confirmDeletion(token);
    }
}
