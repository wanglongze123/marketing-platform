package com.mp.benefit.repository;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 对账扫描（技术方案 §6.8）。V3 PR-10。
 *
 * <p><b>单独一个 mapper，不散进各业务 mapper</b>：对账是旁路读，它的查询按「差异种类」组织，与 业务查询按「实体」组织是两种切分。混在一起时，读业务 mapper
 * 的人要先分辨哪些方法是主链路在用、 哪些只有对账在用 —— 而两者的改动风险完全不同（业务查询改了会影响下单，对账查询改了只影响告警）。
 *
 * <p><b>每条扫描都带时间下界 {@code staleSeconds}</b>：对账查的是「长期未收敛」，不是「此刻不一致」。 少了它，一笔刚下单还没来得及履约的单会被当成差异 ——
 * 而正常业务里这种单持续存在，告警随即变成 噪音，资损哨兵失去意义。
 *
 * <p><b>一律不跨库 JOIN</b>（§3.1）：{@code db_benefit} 与 {@code db_reward} 的比对由对账任务分批拉取 {@code
 * batchQueryByOpNos} 在内存完成。四个分库账号使跨库 JOIN 在运行期直接 {@code access denied}， 这条约束在 V3 首次被真正用到。
 */
@Mapper
public interface ReconcileMapper {

