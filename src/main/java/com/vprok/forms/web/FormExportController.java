package com.vprok.forms.web;

import com.vprok.forms.service.FormExportService;
import com.vprok.forms.web.dto.export.ExportNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend-facing JSON export of a whole page. Separate paths for code and id lookup rather than
 * one overloaded path segment, so neither can be mistaken for the other.
 */
@RestController
@RequestMapping("/api/export/pages")
public class FormExportController {

    private final FormExportService formExportService;

    public FormExportController(FormExportService formExportService) {
        this.formExportService = formExportService;
    }

    @GetMapping("/by-code/{code}")
    public ExportNode exportByCode(@PathVariable String code) {
        return formExportService.exportByCode(code);
    }

    @GetMapping("/by-id/{id}")
    public ExportNode exportById(@PathVariable Long id) {
        return formExportService.exportById(id);
    }
}
