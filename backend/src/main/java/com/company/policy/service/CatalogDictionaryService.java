package com.company.policy.service;

import com.company.policy.dto.CatalogDictionaryRequest.Create;
import com.company.policy.dto.CatalogDictionaryRequest.Rename;
import com.company.policy.dto.CatalogDictionaryRequest.Type;
import com.company.policy.vo.CatalogDictionaryEntry;
import com.company.policy.vo.CurrentUserResponse;
import java.util.List;

public interface CatalogDictionaryService {
    List<CatalogDictionaryEntry> list(CurrentUserResponse session);

    void create(CurrentUserResponse session, Type type, Create request);

    void rename(CurrentUserResponse session, Type type, long id, Rename request);

    void delete(CurrentUserResponse session, Type type, long id, String expectedName);
}
