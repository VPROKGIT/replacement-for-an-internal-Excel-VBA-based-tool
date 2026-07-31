package com.vprok.forms.repository;

import com.vprok.forms.entity.ElementListOption;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ElementListOptionRepository extends JpaRepository<ElementListOption, Long> {

    List<ElementListOption> findByElementIdAndActiveTrueOrderByDisplayOrderAsc(Long elementId);

    /** Bulk variant for whole-tree reads (JSON export), so walking a page costs one query, not one per list field. */
    List<ElementListOption> findByElementIdInAndActiveTrueOrderByDisplayOrderAsc(Collection<Long> elementIds);

    List<ElementListOption> findByElementIdOrderByDisplayOrderAsc(Long elementId);

    Optional<ElementListOption> findByElementIdAndCode(Long elementId, String code);
}
