package com.vprok.forms.web.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** orderedElementIds must contain exactly the current (non-deleted) children of the target parent, each once. */
public record ReorderRequest(@NotEmpty List<Long> orderedElementIds) {
}
