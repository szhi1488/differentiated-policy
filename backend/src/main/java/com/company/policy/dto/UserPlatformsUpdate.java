package com.company.policy.dto;

import java.util.List;

public record UserPlatformsUpdate(Integer expectedVersion, List<Member> members) {
    public record Member(String userId, String memberKind) {}
}
