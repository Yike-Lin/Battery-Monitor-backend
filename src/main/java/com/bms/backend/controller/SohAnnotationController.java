package com.bms.backend.controller;

import com.bms.backend.dto.SohAnnotationCreateRequest;
import com.bms.backend.entity.SohAnnotation;
import com.bms.backend.service.SohAnnotationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/batteries")
@CrossOrigin(origins = "*")
public class SohAnnotationController {

    private final SohAnnotationService sohAnnotationService;

    public SohAnnotationController(SohAnnotationService sohAnnotationService) {
        this.sohAnnotationService = sohAnnotationService;
    }

    /**
     * 保存 SOH 标注（不绑 cycle，按 battery_code 标注）
     */
    @PostMapping("/soh-annotations")
    public ResponseEntity<?> create(@RequestBody SohAnnotationCreateRequest request) {
        SohAnnotation saved = sohAnnotationService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.getId());
    }
}

