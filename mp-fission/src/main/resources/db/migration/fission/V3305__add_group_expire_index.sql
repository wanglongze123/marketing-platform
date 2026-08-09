-- 轮次过期治理的扫描索引（V3 PR-5）。
--
-- 与 fission_relation.idx_expire 同构，列序也相同：status 等值在前，expire_time 范围
-- 其次，id 垫底供分片区间使用。
--
-- 为何 V3301 建表时没有它：技术方案 §3.3 只为 fission_relation 设计了过期治理（FR-F09
-- 的字面范围就是关系），轮次治理是 PR-2 实测唯一键缺陷时连带确定的一条语义 ——
-- 「已过期但未被治理的轮次仍占着 active_flag，必须先终结才能开下一轮」（《分阶段方案》
-- §6.6）。那一条当时只写进了文档，扫描它所需的索引到 PR-5 落地治理时才补。
--
-- 没有这个索引，轮次治理的批量语句只能退回 idx_activity_sponsor —— 该索引首列是
-- activity_id，而治理语句不带活动维度，等于全表扫描。fission_group 的行数远小于
-- fission_relation，全表扫描一时不会显形，正因如此更要在此刻建好：等到它显形时，
-- 加索引是在一张持续写入的表上做 DDL。
ALTER TABLE fission_group
  ADD KEY idx_expire (status, expire_time, id) COMMENT '轮次过期治理分片扫描（V3 PR-5）';
