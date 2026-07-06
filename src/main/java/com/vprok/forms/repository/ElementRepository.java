package com.vprok.forms.repository;

import com.vprok.forms.entity.Element;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Derived queries here filter out soft-deleted rows (deleted_at IS NULL) by
 * default, matching the schema convention that normal reads never see
 * soft-deleted elements. Callers that genuinely need deleted rows (e.g. an
 * undo feature) can fall back to the plain {@link #findById} / {@link #findAll}
 * inherited from JpaRepository, which apply no such filter.
 */
public interface ElementRepository extends JpaRepository<Element, Long> {

    List<Element> findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(Long parentElementId);

    List<Element> findByPageIdAndDeletedAtIsNullOrderByDisplayOrderAsc(Long pageId);

    List<Element> findByPageIdIsNullAndDeletedAtIsNullOrderByCodeAsc();

    Optional<Element> findByIdAndDeletedAtIsNull(Long id);

    Optional<Element> findByElementTypeAndCodeAndDeletedAtIsNull(String elementType, String code);
}
