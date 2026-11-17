package com.company.policy.service;

import com.company.policy.dto.CatalogDictionaryRequest.Create;
import com.company.policy.dto.CatalogDictionaryRequest.Rename;
import com.company.policy.dto.CatalogDictionaryRequest.Type;
import com.company.policy.vo.CatalogDictionaryEntry;
import com.company.policy.vo.CurrentUserResponse;
import java.util.List;

/** 维护商品品牌、系列和颜色字典，支持查询、新增、改名和删除。 */
public interface CatalogDictionaryService {
    /** 列出品牌、系列和颜色字典项及其引用状态。 */
    List<CatalogDictionaryEntry> list(CurrentUserResponse session);

    /** 新增品牌、系列或颜色，新增系列时指定所属品牌。 */
    void create(CurrentUserResponse session, Type type, Create request);

    /** 核对原名称和引用状态后，为指定字典项改名。 */
    void rename(CurrentUserResponse session, Type type, long id, Rename request);

    /** 核对名称并删除未被引用的字典项，删除系列时一并清理其下未被引用的机型。 */
    void delete(CurrentUserResponse session, Type type, long id, String expectedName);
}
