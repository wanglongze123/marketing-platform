package com.mp.benefit.reconcile;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mp.api.benefit.dto.ManualRepairReq;
import com.mp.api.benefit.dto.ManualRepairResp;
import com.mp.api.benefit.dto.RepairAction;
import com.mp.benefit.entity.PlayBizRecord;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.benefit.repository.PlayBizRecordMapper;
import com.mp.benefit.repository.PlayOpRecordMapper;
import com.mp.benefit.repository.ReconcileMapper;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.OpStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.exception.BizException;
import com.mp.common.util.BizNoGenerator;
import com.mp.common.util.ReqFields;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 人工处置（FR-C07、BR-C-27）。V3 PR-10。
 *
 * <p><b>整个类只有一条核心约束：前五类动作一律复用原幂等键，不新造。</b>
 *
 * <p>新造键即绕开 {@code uk_biz_op} 与下游的 {@code opNo} 幂等，等于给人工处置开了一个可以重复发奖的 后门 ——
 * 而<b>人工处置是最容易被重复点击的入口</b>（客服连点）。这条铁律在此的具体形态：
 *
 * <ul>
 *   <li>重试发奖 → 原 {@code grantOpNo}，从履约明细读，不重新派生
 *   <li>重试回收 → 原 {@code revokeNo}，从操作记录读
 *   <li>重试退款 → 原 {@code refundNo}，从主单读；且<b>只补查单任务不补重发</b>
 * </ul>
 *
 * <p><b>重试退款只查不发</b>：与 {@code QUERY_REFUND} 的设计一致 —— 多发一笔奖可回收，多退一笔钱要走
 * 人工追讨，两者的失效代价不对称。人工点「重试退款」时想要的是「把它推到终态」，而查单能做到这件事 且无副作用。
 *
 * <p><b>审计与动作写同一张表</b>：{@code operator} / {@code reason} 落进 {@code play_op_record}，不另开审计表 ——
 * 分表则要在两处各写一次，而那两次之间的窗口正是「动作执行了而审计没落」。
 *
 * <p><b>但两者刻意不在同一个事务里</b>，理由见 {@link #repair}：审计先落且独立提交，动作失败时它要留下来。 包成一个事务会让 {@code rollbackFor =
 * Exception.class} 把审计一并回滚，那次处置就此不留痕迹 —— 与本类要达成的相反。两个写各自幂等（审计撞 {@code uk_biz_op}、补建任务撞 {@code
 * uk_biz_type_op}）， 没有需要原子性的跨写不变量。
 */
@Service
public class ManualRepairService {

    private static final Logger log = LoggerFactory.getLogger(ManualRepairService.class);

    private final PlayBizRecordMapper bizRecordMapper;
    private final PlayOpRecordMapper opRecordMapper;
    private final BenefitTaskMapper taskMapper;
    private final ReconcileMapper reconcileMapper;
    private final ReconcileMetrics metrics;

    public ManualRepairService(
            PlayBizRecordMapper bizRecordMapper,
            PlayOpRecordMapper opRecordMapper,
            BenefitTaskMapper taskMapper,
            ReconcileMapper reconcileMapper,
            ReconcileMetrics metrics) {
        this.bizRecordMapper = bizRecordMapper;
        this.opRecordMapper = opRecordMapper;
        this.taskMapper = taskMapper;
        this.reconcileMapper = reconcileMapper;
        this.metrics = metrics;
    }

    /**
     * 执行一次人工处置。
     *
     * <p>顺序：校验必填 → 定位主单 → 落审计 → 按动作分派。<b>审计先于动作</b>：动作执行到一半崩溃时， 审计记录仍在，人能看到「有人点过这个按钮」——
     * 反过来则那次处置完全没有痕迹。
     */
    public ManualRepairResp repair(ManualRepairReq req) {
        ReqFields.required(req.getBizNo(), "bizNo");
        // operator 与 reason 是 BR-C-27 的硬要求，不是可选的审计装饰。
        // 缺了它们，人工处置与自动链路在库里无从区分，对账也算不出真实的自动收敛率
        ReqFields.required(req.getOperator(), "operator");
        ReqFields.required(req.getReason(), "reason");
        ReqFields.required(req.getTicketNo(), "ticketNo");
        if (req.getAction() == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "必填参数缺失: action");
        }

