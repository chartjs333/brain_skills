package local.codex.skills.manager.controller;

import java.util.List;

import local.codex.skills.manager.model.GenerateVariationsRequest;
import local.codex.skills.manager.model.OriginalSkillDetail;
import local.codex.skills.manager.model.OriginalSkillSummary;
import local.codex.skills.manager.model.SkillVariationDetail;
import local.codex.skills.manager.model.SkillVariationSummary;
import local.codex.skills.manager.model.VariationGenerationResult;
import local.codex.skills.manager.service.OriginalSkillLibraryService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/original-skills")
public class OriginalSkillController {
    private final OriginalSkillLibraryService libraryService;

    public OriginalSkillController(OriginalSkillLibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @GetMapping
    public List<OriginalSkillSummary> listOriginals() {
        return libraryService.listOriginals();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<OriginalSkillSummary> upload(@RequestParam("files") List<MultipartFile> files) {
        return libraryService.upload(files);
    }

    @GetMapping("/{originalId}")
    public OriginalSkillDetail getOriginal(@PathVariable("originalId") String originalId) {
        return libraryService.getOriginal(originalId);
    }

    @GetMapping(value = "/{originalId}/download", produces = "text/markdown")
    public ResponseEntity<String> downloadOriginal(@PathVariable("originalId") String originalId) {
        OriginalSkillDetail original = libraryService.getOriginal(originalId);
        return markdownDownload(original.fileName(), original.content());
    }

    @GetMapping("/{originalId}/variations")
    public List<SkillVariationSummary> listVariations(@PathVariable("originalId") String originalId) {
        return libraryService.listVariations(originalId);
    }

    @PostMapping("/{originalId}/variations")
    public VariationGenerationResult generateVariations(
            @PathVariable("originalId") String originalId,
            @RequestBody(required = false) GenerateVariationsRequest request
    ) {
        return libraryService.generateVariations(originalId, request);
    }

    @GetMapping("/{originalId}/variations/{variationId}")
    public SkillVariationDetail getVariation(
            @PathVariable("originalId") String originalId,
            @PathVariable("variationId") String variationId
    ) {
        return libraryService.getVariation(originalId, variationId);
    }

    @GetMapping(value = "/{originalId}/variations/{variationId}/download", produces = "text/markdown")
    public ResponseEntity<String> downloadVariation(
            @PathVariable("originalId") String originalId,
            @PathVariable("variationId") String variationId
    ) {
        SkillVariationDetail variation = libraryService.getVariation(originalId, variationId);
        return markdownDownload(variation.fileName(), variation.content());
    }

    @DeleteMapping("/{originalId}/variations/{variationId}")
    public ResponseEntity<Void> deleteVariation(
            @PathVariable("originalId") String originalId,
            @PathVariable("variationId") String variationId
    ) {
        libraryService.deleteVariation(originalId, variationId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<String> markdownDownload(String fileName, String content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType("text/markdown; charset=utf-8"))
                .body(content);
    }
}
