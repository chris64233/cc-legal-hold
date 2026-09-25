package com.chris64233.cc.legalhold.web;

import com.chris64233.cc.legalhold.service.ObjectService;
import com.chris64233.cc.legalhold.web.dto.DataObjectView;
import com.chris64233.cc.legalhold.web.dto.EligibilityView;
import com.chris64233.cc.legalhold.web.dto.RegisterObjectRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/objects")
public class ObjectController {

    private final ObjectService objectService;

    public ObjectController(ObjectService objectService) {
        this.objectService = objectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DataObjectView register(@Valid @RequestBody RegisterObjectRequest request) {
        return objectService.register(request);
    }

    @GetMapping("/{businessKey}/eligibility")
    public EligibilityView eligibility(@PathVariable String businessKey) {
        return objectService.eligibility(businessKey);
    }

    @PostMapping("/retention-rules")
    public RetentionRuleView saveRule(@Valid @RequestBody RetentionRuleRequest request) {
        return objectService.saveRule(request);
    }
}
