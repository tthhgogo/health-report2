-- 2026-09-03：第一期不支持 DOC/DOCX（设计方案 §3.2.1），页数全部精确（§3.4.1）；
-- 降级口径收敛为 SCHEMA_ITEM_DROPPED / DIET_TAG_DROPPED（§4.4）。仅修改列 COMMENT，不改类型与数据。
ALTER TABLE ct_health_report_file MODIFY COLUMN content_type VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '按内容判定的真实格式：PDF/JPG/PNG/OFD，不信任扩展名；DOC/DOCX识别即拒不落行';
ALTER TABLE ct_health_report_file MODIFY COLUMN precheck_pages INT NOT NULL COMMENT '创建任务容量预检页数：PDF与OFD为真实页数，图片恒为1，全部为精确值';
ALTER TABLE ct_health_report_task MODIFY COLUMN partial TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否发生预算内条目剔除：1是0否，具体影响由partial_reason说明';
ALTER TABLE ct_health_report_task MODIFY COLUMN partial_reason VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '降级原因：SCHEMA_ITEM_DROPPED普通条目被剔除/DIET_TAG_DROPPED饮食标签被剔除并抑制菜品推荐';
ALTER TABLE ct_dish_tag MODIFY COLUMN model_version VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜品离线打标模型版本，冗余存储仅供排障，不参与任何键与查询条件';
ALTER TABLE ct_dish_tag MODIFY COLUMN prompt_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜品离线打标提示词版本，冗余存储仅供排障';