        String bizNo = req.getBizNo();
        PlayBizRecord order = findOrder(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }

        writeAudit(order, req);
        metrics.onManualRepair(req.getAction().name());

        return switch (req.getAction()) {
            case REQUERY_PAY -> requeryPay(order);
            case REQUERY_GRANT, RETRY_GRANT -> retryGrant(order, req.getAction());
            case RETRY_REVOKE -> retryRevoke(order);
            case RETRY_REFUND -> retryRefund(order);
            case MARK_DONE -> markDone(order, req);
            case EXPORT_EVIDENCE -> exportEvidence(order);
        };
    }

    /**
     * 落审计记录。
     *
     * <p>{@code op_seq} 取工单号 —— 一单可被处置多次，各留一条痕。{@code idempotent_key} 取 {@code bizNo + 动作 +
     * 工单号}：同一工单重复提交命中唯一键，{@code retry_count} 自增而不新增行。
     */
    private void writeAudit(PlayBizRecord order, ManualRepairReq req) {
        String bizNo = order.getPlayBizRecordNo();
        String opSeq = req.getAction().name() + "_" + req.getTicketNo();
        opRecordMapper.upsertManualRepair(
                bizNo + "_MR_" + opSeq,
                bizNo + "_MR_" + opSeq,
                bizNo,
                order.getUserId(),
                order.getActivityId(),
                opSeq,
                OpStatus.SUCCESS.name(),
                req.getOperator(),
                req.getReason());
        log.warn(
                "manual repair, bizNo={}, action={}, operator={}, ticket={}, reason={}",
                bizNo,
                req.getAction(),
                req.getOperator(),
                req.getTicketNo(),
                req.getReason());
    }

    /** 重查支付：补建 {@code QUERY_CLOSE} 任务。键取本单固定串，与自动链路同键。 */
    private ManualRepairResp requeryPay(PlayBizRecord order) {
        String bizNo = order.getPlayBizRecordNo();
        String opNo = bizNo + "_" + TaskType.QUERY_CLOSE.name();
        taskMapper.enqueue(
                BizNoGenerator.taskNo(), bizNo, TaskType.QUERY_CLOSE.name(), opNo, 0, "{}");
        return ManualRepairResp.accepted(opNo);
    }

    /**
     * 重查 / 重试发奖：<b>以原 {@code grantOpNo} 补建任务</b>。
     *
     * <p>键从履约明细读，不重新派生 —— 派生规则若与发起侧漂移，下游会把它当成一笔全新的发放。 <b>连点两次拿到同一个键，账本里仍只有一条</b>，这是退出标准第 20 条要断言的。
     *
     * <p>重查落 {@code QUERY_GRANT}（只读），重试落 {@code GRANT}（会调下游）。两者复用同一段取键逻辑 —— 差别只在任务类型。
     */
    private ManualRepairResp retryGrant(PlayBizRecord order, RepairAction action) {
        String bizNo = order.getPlayBizRecordNo();
        List<String> opNos = reconcileMapper.selectGrantOpNos(bizNo);
        if (opNos.isEmpty()) {
            // 没有履约明细即从未发起过发放，补不出原键。此时该走的是正常履约而非重试
            throw new BizException(ErrorCode.INVALID_PARAM, "该单无发奖记录，无原幂等键可复用: " + bizNo);
        }
        TaskType type = action == RepairAction.RETRY_GRANT ? TaskType.GRANT : TaskType.QUERY_GRANT;
        for (String opNo : opNos) {
            taskMapper.enqueue(BizNoGenerator.taskNo(), bizNo, type.name(), opNo, 0, "{}");
        }
        return ManualRepairResp.accepted(String.join(",", opNos));
    }

    /** 重试回收：以原 {@code revokeNo} 补建 {@code REVOKE} 任务。 */
    private ManualRepairResp retryRevoke(PlayBizRecord order) {
        String bizNo = order.getPlayBizRecordNo();
        String revokeNo = reconcileMapper.selectRevokeOpNo(bizNo);
        if (revokeNo == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "该单未走过退款准入，无原回收键: " + bizNo);
        }
        taskMapper.enqueue(
                BizNoGenerator.taskNo(), bizNo, TaskType.REVOKE.name(), revokeNo, 0, "{}");
        return ManualRepairResp.accepted(revokeNo);
    }

    /**
     * 重试退款：以原 {@code refundNo} 补建 {@code QUERY_REFUND} 任务，<b>只查不发</b>。
     *
     * <p>人工点「重试退款」想要的是把它推到终态，而查单能做到且无副作用。补 {@code REFUND} 任务则是 真的再退一次 —— 多退一笔钱要走人工追讨（BR-B-38，与
     * {@code QUERY_REFUND} 的设计同源）。
     */
    private ManualRepairResp retryRefund(PlayBizRecord order) {
        String bizNo = order.getPlayBizRecordNo();
        String refundNo = order.getRefundNo();
        if (refundNo == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "该单无退款单号，无原键可复用: " + bizNo);
        }
        taskMapper.enqueue(
                BizNoGenerator.taskNo(), bizNo, TaskType.QUERY_REFUND.name(), refundNo, 0, "{}");
        return ManualRepairResp.accepted(refundNo);
    }

    /**
     * 标记人工完成：<b>唯一会写终态而不调下游的动作</b>。
     *
     * <p>只推进 {@code GRANT_UNKNOWN} → {@code GRANT_SUCCESS}，条件更新限定前置态 —— 已终结的单不被
     * 改写。<b>不做成「随便改成任意状态」</b>：那等于给人一个绕过全部状态机的入口，而状态机正是 「已支付必履约」这类不变量的载体。
     *
     * <p>审计记录已在前面落过，故这一步在对账里可被识别为人工干预、不计入自动收敛率。
     */
    private ManualRepairResp markDone(PlayBizRecord order, ManualRepairReq req) {
        String bizNo = order.getPlayBizRecordNo();
        int rows =
                bizRecordMapper.advanceGrantStatus(
                        bizNo, GrantStatus.GRANT_UNKNOWN.name(), GrantStatus.GRANT_SUCCESS.name());
        if (rows == 0) {
            log.info("manual mark-done not applied, order not in GRANT_UNKNOWN, bizNo={}", bizNo);
        }
        return ManualRepairResp.accepted(null);
    }

    /**
     * 导出对账证据：<b>只读，不改任何状态</b>。
     *
     * <p>汇总主单三子状态、履约明细与操作记录的关键字段，供人工判断与工单归档。V3 输出为一段文本 —— 结构化导出（CSV / 对账文件）属运维范围，本方法的形状不变。
     */
    private ManualRepairResp exportEvidence(PlayBizRecord order) {
        String bizNo = order.getPlayBizRecordNo();
        StringBuilder sb = new StringBuilder();
        sb.append("bizNo=")
                .append(bizNo)
                .append("; pay=")
                .append(order.getPayStatus())
                .append("; grant=")
                .append(order.getGrantStatus())
                .append("; refund=")
                .append(order.getRefundStatus())
                .append("; orderAmount=")
                .append(order.getOrderAmount())
                .append("; payAmount=")
                .append(order.getPayAmount())
                .append("; tradeNo=")
                .append(order.getTradeNo())
                .append("; refundNo=")
                .append(order.getRefundNo())
                .append("; grantOpNos=")
                .append(reconcileMapper.selectGrantOpNos(bizNo));
        return ManualRepairResp.evidence(sb.toString());
    }

    private PlayBizRecord findOrder(String bizNo) {
        return bizRecordMapper.selectOne(
                Wrappers.<PlayBizRecord>lambdaQuery().eq(PlayBizRecord::getPlayBizRecordNo, bizNo));
    }
}
