package com.vprok.forms.repository;

import com.vprok.forms.entity.UiAttributeEntryValue;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UiAttributeEntryValueRepository extends JpaRepository<UiAttributeEntryValue, Long> {

    /**
     * Every value of every entry of these elements, in entry order and, within an entry, in the
     * kinds' order - one query for a whole page (JSON export) or one element (the editor). Entry
     * and definition are fetched because callers read them after the transaction closes.
     */
    @Query("""
            select v from UiAttributeEntryValue v
              join fetch v.entry e
              join fetch v.definition d
             where e.element.id in :elementIds
             order by e.element.id, e.displayOrder, e.id, d.displayOrder, d.id""")
    List<UiAttributeEntryValue> findByElementIdIn(@Param("elementIds") Collection<Long> elementIds);

    @Modifying
    @Query("delete from UiAttributeEntryValue v where v.entry.id = :entryId")
    void deleteByEntryId(@Param("entryId") Long entryId);
}
