package com.vprok.forms.web.ui;

import com.vprok.forms.web.dto.ElementResponse;
import java.util.List;

/** A tree node pre-fetched for rendering: children and allowed-child-types come along so the template never has to call back into services. */
public record ElementTreeNode(ElementResponse element, List<ElementTreeNode> children, List<String> allowedChildTypes) {
}
