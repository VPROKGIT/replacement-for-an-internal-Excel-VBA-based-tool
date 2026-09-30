package com.vprok.forms.repository;

import com.vprok.forms.entity.UiAttributeEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UiAttributeEntryRepository extends JpaRepository<UiAttributeEntry, Long> {

    List<UiAttributeEntry> findByElementIdOrderByDisplayOrderAscIdAsc(Long elementId);
}
