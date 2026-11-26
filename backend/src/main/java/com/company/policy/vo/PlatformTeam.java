package com.company.policy.vo;

import java.util.List;

public record PlatformTeam(
        String perspective, String platformCode, int version, List<Member> members) {
    public record Member(String userId, String name, String memberKind, boolean canSubmit) {}

    public record Candidate(String id, String name, String department, boolean canSubmit) {}
}
