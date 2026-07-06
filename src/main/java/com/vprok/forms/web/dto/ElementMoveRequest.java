package com.vprok.forms.web.dto;

import jakarta.validation.constraints.NotNull;

public record ElementMoveRequest(@NotNull Long newParentElementId) {
}
