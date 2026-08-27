package com.example.healthreport.infra;

/**
 * S3 私有对象存储边界。
 * <p>任务 01 只保留显式失败的占位实现，后续接入前绝不返回假数据。</p>
 */
public interface S3FileStorage {

    /** TODO 接入对象存储写入。 */
    default void write(String objectKey, byte[] contentBytes) {
        throw new UnsupportedOperationException("S3FileStorage尚未实现");
    }

    /** TODO 接入对象存储读取。 */
    default byte[] read(String objectKey) {
        throw new UnsupportedOperationException("S3FileStorage尚未实现");
    }

    /** TODO 接入对象存储删除。 */
    default void delete(String objectKey) {
        throw new UnsupportedOperationException("S3FileStorage尚未实现");
    }
}
