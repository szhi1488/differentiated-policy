package com.company.policy.service.impl;

import com.company.policy.support.policy.PolicyExecutionChange;
import com.company.policy.vo.ApplicantCatalogResponse.Configuration;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Selection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** 将冻结的逐商品审核依据合并为卡上的整组配置，保留每段的实际采用名单与补贴。 */
final class PolicyCardConfigurationGroups {
    record Group(
            PolicyExecutionChange change,
            boolean wholeConfiguration,
            LocalDateTime from,
            LocalDateTime through) {
        Group(PolicyExecutionChange change, boolean wholeConfiguration) {
            this(change, wholeConfiguration, change.from(), change.through());
        }
    }

    private record Coverage(Configuration configuration, int total, Set<String> goods) {}

    private record Round(String policyId, int submissionVersion) {
        Round(Policy policy) {
            this(policy.policyId(), policy.submissionVersion());
        }
    }

    // planKey 仅在原商品卡内关联方案，两个商品的补贴条件按业务值比较。
    private record Terms(
            String cardType,
            String mode,
            BigDecimal fixed,
            BigDecimal cap,
            BigDecimal ratio,
            String base,
            Integer quantity) {
        static Terms of(Selection row) {
            var plan = row.subsidy();
            if (plan == null) return null;
            return new Terms(
                    row.cardType(),
                    plan.subsidyMode(),
                    decimal(plan.fixedAmount()),
                    decimal(plan.maxSubsidyAmount()),
                    decimal(plan.ratio()),
                    plan.priceBase(),
                    row.quantity());
        }

        private static BigDecimal decimal(String value) {
            return value == null ? null : new BigDecimal(value).stripTrailingZeros();
        }
    }

    private record Adopted(Round round, Terms terms) {
        Adopted(Policy policy) {
            this(new Round(policy), Terms.of(policy.selections().get(0)));
        }
    }

    private record RetailGroup(
            String platform,
            Configuration configuration,
            int total,
            List<Adopted> after,
            List<Round> before,
            List<Round> candidates) {
        RetailGroup(PolicyExecutionChange change, Coverage coverage) {
            this(
                    change.scope().platformCode(),
                    coverage.configuration(),
                    coverage.total(),
                    change.after().stream().map(Adopted::new).toList(),
                    change.before().stream().map(Round::new).toList(),
                    change.candidates().stream().map(Round::new).toList());
        }
    }

    private PolicyCardConfigurationGroups() {}

    static List<Group> group(List<PolicyExecutionChange> segments) {
        var result = new ArrayList<Group>();
        var retail = new LinkedHashMap<RetailGroup, List<PolicyExecutionChange>>();
        for (var change : segments) {
            var coverage = coverage(change);
            if (coverage == null) {
                result.add(new Group(change, false));
            } else if (coverage.goods().size() == coverage.total()) {
                // 百亿的采用单在当前配置范围内携带完整商品列表。单商品整组也能直接核实。
                result.add(new Group(change, true));
            } else if (change.scope().goodsId() != null) {
                retail.computeIfAbsent(
                                new RetailGroup(change, coverage), ignored -> new ArrayList<>())
                        .add(change);
            } else {
                result.add(new Group(change, false));
            }
        }
        retail.forEach((key, members) -> addRetail(result, members, key.total()));
        result.sort(
                Comparator.comparing(Group::from)
                        .thenComparing(Group::wholeConfiguration, Comparator.reverseOrder()));
        return result;
    }

    // 至少一张普通卡须有冻结的整组件数，各单在本段实际覆盖的商品也须逐项相同。
    private static Coverage coverage(PolicyExecutionChange change) {
        Integer total = null;
        Configuration configuration = null;
        Set<String> goods = null;
        for (var policy : change.after()) {
            var selected = new HashSet<String>();
            Terms first = null;
            for (var row : policy.selections()) {
                var terms = Terms.of(row);
                if (terms == null
                        || row.products().isEmpty()
                        || (first != null && !first.equals(terms))) return null;
                first = terms;
                var count = row.wholeConfigurationGoodsCount();
                if ("COMMON".equals(row.cardType()) && count != null) {
                    if (count < 1 || (total != null && !total.equals(count))) return null;
                    total = count;
                }
                for (var product : row.products()) {
                    var current = new Configuration(product.spuId(), product.ram(), product.rom());
                    if (product.goodsId() == null
                            || product.spuId() == null
                            || product.ram() == null
                            || product.rom() == null
                            || (configuration != null && !configuration.equals(current))
                            || !selected.add(product.goodsId())) return null;
                    configuration = current;
                }
            }
            if (selected.isEmpty() || (goods != null && !goods.equals(selected))) return null;
            goods = selected;
        }
        if (total == null || goods.size() > total) return null;
        var scope = change.scope();
        if (scope.goodsId() != null) {
            if (!goods.equals(Set.of(scope.goodsId().toString()))) return null;
        } else if (scope.spuId() == null
                || !configuration.equals(
                        new Configuration(scope.spuId().toString(), scope.ram(), scope.rom())))
            return null;
        return new Coverage(configuration, total, goods);
    }

    private static void addRetail(
            List<Group> result, List<PolicyExecutionChange> members, int total) {
        // 同商品同条件在冻结依据里是互不重叠的闭区间；件数按商品 ID 计算。
        if (members.stream().map(c -> c.scope().goodsId()).distinct().count() != total) {
            members.forEach(c -> result.add(new Group(c, false)));
            return;
        }
        var boundaries = new TreeMap<LocalDateTime, Integer>();
        for (var member : members) {
            boundaries.merge(member.from(), 1, Integer::sum);
            boundaries.merge(member.through().plusSeconds(1), -1, Integer::sum);
        }
        var whole = new TreeMap<LocalDateTime, LocalDateTime>();
        LocalDateTime start = null;
        int active = 0;
        for (var boundary : boundaries.entrySet()) {
            active += boundary.getValue();
            if (active == total && start == null) {
                start = boundary.getKey();
            } else if (active != total && start != null) {
                var end = boundary.getKey().minusSeconds(1);
                whole.put(start, end);
                result.add(new Group(members.get(0), true, start, end));
                start = null;
            }
        }
        // 完整覆盖以外的前、后或中间时段继续逐商品展示，不跨过一秒的差异。
        for (var member : members) {
            var cursor = member.from();
            var covering = whole.floorEntry(cursor);
            if (covering != null && !covering.getValue().isBefore(cursor)) {
                cursor = covering.getValue().plusSeconds(1);
            }
            for (var interval = whole.ceilingEntry(cursor);
                    interval != null && !interval.getKey().isAfter(member.through());
                    interval = whole.higherEntry(interval.getKey())) {
                if (cursor.isBefore(interval.getKey())) {
                    result.add(new Group(member, false, cursor, interval.getKey().minusSeconds(1)));
                }
                cursor = interval.getValue().plusSeconds(1);
            }
            if (!cursor.isAfter(member.through())) {
                result.add(new Group(member, false, cursor, member.through()));
            }
        }
    }
}
