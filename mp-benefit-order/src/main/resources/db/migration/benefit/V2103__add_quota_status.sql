-- 主单记录本单限购额度处置到哪一步（V2 PR-8）。
--
-- 为什么不能复用 stock_status：库存与额度虽由同一条 STOCK_RELEASE 任务承接，但**是否占用过**
-- 这件事两者并不同步 —— 库存对每一单都预占，额度只在 SKU 配了限购（purchase_limit_qty > 0）
-- 时才扣。不限购的单 stock_status='LOCKED' 而额度从未占用，释放时若无条件调 tryRelease，
-- used_qty 就会被减掉一份不属于它的。
--
-- 该错误只在**限额中途变过**时才显形：下单时不限购（无额度行），运营随后调高限额，同用户另一单
-- 建行并占用 1，此时前一单释放 —— used_qty 从 1 减到 0，后一单的额度被前一单还掉了。
-- 与 stock_status 修掉的那个缺陷同构：共享计数器上的下界（used_qty >= qty）拦不住跨单误还，
-- 因为别的单占着时它照常大于 0。
--
-- 迁移语义：存量单一律置 NONE。V2103 之前的单都在「限额恒定」的假设下跑，其中限购单的额度
-- 已扣但无从区分；置 NONE 意味着它们日后关单不再返还额度 —— 宁可少还（用户少一次购买机会），
-- 不可多还（额度被刷穿，限购形同虚设）。存量数据量为零（V2 未上线），此处只为口径完整。
ALTER TABLE play_biz_record
  ADD COLUMN quota_status VARCHAR(16) NOT NULL DEFAULT 'NONE'
  COMMENT '本单限购额度处置态 NONE/LOCKED/RELEASED，额度返还的每单幂等承重点' AFTER stock_status;
