package com.company.policy.service.impl;

import com.company.policy.common.config.JackyunProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.JackyunGoodsQuery;
import com.company.policy.service.JackyunGoodsService;
import com.company.policy.vo.JackyunGoodsPage;
import com.company.policy.vo.JackyunGoodsPage.Goods;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class JackyunGoodsServiceImpl implements JackyunGoodsService {
    private static final URI GATEWAY = URI.create("https://open.jackyun.com/open/openapi/do");
    private static final String METHOD = "erp-goods.goods.sku.search";
    private static final String COLS =
            "goodsName,goodsNo,skuName,cateName,goodsField4,goodsField1,goodsField5,brandName,skuId";
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final JackyunProperties properties;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JackyunGoodsServiceImpl(
            JackyunProperties properties, HttpClient client, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public JackyunGoodsPage queryPage(JackyunGoodsQuery query) {
        if (!properties.isConfigured()) {
            throw unavailable("JACKYUN_CONFIG_MISSING", "请配置吉客云 AppKey 和 AppSecret。");
        }

        HttpResponse<String> response;
        try {
            response =
                    client.send(
                            buildRequest(query),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw unavailable("JACKYUN_REQUEST_INTERRUPTED", "吉客云查询已中断。");
        } catch (IOException ignored) {
            throw unavailable("JACKYUN_TRANSPORT_ERROR", "吉客云查询失败，请检查网络后重试。");
        }
        if (response.statusCode() != 200) {
            throw unavailable("JACKYUN_HTTP_ERROR", "吉客云查询返回 HTTP " + response.statusCode() + "。");
        }
        return parsePage(query, response.body());
    }

    private HttpRequest buildRequest(JackyunGoodsQuery query) throws IOException {
        var bizcontent =
                mapper.createObjectNode()
                        .put("brandName", query.brandName())
                        .put("cols", COLS)
                        .put("isIncludeBlockup", query.includeBlockup() ? 1 : 0)
                        .put("lastMaxId", Long.parseLong(query.lastMaxId()))
                        .put("pageIndex", 0)
                        .put("pageSize", query.pageSize());
        var parameters = new TreeMap<String, String>();
        parameters.put("appkey", properties.getAppKey());
        parameters.put("bizcontent", mapper.writeValueAsString(bizcontent));
        parameters.put("contenttype", "json");
        parameters.put("method", METHOD);
        parameters.put("timestamp", TIMESTAMP.format(clock.instant()));
        parameters.put("version", "v1.0");
        parameters.put("sign", JackyunRequestSigner.sign(parameters, properties.getAppSecret()));
        // 商家授权 token 仅在 ISV 调用时发送，且不参与签名。
        if (StringUtils.hasText(properties.getToken())) {
            parameters.put("token", properties.getToken());
        }

        var form = new StringJoiner("&");
        parameters.forEach(
                (key, value) ->
                        form.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return HttpRequest.newBuilder(GATEWAY)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private JackyunGoodsPage parsePage(JackyunGoodsQuery query, String body) {
        JsonNode envelope;
        try {
            envelope = mapper.readTree(body);
        } catch (IOException ignored) {
            throw invalidResponse("吉客云返回了无法解析的 JSON。");
        }
        if (envelope == null
                || !envelope.path("code").isIntegralNumber()
                || !envelope.path("code").canConvertToInt()) {
            throw invalidResponse("吉客云响应缺少有效业务返回码。");
        }
        int code = envelope.path("code").intValue();
        if (code != 200) {
            throw unavailable("JACKYUN_BUSINESS_ERROR", "吉客云拒绝查询，业务码 " + code + "。");
        }

        JsonNode data = envelope.path("result").path("data");
        Iterable<JsonNode> rows;
        if (data.isArray()) {
            rows = data;
        } else if (data.isObject()) {
            // 官方响应示例使用单个对象，真实分页查询返回数组。
            rows = List.of(data);
        } else if (data.isNull()) {
            rows = List.of();
        } else {
            throw invalidResponse("吉客云响应缺少货品数据。");
        }

        long checkpoint = Long.parseLong(query.lastMaxId());
        List<Goods> goods = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!query.brandName().equals(row.path("brandName").asText())) {
                throw invalidResponse("吉客云返回品牌与查询条件不一致。");
            }
            long skuId = readSkuId(row.path("skuId"));
            if (skuId <= checkpoint) {
                throw invalidResponse("吉客云返回的 SKU 续拉位置未递增。");
            }
            checkpoint = skuId;
            goods.add(
                    new Goods(
                            readSourceField(row, "goodsName"),
                            readSourceField(row, "goodsNo"),
                            readSourceField(row, "skuName"),
                            readSourceField(row, "cateName"),
                            readSourceField(row, "goodsField4"),
                            readSourceField(row, "goodsField1"),
                            readSourceField(row, "goodsField5")));
        }
        return new JackyunGoodsPage(query.brandName(), goods, Long.toString(checkpoint));
    }

    private static long readSkuId(JsonNode node) {
        String value = node.asText();
        if ((node.isIntegralNumber() || node.isTextual()) && value.matches("[0-9]{1,19}")) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                throw invalidResponse("吉客云返回的 SKU 续拉位置超出范围。");
            }
        }
        throw invalidResponse("吉客云返回的 SKU 续拉位置缺失或无效。");
    }

    private static String readSourceField(JsonNode row, String field) {
        JsonNode node = row.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalidResponse("吉客云货品字段 " + field + " 的类型无效。");
        }
        return node.textValue();
    }

    private static ApiException invalidResponse(String message) {
        return unavailable("JACKYUN_INVALID_RESPONSE", message);
    }

    private static ApiException unavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
