package com.company.policy.dto;

import java.util.List;

public record UserPlatformsUpdate(
        List<ChannelPlatform> platforms, List<ChannelPlatform> expectedPlatforms) {}
