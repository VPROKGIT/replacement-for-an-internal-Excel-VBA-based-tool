package com.vprok.forms.repository;

import com.vprok.forms.entity.Element;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    List<Element> findByPageIdIsNullAndTemplateFalseAndDeletedAtIsNullOrderByCodeAsc();

    List<Element> findByPageIdIsNullAndTemplateTrueAndDeletedAtIsNullOrderByCodeAsc();

    Optional<Element> findByIdAndDeletedAtIsNull(Long id);

    Optional<Element> findByElementTypeAndCodeAndDeletedAtIsNull(String elementType, String code);

    // Join-fetches page: callers render the source template page's label after the transaction
    // has closed (open-in-view is disabled), which a lazy proxy can't serve.
    @Query("""
            select e from Element e join fetch e.page p
            where e.elementType = :elementType and p.template = true
              and e.deletedAt is null and p.deletedAt is null
            order by p.code, e.code""")
    List<Element> findActiveByElementTypeOnTemplatePages(@Param("elementType") String elementType);
}
