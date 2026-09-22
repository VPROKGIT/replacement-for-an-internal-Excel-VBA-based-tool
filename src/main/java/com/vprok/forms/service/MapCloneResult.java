package com.vprok.forms.service;

import com.vprok.forms.entity.Element;
import java.util.List;

/** The new MAP, plus any copied codes that had to be suffixed ("STREET → STREET_2"). */
public record MapCloneResult(Element clonedMap, List<String> renamedCodes) {
}
