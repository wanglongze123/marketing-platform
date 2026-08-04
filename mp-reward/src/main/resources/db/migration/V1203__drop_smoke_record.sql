-- V0 的冒烟脚手架，V1 主链路建成后删除
-- 不修改 V0201 历史脚本（会导致 Flyway checksum 校验失败），另写此脚本
DROP TABLE IF EXISTS smoke_record;
