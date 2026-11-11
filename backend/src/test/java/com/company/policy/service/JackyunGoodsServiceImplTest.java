package com.company.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.company.policy.common.config.JackyunProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.JackyunGoodsQuery;
import com.company.policy.service.impl.JackyunGoodsServiceImpl;
import com.company.policy.vo.JackyunGoodsPage.Goods;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class JackyunGoodsServiceImplTest {
    private static final String CURSOR = "2216051345374512128";
    private final HttpClient client = mock(HttpClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final JackyunProperties properties = new JackyunProperties();
    private final JackyunGoodsService service =
            new JackyunGoodsServiceImpl(
                    properties,
                    client,
                    mapper,
                    Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC));
    private final JackyunGoodsQuery query = new JackyunGoodsQuery("OPPO手机", 3, CURSOR, true);

    @Test
    void signsTheOriginalJsonAndKeepsTheSourceFieldsAndExactCursor() throws Exception {
        configure();
        properties.setToken("test+token&value");
        respond(
                """
                {"code":200,"result":{"contextId":"42","data":[
                  {"brandName":"OPPO手机","skuId":2216051345374512129,"goodsName":" 样例手机 ",
                   "goodsNo":"SAMPLE","skuName":"8+128","cateName":"样例型号",
                   "goodsField4":"","goodsField1":null,"retailPrice":10}
                ]}}
                """);

        var page = service.queryPage(query);
        assertThat(page.brandName()).isEqualTo("OPPO手机");
        assertThat(page.goods())
                .containsExactly(new Goods(" 样例手机 ", "SAMPLE", "8+128", "样例型号", "", null, null));
        assertThat(page.nextLastMaxId()).isEqualTo("2216051345374512129");

        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(requests.capture(), any());
        var request = requests.getValue();
        assertThat(request.uri().toString()).isEqualTo("https://open.jackyun.com/open/openapi/do");
        assertThat(request.method()).isEqualTo("POST");
        Map<String, String> form = readForm(request);
        assertThat(form)
                .containsEntry("method", "erp-goods.goods.sku.search")
                .containsEntry("version", "v1.0")
                .containsEntry("timestamp", "2025-01-01 08:00:00")
                .containsEntry("token", "test+token&value")
                .containsEntry("sign", "104e3347e6a50564ec255c3dfb8fb789");
        var business = mapper.readTree(form.get("bizcontent"));
        assertThat(business.path("brandName").asText()).isEqualTo("OPPO手机");
        assertThat(business.path("lastMaxId").isIntegralNumber()).isTrue();
        assertThat(business.path("lastMaxId").asText()).isEqualTo(CURSOR);
        assertThat(business.path("pageIndex").intValue()).isZero();
        assertThat(business.path("isIncludeBlockup").intValue()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"一加手机,2216051345374512129", "OPPO手机,2216051345374512128"})
    void rejectsWrongBrandOrStalledCursor(String brand, String skuId) throws Exception {
        configure();
        respond(
                """
                {"code":200,"result":{"data":[{"brandName":"%s","skuId":"%s"}]}}
                """
                        .formatted(brand, skuId));
        assertThatThrownBy(() -> service.queryPage(query))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("JACKYUN_INVALID_RESPONSE"));
    }

    @Test
    void keepsTheCursorOnAnEmptyPage() throws Exception {
        configure();
        respond("{\"code\":200,\"result\":{\"data\":[]}}");
        var page = service.queryPage(query);
        assertThat(page.goods()).isEmpty();
        assertThat(page.nextLastMaxId()).isEqualTo(CURSOR);
    }

    @Test
    void reportsBusinessFailureWithoutExposingTheUpstreamMessage() throws Exception {
        configure();
        respond("{\"code\":0,\"msg\":\"private-upstream-response\"}");
        assertThatThrownBy(() -> service.queryPage(query))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("JACKYUN_BUSINESS_ERROR"))
                .hasMessageNotContaining("private-upstream-response");
    }

    @Test
    void missingCredentialsFailBeforeAnyNetworkCall() {
        assertThatThrownBy(() -> service.queryPage(query))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("JACKYUN_CONFIG_MISSING"));
        verifyNoInteractions(client);
    }

    private void configure() {
        properties.setAppKey("10000000");
        properties.setAppSecret("TestSecret");
    }

    private void respond(String body) throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        when(client.<String>send(any(), any())).thenReturn(response);
    }

    private static Map<String, String> readForm(HttpRequest request) {
        var subscriber = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        request.bodyPublisher()
                .orElseThrow()
                .subscribe(
                        new Flow.Subscriber<ByteBuffer>() {
                            @Override
                            public void onSubscribe(Flow.Subscription subscription) {
                                subscriber.onSubscribe(subscription);
                            }

                            @Override
                            public void onNext(ByteBuffer item) {
                                subscriber.onNext(List.of(item));
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                subscriber.onError(throwable);
                            }

                            @Override
                            public void onComplete() {
                                subscriber.onComplete();
                            }
                        });
        var result = new HashMap<String, String>();
        for (String parameter : subscriber.getBody().toCompletableFuture().join().split("&")) {
            var entry = parameter.split("=", 2);
            result.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
