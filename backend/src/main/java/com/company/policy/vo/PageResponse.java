package com.company.policy.vo;

import java.util.List;

public record PageResponse<T>(List<T> items, long total) {}
