-- 2026-09-04：消除 origin_name 敏感元数据例外（开发方案 §3.2 待决项落定）。
-- 原始文件名（常含姓名与体检属性）不再落任何存储；改存服务端安全生成的展示名。
-- 三步顺序不可调换：先改名（保持 255 宽度，避免存量超 64 的原名导致 CHANGE 失败），
-- 再覆写存量值（不等 TTL，立即清除历史敏感面），最后收窄列宽。

ALTER TABLE ct_health_report_file
  CHANGE COLUMN origin_name display_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '安全生成的展示名：体检报告-{fileId前8位}.{ext}，ext由内容判定的真实格式映射；不含任何用户输入，原始文件名从不落任何存储，2026-09-04起消除敏感元数据例外';

-- 幂等：已是生成名的行再执行一次结果不变。
UPDATE ct_health_report_file
   SET display_name = CONCAT('体检报告-', SUBSTRING(file_id, 1, 8), '.', LOWER(content_type));

ALTER TABLE ct_health_report_file
  MODIFY COLUMN display_name VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '安全生成的展示名：体检报告-{fileId前8位}.{ext}，ext由内容判定的真实格式映射；不含任何用户输入，原始文件名从不落任何存储，2026-09-04起消除敏感元数据例外';
