package com.company.policy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.CatalogDictionaryRequest.Create;
import com.company.policy.dto.CatalogDictionaryRequest.Rename;
import com.company.policy.dto.CatalogDictionaryRequest.Type;
import com.company.policy.mapper.CatalogDictionaryMapper;
import com.company.policy.mapper.CatalogGoodsMapper;
import com.company.policy.vo.CurrentUserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CatalogDictionaryServiceImplTest {
    @Test
    void rejectsWritesThatAffectNoRows() {
        var dictionary = mock(CatalogDictionaryMapper.class);
        var service =
                new CatalogDictionaryServiceImpl(
                        mock(CatalogAccess.class), dictionary, mock(CatalogGoodsMapper.class));
        var admin = new CurrentUserResponse("1", "admin", "tenant", "管理员", "ADMIN");
        when(dictionary.lockName(Type.brand, 1)).thenReturn("旧品牌");
        when(dictionary.linkedSeries(1)).thenReturn(null);
        when(dictionary.linkedSource(1)).thenReturn(null);

        assertConflict(() -> service.create(admin, Type.brand, new Create("品牌", null)));
        assertConflict(() -> service.rename(admin, Type.brand, 1, new Rename("新品牌", "旧品牌")));
        assertConflict(() -> service.delete(admin, Type.brand, 1, "旧品牌"));
    }

    private static void assertConflict(Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(error.getCode()).isEqualTo("CATALOG_DICTIONARY_CHANGED");
                        });
    }
}
