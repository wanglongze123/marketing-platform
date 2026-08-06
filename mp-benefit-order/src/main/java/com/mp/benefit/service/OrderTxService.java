package com.mp.benefit.service;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.benefit.config.BenefitTx;
import com.mp.benefit.entity.PlayBizRecord;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.benefit.repository.PlayBizRecordMapper;
import com.mp.benefit.repository.PlayOpRecordMapper;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.OpStatus;
import com.mp.common.enums.OpType;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RefundStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.util.BizNoGenerator;
import com.mp.common.util.IdempotentKeys;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 订单的事务边界，独立成 bean。
 *
 * <p><b>为什么不放在 BenefitOrderServiceImpl 内部</b>：{@code @Transactional} 依赖 Spring 代理，
 * 同类内部调用不经过代理，注解静默失效 —— 事务看起来配了、实际没开。这类缺陷不报错， 要到「状态改了但操作记录没落库」时才暴露，而那正是可靠任务表最怕的缺口。
 *
 * <p>本类的每个方法即一个事务单元，严格遵守《开发规范》§7.4：事务内只有 DB 操作， 无 RPC、无发消息、无 sleep。
 *
 * <p><b>只用 {@link BenefitTx} 而非裸 {@code @Transactional}</b>：四套数据源下不存在「默认」 事务管理器，不带 {@code
 * transactionManager} 属性的注解按类型注入会取到别库的管理器，{@code db_benefit} 的写 各自自动提交 ——
 * 同样不报错。这与上一段的代理失效同属一族：事务问题的失效形态是「没有事务」 而非「事务出错」（《分阶段方案》§5.6 ②）。
 */
@Service
public class OrderTxService {

    private static final Logger log = LoggerFactory.getLogger(OrderTxService.class);

    /** V1 支付有效期，V2 随关单任务一并配置化 */
    private static final int PAY_EXPIRE_MINUTES = 30;

    private final PlayBizRecordMapper bizRecordMapper;
    private final PlayOpRecordMapper opRecordMapper;
    private final BenefitTaskMapper taskMapper;

    public OrderTxService(
            PlayBizRecordMapper bizRecordMapper,
            PlayOpRecordMapper opRecordMapper,
            BenefitTaskMapper taskMapper) {
        this.bizRecordMapper = bizRecordMapper;
        this.opRecordMapper = opRecordMapper;
        this.taskMapper = taskMapper;
    }

    /** 建单 + 写操作记录。 */
    @BenefitTx
    public PlayBizRecord createOrder(
            CreateTradeReq req,
            String bizNo,
            long salePrice,
            int configVersion,
            String priceSnapshot,
            String benefitSnapshot) {
        PlayBizRecord record = new PlayBizRecord();
        record.setPlayBizRecordNo(bizNo);
        record.setActivityId(req.getActivityId());
        record.setSkuId(req.getSkuId());
        record.setUserId(req.getUserId());
        record.setClientReqNo(req.getClientReqNo());
        record.setQuantity(req.getQuantity());
        record.setPayStatus(PayStatus.WAIT_PAY.name());
        record.setGrantStatus(GrantStatus.NOT_START.name());
        record.setRefundStatus(RefundStatus.NONE.name());
        record.setOrderAmount(salePrice);
        record.setCurrency("CNY");
        record.setConfigVersion(configVersion);
        record.setPriceSnapshot(priceSnapshot);
        record.setBenefitSnapshot(benefitSnapshot);
        record.setExpireTime(LocalDateTime.now().plusMinutes(PAY_EXPIRE_MINUTES));
        bizRecordMapper.insert(record);

        opRecordMapper.upsert(
                bizNo + "_CREATE",
                req.getUserId()
                        + "_"
                        + req.getActivityId()
                        + "_"
                        + req.getSkuId()
                        + "_"
                        + req.getClientReqNo(),
                bizNo,
                req.getUserId(),
                req.getActivityId(),
                OpType.CREATE_TRADE.name(),
                "",
                OpStatus.SUCCESS.name());
        return record;
    }

