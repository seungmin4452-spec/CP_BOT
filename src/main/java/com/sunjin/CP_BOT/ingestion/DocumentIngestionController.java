package com.sunjin.CP_BOT.ingestion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * 사내 규정 PDF 업로드 API. SecurityConfig에서 ROLE_ADMIN만 호출 가능하도록 제한한다.
 */
@Validated
@RestController
@RequiredArgsConstructor
public class DocumentIngestionController {

    private final DocumentIngestionService documentIngestionService;

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/api/documents", consumes = "multipart/form-data")
    public DocumentIngestionService.IngestionResult ingest(
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentTitle") @NotBlank String documentTitle,
            @RequestParam(value = "category", defaultValue = "일반") String category,
            @RequestParam("allowedRoles") @NotEmpty Set<String> allowedRoles) {

        return documentIngestionService.ingest(file, documentTitle, category, allowedRoles);
    }
}
