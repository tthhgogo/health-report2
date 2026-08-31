-- 2026-08-28：任务、文件和菜品标签增加企业隔离；菜品列与食堂接口统一为 dishes_id。
-- 执行前必须为历史 task/file/tag 行回填真实 company_id；禁止用统一假企业绕过租户归属。
ALTER TABLE ct_health_report_task
  ADD COLUMN company_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '归属企业ID，创建任务时从可信认证上下文固化，模块四据此选择企业菜品集合' AFTER task_id;
CREATE INDEX idx_company_user ON ct_health_report_task (company_id, user_id);

ALTER TABLE ct_health_report_file
  ADD COLUMN company_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '归属企业ID，上传时从可信认证上下文固化，绑定任务时必须与任务企业精确一致' AFTER file_id;
CREATE INDEX idx_company_user ON ct_health_report_file (company_id, user_id);

ALTER TABLE ct_dish_tag
  ADD COLUMN company_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '菜品所属企业ID，离线打标、查询与Redis发布的租户隔离键' FIRST;
ALTER TABLE ct_dish_tag
  CHANGE COLUMN dish_id dishes_id BIGINT NOT NULL COMMENT '食堂菜品ID，在同一企业内唯一，与company_id共同确定菜品';

-- 回填完成并逐行核验企业归属后，再在维护窗口执行以下收口语句。
ALTER TABLE ct_health_report_task MODIFY COLUMN company_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '归属企业ID，创建任务时从可信认证上下文固化，模块四据此选择企业菜品集合';
ALTER TABLE ct_health_report_file MODIFY COLUMN company_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '归属企业ID，上传时从可信认证上下文固化，绑定任务时必须与任务企业精确一致';
ALTER TABLE ct_dish_tag MODIFY COLUMN company_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜品所属企业ID，离线打标、查询与Redis发布的租户隔离键';

ALTER TABLE ct_dish_tag
  DROP INDEX uk_dish_hash_enum;
ALTER TABLE ct_dish_tag
  DROP INDEX idx_online;
CREATE UNIQUE INDEX uk_company_dish_hash_enum
  ON ct_dish_tag (company_id, dishes_id, tag_hash, enum_key);
CREATE INDEX idx_build
  ON ct_dish_tag (company_id, enum_key, dishes_id, tag_hash);