    /**
     * 支付回调：条件更新 + 操作记录同事务。
     *
     * @return 是否真的推进了状态；false 表示条件不满足（重复或乱序通知）
     */
    @BenefitTx
    public boolean applyPayCallback(PayCallbackReq req, PlayBizRecord order, PayStatus target) {
        String bizNo = order.getPlayBizRecordNo();

        int rows =
                bizRecordMapper.advancePayStatus(
                        bizNo,
                        PayStatus.WAIT_PAY.name(),
                        target.name(),
                        req.getPayAmount(),
                        req.getTradeNo());

        // op_seq 取 notifySeq 而非空串：同一订单会收到多条语义不同的通知，各自都要留痕。
        // 取空串会让第二条在 uk_biz_op 上冲突被拒，执行不到上面的条件更新。
        opRecordMapper.upsert(
                bizNo + "_PAY_" + req.getNotifySeq(),
                IdempotentKeys.payCallback(req.getTradeNo(), req.getNotifySeq()),
                bizNo,
                order.getUserId(),
                order.getActivityId(),
                OpType.PAY_CALLBACK.name(),
                req.getNotifySeq(),
                OpStatus.SUCCESS.name());

        if (rows == 0) {
            // 幂等三道闸的第三道生效，不是错误：不抛异常、不重试、不打 ERROR
            log.info(
                    "payCallback rejected by conditional update, bizNo={}, target={}",
                    bizNo,
                    target);
            return false;
        }

        // 本地消息表：收款与「发起履约」绑同一事务。ACK 支付前 GRANT 任务已落库，
        // 此后任何一点崩溃，调度器重启后续跑 —— 这是「已收款必履约」的根。
        // 若改为提交后同步调用，「改状态」与「触发履约」之间存在无法消除的崩溃窗口。
        if (target == PayStatus.PAY_SUCCESS) {
            taskMapper.enqueue(
                    BizNoGenerator.taskNo(),
                    bizNo,
                    TaskType.GRANT.name(),
                    // 一单一次履约编排，用确定性本地键而非留空 —— 唯一索引不对 NULL 去重
                    bizNo + "_GRANT",
                    // 立即可执行
                    0,
                    "{}");
        }

        log.info("payCallback advanced, bizNo={}, WAIT_PAY -> {}", bizNo, target);
        return true;
    }

    /** 履约启动：置 GRANTING + 落操作记录中间态。必须先于 RPC。 */
    @BenefitTx
    public void startGrant(PlayBizRecord order) {
        String bizNo = order.getPlayBizRecordNo();

        // affected_rows=0 表示已是 GRANTING（重入），继续走发放流程 ——
        // reward 侧按 opNo 幂等，重复调用返回原结果，不会重复发放
        bizRecordMapper.advanceGrantStatus(
                bizNo, GrantStatus.NOT_START.name(), GrantStatus.GRANTING.name());

        opRecordMapper.upsert(
                bizNo + "_GRANT",
                bizNo + "_GRANT",
                bizNo,
                order.getUserId(),
                order.getActivityId(),
                OpType.GRANT_BENEFIT.name(),
                "",
                OpStatus.PROCESSING.name());
    }

    /** 履约收尾：回写主单与操作记录终态。 */
    @BenefitTx
    public void finishGrant(String bizNo, GrantStatus target) {
        bizRecordMapper.advanceGrantStatus(bizNo, GrantStatus.GRANTING.name(), target.name());

        boolean success = target == GrantStatus.GRANT_SUCCESS;
        opRecordMapper.finish(
                bizNo,
                OpType.GRANT_BENEFIT.name(),
                "",
                success ? OpStatus.SUCCESS.name() : OpStatus.FAILED.name(),
                success ? RetStatus.SUCCESS.name() : RetStatus.FAIL.name());
    }

    /** 回填支付单号。独立短事务，不改任何状态。 */
    @BenefitTx
    public void fillTradeNo(String bizNo, String tradeNo) {
        bizRecordMapper.fillTradeNo(bizNo, tradeNo);
    }
}
