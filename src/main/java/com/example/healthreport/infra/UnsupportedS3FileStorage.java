package com.example.healthreport.infra;

import org.springframework.stereotype.Component;

/**
 * 对象存储尚未接入时的显式失败占位 Bean。
 * <p>接入真实实现时删除本占位 Bean；当前绝不返回假数据。</p>
 */
@Component
class UnsupportedS3FileStorage implements S3FileStorage {
    // 所有方法沿用接口中的 TODO 显式失败实现。
}
