package com.jixiejia.agent.api.dto;

/**
 * 飞轮审核请求。
 *
 * @param answer 审核通过时必填：该问题的标准答案，会连同问题一起补进知识库
 * @param note   审核意见
 */
public record FlywheelReviewRequest(String answer, String note) {
}
