-- 2026-09-05：恢复 DOCX 支持（设计方案 §3.2.1 裁决翻案，纯 Java docx4j 转图路线评估通过）。
-- 无结构变更，仅更新 content_type 的取值说明注释；DOCX 行随新代码自然写入。

ALTER TABLE ct_health_report_file
  MODIFY COLUMN content_type VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '按内容判定的真实格式：PDF/JPG/PNG/OFD/DOCX，不信任扩展名；旧版DOC识别即拒不落行（DOCX于2026-09-05恢复支持）';