    /**
     * 第 1 项：已收款未履约。
     *
     * <p><b>谓词是白名单，不是黑名单</b>（§6.8 的明确要求）。写成「排除 {@code GRANT_SUCCESS}」时，一笔 {@code GRANT_FAILED}
     * 且已全额退款的单会被当作待履约补发一次 —— 钱退了、货也给了。
     *
     * <p>三个条件各挡一类：
     *
     * <ul>
     *   <li>{@code grant_status IN (...)} 白名单 —— 只捞确实还没发完的三态，新增枚举时不会误放行
     *   <li>{@code refund_status = 'NONE'} —— 一旦进入退款流程（含回收中、退款中、已退款），该单不再是「待履约」
     *   <li>{@code update_time < ?} —— 刚支付的单不算差异
     * </ul>
     *
     * <p>走 {@code idx_pay_grant(pay_status, grant_status)}。
     */
    @Select(
            "SELECT play_biz_record_no FROM play_biz_record"
                    + " WHERE pay_status = 'PAY_SUCCESS'"
                    + " AND grant_status IN ('NOT_START', 'GRANTING', 'GRANT_UNKNOWN')"
                    + " AND refund_status = 'NONE'"
                    + " AND update_time < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)"
                    + " LIMIT #{limit}")
    List<String> scanPaidNotGranted(
            @Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * 第 2 项：已退款但权益未回收。
     *
     * <p><b>须限定 {@code grant_status = 'GRANT_SUCCESS'}</b>：{@code NOT_START} / {@code GRANT_FAILED}
     * 的单权益从未发出，回收留痕恒为空，而这类单是自动退款的主力 —— 不加限定会让告警全为假阳性， 资损哨兵随之失效。
     *
     * <p>判据取「有没有留痕」而非「回收调用成功没有」：PR-7/8 review 已把留痕改为按 {@code grantOpNo} 逐笔落，故这里逐条明细比对，一条没留痕即差异。
     */
    @Select(
            "SELECT DISTINCT r.play_biz_record_no FROM play_biz_record r"
                    + " JOIN benefit_fulfillment_record f"
                    + " ON f.play_biz_record_no = r.play_biz_record_no"
                    + " WHERE r.refund_status = 'REFUND_SUCCESS'"
                    + " AND r.grant_status = 'GRANT_SUCCESS'"
                    + " AND f.grant_status = 'SUCCESS' AND f.revoke_no IS NULL"
                    + " AND r.update_time < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)"
                    + " LIMIT #{limit}")
    List<String> scanRefundedNotRevoked(
            @Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * 第 4 项：操作记录长期停在非终态。
     *
     * <p><b>扫描范围覆盖全部非终态，而非只扫 {@code UNKNOWN}</b>（§6.8 的明确要求）：{@code PROCESSING} 与 {@code INIT}
     * 同样可能因宕机悬挂。这是「收敛率 100%」的关键 —— 任务负责主动收敛，对账负责 发现「连任务都没有」的悬挂记录。
     *
     * <p>走 {@code idx_status_recover(status, update_time)}。
     */
    @Select(
            "SELECT play_biz_record_no FROM play_op_record"
                    + " WHERE status IN ('INIT', 'PROCESSING', 'UNKNOWN')"
                    + " AND update_time < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)"
                    + " LIMIT #{limit}")
    List<String> scanUnresolvedOps(
            @Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * 第 5 项：金额一致性。
     *
     * <p><b>只告警，禁止自动改单</b>（§6.8）：金额差异要么是支付方与平台的口径不一致，要么是有人改过 数据，两者都不该由对账猜一个值写回去。
     *
     * <p>{@code pay_amount IS NULL} 不算差异：它是 {@code applyPaidAfterClosing} 有意留下的「实付未知」
     * 状态，由支付通知回填；把它算成差异会让每一笔走关单收敛的单都报一次。<b>退款侧对它的处置是拒绝 退款（{@code 1754}），不是当成金额错误</b>。
     *
     * <p><b>时间下界与其余项一致</b>（PR-10 后置 review 补）：本项曾是十五项里唯一不带下界的扫描，于是 每轮都要扫全部已支付单，而其余项只看「长期未收敛」的那批。
     *
     * <p>下界在这里不只是省开销，它同样是判据的一部分：{@code pay_amount} 由支付通知回填，而一笔刚 走完关单收敛的单在通知到达前 {@code pay_amount} 与
     * {@code order_amount} 本就可能不等 —— 没有下界时 这个正常的中间态每轮都会被报成金额差异。<b>而假告警会让资损哨兵失效</b>，第 5 项正是哨兵之一。
     *
     * <p>补下界之后本条走 {@code idx_pay_update(pay_status, update_time)}；{@code pay_amount <>
     * order_amount} 是两列比较，任何索引都用不上，只能作为回表后的过滤。
     */
    @Select(
            "SELECT play_biz_record_no FROM play_biz_record"
                    + " WHERE pay_status = 'PAY_SUCCESS'"
                    + " AND pay_amount IS NOT NULL AND pay_amount <> order_amount"
                    + " AND update_time < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)"
                    + " LIMIT #{limit}")
    List<String> scanAmountMismatch(
            @Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * 第 6 项 a：超卖检出。{@code locked + consumed > total} 即为已发生的超卖。
     *
     * <p>它是 {@code stock_oversold_total} 这个资损哨兵的数据来源 —— <b>由对账产出，不是请求路径埋点</b>。
     * 请求路径上的计数只能证明「我以为没超卖」，而这条 SQL 直接问库里的数。
     */
    @Select("SELECT COUNT(*) FROM marketing_stock WHERE locked + consumed > total")
    int countOversoldRows();

    /**
     * 第 6 项 b：库存消耗<b>份数</b>与成交单据的份数比对。
     *
     * <p>口径按 §3.4 的表：{@code consumed} 对应「已支付且未退款成功」的单。退款要回补库存，故已 {@code REFUND_SUCCESS} 的单不计入。
     *
     * <p><b>取 {@code SUM(quantity)} 而非 {@code COUNT(*)}</b>（PR-10 review 补）：{@code consumed} 按份数累加
     * （{@code consumed = consumed + qty}），与单数不是同一个量纲。一笔 {@code quantity=3} 的单会让 {@code consumed=3}
     * 而单数为 1 —— 每轮对账报一次假差异。
     *
     * <p><b>它此前不报错，只因为 {@code doCreateTrade} 有一道 {@code quantity=1} 的守卫</b>；而 {@code quantity} 在
     * DDL、DTO、库存 SQL 里全都按份数设计：守卫一放开（多份购买是常规需求），这一项立刻开始刷 假告警 —— 而<b>假告警会让资损哨兵失效</b>，那正是 §6.8 要避免的。
     *
     * <p>{@code SUM} 在无匹配行时返回 {@code NULL}，故用 {@code COALESCE} 归零。
     *
     * <p><b>不自动改数，只告警</b>：库存差异可由「补建释放任务」自愈（任务自带幂等闸），而直接改 {@code consumed} 会把一次错误固化成新的基线。
     */
    @Select(
            "SELECT COALESCE(SUM(quantity), 0) FROM play_biz_record"
                    + " WHERE sku_id = #{skuId} AND pay_status = 'PAY_SUCCESS'"
                    + " AND refund_status <> 'REFUND_SUCCESS'")
    int sumConsumedQuantity(@Param("skuId") String skuId);

    /**
     * 第 9 项：已关闭的单仍占着库存。
     *
     * <p>判据是主单的 {@code stock_status} 仍为 {@code LOCKED} —— 它是库存处置的每单幂等承重点，未处置 即仍在占用。<b>不看 {@code
     * marketing_stock} 的数</b>：那是所有订单共享的计数器，看不出「哪一单没释放」。
     */
    @Select(
            "SELECT play_biz_record_no FROM play_biz_record"
                    + " WHERE pay_status = 'CLOSED' AND stock_status = 'LOCKED'"
                    + " AND update_time < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)"
                    + " LIMIT #{limit}")
    List<String> scanClosedStillHoldingStock(
            @Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * 第 14 项：关单中间态长期未收敛。
     *
     * <p>{@code CLOSING} 是 V2 引入的中间态，§6.4 的通用约束要求「任何中间态都必须同时具备准入谓词的 入边与对账的一行」—— 本项即那一行。缺了它，一笔关单 RPC
     * 超时后又没被查单收敛的单会永久停在 {@code CLOSING}，而前十三项对账无一覆盖（第 1 项扫 {@code PAY_SUCCESS}、第 9 项扫 {@code
     * CLOSED}）。
     */
    @Select(
            "SELECT play_biz_record_no FROM play_biz_record"
                    + " WHERE pay_status = 'CLOSING'"
                    + " AND update_time < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)"
                    + " LIMIT #{limit}")
    List<String> scanStuckClosing(
            @Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * 第 15 项：限购额度与单据数比对。
     *
     * <p>与第 6 项是同一类兜底的两个计数器：那项管库存，本项管额度。<b>凡是有一个共享计数器、又要按单 增减的地方，就要有一条对账项</b> —— V2
     * 之前只有前者，理由曾是「额度由库存那道闸一并保证」，而该 假设不成立：两者「是否占用过」并不同步（不限购的 SKU 不扣额度）。
     *
     * <p><b>同样取 {@code SUM(quantity)}</b>（PR-10 review 补）：{@code used_qty} 与 {@code consumed} 一样按份数
     * 累加，与单数不同量纲。两处是同一个错，故一并修 —— 它们本就是「同一类兜底的两个计数器」。
     *
     * <p><b>差异一律不自动改 {@code used_qty}</b>：它与库存不同 —— 库存差异可由补建释放任务自愈，而额度
     * 的正确值取决于历史上哪些单占用过，直接改数会把一次错误固化成新基线。
     */
    @Select(
            "SELECT COALESCE(SUM(quantity), 0) FROM play_biz_record"
                    + " WHERE user_id = #{userId} AND activity_id = #{activityId}"
                    + " AND sku_id = #{skuId} AND quota_status = 'LOCKED'")
    int sumQuotaHoldingQuantity(
            @Param("userId") String userId,
            @Param("activityId") String activityId,
            @Param("skuId") String skuId);

    /**
     * 第 3 项：<b>已发放成功的单</b>，供逐 {@code opNo} 与 reward 侧比对（PR-10 review 补）。
     *
     * <p><b>首版复用了 {@code scanPaidNotGranted}，那是错的</b>：那条扫的是 {@code grant_status IN
     * ('NOT_START','GRANTING','GRANT_UNKNOWN')} —— <b>还没发完</b>的单。而本项要找的是「平台记着
     * 已发成功、下游却查无」，两个集合几乎不相交，于是这一项近乎空转：它只在单子还没发完时去比对下游， 而那种单本来就没有发奖记录，查无是正常的、不是差异。
     *
     * <p>实测确认：造一笔正常发放成功的单，删掉 {@code reward_grant_record} 与 {@code reward_grant_item}， 跑对账，本项检出 0 条。
     *
     * <p>故谓词取 {@code grant_status = 'GRANT_SUCCESS'} —— 只有平台声称「已发成功」的单，下游查无才 构成差异。
     */
    @Select(
            "SELECT play_biz_record_no FROM play_biz_record"
                    + " WHERE pay_status = 'PAY_SUCCESS' AND grant_status = 'GRANT_SUCCESS'"
                    + " AND update_time < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)"
                    + " LIMIT #{limit}")
    List<String> scanGrantedOrders(
            @Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * 某单已发放成功的明细对应的发奖幂等号。第 3 项据它逐笔比对下游。
     *
     * <p><b>限定 {@code grant_status = 'SUCCESS'}</b>：失败或未定的明细在下游查无是正常的，不构成差异。 与 {@link
     * #selectGrantOpNos} 的差别正在这道谓词 —— 那个取全部，供人工处置与证据导出使用。
     */
    @Select(
            "SELECT DISTINCT grant_op_no FROM benefit_fulfillment_record"
                    + " WHERE play_biz_record_no = #{bizNo} AND grant_status = 'SUCCESS'"
                    + " AND grant_op_no IS NOT NULL")
    List<String> selectSucceededGrantOpNos(@Param("bizNo") String bizNo);

    /** 第 11 项与人工处置要比对的发奖幂等号：本单已发起过的全部发放调用，不限状态。 */
    @Select(
            "SELECT DISTINCT grant_op_no FROM benefit_fulfillment_record"
                    + " WHERE play_biz_record_no = #{bizNo} AND grant_op_no IS NOT NULL")
    List<String> selectGrantOpNos(@Param("bizNo") String bizNo);

    /** 已支付单的全部 SKU，供第 6 项逐 SKU 比对。 */
    @Select("SELECT DISTINCT sku_id FROM play_biz_record WHERE pay_status = 'PAY_SUCCESS'")
    List<String> selectPaidSkuIds();

    /**
     * 这批单号里哪些本地存在。第 8 项按支付方对账文件逐批反查。
     *
     * <p><b>批量而非逐笔查</b>：对账文件是支付方一天的全部收款流水，逐笔一次查询会让第 8 项自己成为 最慢的一项 —— 而对账一轮跑不完，第 5/6/11 项的哨兵指标就停止更新。
     *
     * <p>走 {@code uk_biz_no}。返回存在的那些，调用方做差集得出「支付方有而本地无」的。<b>反过来写 （查不存在的）做不到</b>：本地没有的行查不出来，SQL
     * 只能告诉你表里有什么。
     */
    @Select({
        "<script>",
        "SELECT play_biz_record_no FROM play_biz_record WHERE play_biz_record_no IN",
        "<foreach item='no' collection='bizNos' open='(' separator=',' close=')'>#{no}</foreach>",
        "</script>"
    })
    List<String> selectExistingBizNos(@Param("bizNos") List<String> bizNos);

    /** 主单快照，对账按当前状态决定补哪一种任务（BR-C-23 先查证再动）。 */
    @Select(
            "SELECT play_biz_record_no, pay_status, grant_status, refund_status, sku_id"
                    + " FROM play_biz_record WHERE play_biz_record_no = #{bizNo}")
    OrderSnapshot selectSnapshot(@Param("bizNo") String bizNo);

    /** 该单的回收操作单号。第 2 项补建 {@code REVOKE} 任务时复用它，不新造键。 */
    @Select(
            "SELECT op_no FROM play_op_record"
                    + " WHERE play_biz_record_no = #{bizNo} AND op_type = 'REVOKE_BENEFIT'"
                    + " LIMIT 1")
    String selectRevokeOpNo(@Param("bizNo") String bizNo);

    /** 某库存键的已消耗数，第 6 项比对用。 */
    @Select("SELECT consumed FROM marketing_stock WHERE stock_key = #{stockKey}")
    Integer selectConsumed(@Param("stockKey") String stockKey);

    /** 全部限购额度行，第 15 项逐行比对。 */
    @Select(
            "SELECT user_id, activity_id, sku_id, used_qty FROM user_purchase_quota"
                    + " WHERE used_qty > 0 LIMIT #{limit}")
    List<QuotaRow> selectQuotaRows(@Param("limit") int limit);

    /**
     * 第 11 项：重复发奖检出。
     *
     * <p>判据是「同一 {@code (bizNo, benefitItemId)} 有多于一条成功明细」。{@code uk_biz_item} 本该挡住它， 故检出非 0
     * 即意味着唯一索引被绕过或数据被人改过 —— 这正是哨兵要盯的那一类。
     *
     * <p>用 {@code HAVING} 而非在应用层聚合：几十万行的表拉到内存里数一遍，对账一轮就跑不完了。
     */
    @Select(
            "SELECT COUNT(*) FROM (SELECT play_biz_record_no, benefit_item_id"
                    + " FROM benefit_fulfillment_record WHERE grant_status = 'SUCCESS'"
                    + " GROUP BY play_biz_record_no, benefit_item_id HAVING COUNT(*) > 1) t")
    int countDuplicateGrantedItems();

    /** 主单的对账视图。只取对账要用的列 —— 全字段映射会把快照 JSON 一并拉出来。 */
    class OrderSnapshot {
        private String playBizRecordNo;
        private String payStatus;
        private String grantStatus;
        private String refundStatus;
        private String skuId;

        public String getPlayBizRecordNo() {
            return playBizRecordNo;
        }

        public void setPlayBizRecordNo(String playBizRecordNo) {
            this.playBizRecordNo = playBizRecordNo;
        }

        public String getPayStatus() {
            return payStatus;
        }

        public void setPayStatus(String payStatus) {
            this.payStatus = payStatus;
        }

        public String getGrantStatus() {
            return grantStatus;
        }

        public void setGrantStatus(String grantStatus) {
            this.grantStatus = grantStatus;
        }

        public String getRefundStatus() {
            return refundStatus;
        }

        public void setRefundStatus(String refundStatus) {
            this.refundStatus = refundStatus;
        }

        public String getSkuId() {
            return skuId;
        }

        public void setSkuId(String skuId) {
            this.skuId = skuId;
        }
    }

    /** 限购额度行的对账视图。 */
    class QuotaRow {
        private String userId;
        private String activityId;
        private String skuId;
        private Integer usedQty;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getActivityId() {
            return activityId;
        }

        public void setActivityId(String activityId) {
            this.activityId = activityId;
        }

        public String getSkuId() {
            return skuId;
        }

        public void setSkuId(String skuId) {
            this.skuId = skuId;
        }

        public Integer getUsedQty() {
            return usedQty;
        }

        public void setUsedQty(Integer usedQty) {
            this.usedQty = usedQty;
        }
    }
}
