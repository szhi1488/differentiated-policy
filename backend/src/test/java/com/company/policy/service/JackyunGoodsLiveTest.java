package com.company.policy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.policy.common.config.JackyunProperties;
import com.company.policy.dto.JackyunGoodsQuery;
import com.company.policy.service.impl.JackyunGoodsServiceImpl;
import com.company.policy.vo.JackyunGoodsPage.Goods;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 按需用真实账套验证只读查询；普通 verify 不访问吉客云。 */
@EnabledIfEnvironmentVariable(named = "JACKYUN_LIVE_CHECK", matches = "1")
class JackyunGoodsLiveTest {
    @Test
    void checksSourceFieldsForBothBrands() {
        var config = new JackyunProperties();
        config.setAppKey(System.getenv("JACKYUN_APP_KEY"));
        config.setAppSecret(System.getenv("JACKYUN_APP_SECRET"));
        config.setToken(System.getenv("JACKYUN_TOKEN"));
        assertThat(config.isConfigured()).as("吉客云测试凭证已配置").isTrue();
        var service =
                new JackyunGoodsServiceImpl(
                        config,
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                        new ObjectMapper(),
                        Clock.systemUTC());

        for (String brand : List.of("OPPO手机", "一加手机")) {
            var page = service.queryPage(new JackyunGoodsQuery(brand, 3, "0", false));
            assertThat(page.goods().size()).as("%s 样本条数", brand).isBetween(1, 3);
            assertThat(page.nextLastMaxId().equals("0")).as("%s 游标向前推进", brand).isFalse();
            int[] present = new int[7];
            for (Goods goods : page.goods()) {
                String[] fields = {
                    goods.goodsName(),
                    goods.goodsNo(),
                    goods.skuName(),
                    goods.cateName(),
                    goods.goodsField4(),
                    goods.goodsField1(),
                    goods.goodsField5()
                };
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i] != null) {
                        present[i]++;
                    }
                }
            }
            System.out.printf(
                    "%s HTTP/业务码 200，样本 %d 条；非 null 字段：goodsName=%d goodsNo=%d skuName=%d cateName=%d goodsField4=%d goodsField1=%d goodsField5=%d%n",
                    brand,
                    page.goods().size(),
                    present[0],
                    present[1],
                    present[2],
                    present[3],
                    present[4],
                    present[5],
                    present[6]);
        }
    }
}
