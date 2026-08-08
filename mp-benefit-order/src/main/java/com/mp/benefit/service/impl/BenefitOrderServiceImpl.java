package com.mp.benefit.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mp.api.activity.dto.ActivityConfResp;
import com.mp.api.activity.service.ActivityService;
import com.mp.api.benefit.dto.ConvergenceResp;
import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.benefit.dto.FulfillmentItem;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.benefit.dto.PreConsultReq;
import com.mp.api.benefit.dto.PreConsultResp;
import com.mp.api.benefit.dto.QueryOrderResp;
import com.mp.api.benefit.dto.RevokeAdmitReq;
import com.mp.api.benefit.dto.RevokeAdmitResp;
import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.api.mock.dto.PayCloseResp;
import com.mp.api.mock.dto.PayCreateReq;
import com.mp.api.mock.dto.PayCreateResp;
import com.mp.api.mock.dto.PayRefundResp;
import com.mp.api.mock.service.MockPayService;
import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.api.reward.dto.GrantRewardResp;
import com.mp.api.reward.dto.RevokeRewardReq;
import com.mp.api.reward.dto.RevokeRewardResp;
import com.mp.api.reward.dto.RewardItem;
import com.mp.api.reward.service.RewardService;
import com.mp.benefit.entity.BenefitFulfillmentRecord;
import com.mp.benefit.entity.BenefitItem;
import com.mp.benefit.entity.BenefitSku;
import com.mp.benefit.entity.BenefitTask;
import com.mp.benefit.entity.PlayBizRecord;
import com.mp.benefit.entity.PlayOpRecord;
import com.mp.benefit.lock.BizLock;
import com.mp.benefit.lock.ContentionMetrics;
import com.mp.benefit.repository.BenefitFulfillmentRecordMapper;
import com.mp.benefit.repository.BenefitItemMapper;
import com.mp.benefit.repository.BenefitSkuMapper;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.benefit.repository.PlayBizRecordMapper;
import com.mp.benefit.repository.PlayOpRecordMapper;
import com.mp.benefit.service.OrderTxService;
import com.mp.benefit.service.RevokedItem;
import com.mp.benefit.service.SnapshotItem;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.ItemGrantStatus;
import com.mp.common.enums.OpType;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RefundStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.exception.BizException;
import com.mp.common.security.ConsultTokenPayload;
import com.mp.common.security.ConsultTokenSigner;
import com.mp.common.security.PayNotifySigner;
import com.mp.common.util.BizNoGenerator;
import com.mp.common.util.IdempotentKeys;
import com.mp.common.util.ReqFields;
import com.mp.common.web.TraceIdHolder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 权益售卖实现：编排层，不含事务。
 *
 * <p>所有事务边界收在 {@link OrderTxService} —— 独立 bean 才能让 {@code @Transactional} 经过 Spring
 * 代理生效；同类内部调用会使注解静默失效。
 */
@DubboService
@Service
public class BenefitOrderServiceImpl implements BenefitOrderService {

    private static final Logger log = LoggerFactory.getLogger(BenefitOrderServiceImpl.class);

    /**
     * 快照的序列化器。<b>关闭未知字段报错</b>。
     *
     * <p>快照是长期持久化数据，写入与读出可能相隔数月，其间 {@link SnapshotItem} 的字段会增删。 Jackson 默认 {@code
     * FAIL_ON_UNKNOWN_PROPERTIES = true}，于是新版写入的快照在回滚到旧版后 读不回来 —— 而履约、退款一律只读快照。
     *
     * <p>失效形态是<b>已收款但永不履约</b>：{@code groupByProvider} 抛 {@code IllegalStateException}， 调度器按未预期异常判
     * {@code UNKNOWN} 并短退避重试，每一轮都在同一行 JSON 上失败，直至 {@code DEAD}。死信原因是一行序列化异常，排查时不会指向数据兼容性。
     *
     * <p>忽略未知字段只解决向前兼容（旧版读新版数据）。反向由 record 的字段默认值承担：新增字段 在旧数据中缺失时为 {@code null} 或 {@code
     * 0}，故新增字段必须允许这两个取值有意义，不能把 「缺失」与「取值为零」当成不同语义。
     */
    private static final ObjectMapper JSON =
            JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** 单号碰撞重试次数。UUIDv7 连撞三次实际不可能，超出即视为库层异常而非碰撞 */
    private static final int BIZ_NO_RETRY = 3;

    /**
     * 履约扇出的整体超时（秒）。
     *
     * <p><b>必须显著短于任务租约（30 秒）</b>：扇出跑超租约时，任务会被另一实例正当接管并重跑 —— 于是同一笔发放有两个实例同时在途。下游按 opNo
     * 幂等挡得住重复发放，但两边的状态回写会 互相覆盖，且 fencing 会把先完成那个的结果判掉。取 10 秒留出三倍余量。
     */
    private static final long FANOUT_TIMEOUT_SECONDS = 10;

    // V0/V1 单进程用 Spring 注入；V3 拆服务后改回 @DubboReference(protocol="tri")
    @Autowired private ActivityService activityService;
    @Autowired private RewardService rewardService;
    @Autowired private MockPayService mockPayService;

    private final OrderTxService tx;
    private final BenefitSkuMapper skuMapper;
    private final BenefitItemMapper itemMapper;
    private final PlayBizRecordMapper bizRecordMapper;
    private final BenefitFulfillmentRecordMapper fulfillmentMapper;
    private final PlayOpRecordMapper opRecordMapper;
    private final BenefitTaskMapper taskMapper;
    private final ConsultTokenSigner tokenSigner;
    private final PayNotifySigner payNotifySigner;
    private final BizLock bizLock;
    private final ContentionMetrics contention;
    private final long tokenTtlSeconds;

    public BenefitOrderServiceImpl(
            OrderTxService tx,
            BenefitSkuMapper skuMapper,
            BenefitItemMapper itemMapper,
            PlayBizRecordMapper bizRecordMapper,
            BenefitFulfillmentRecordMapper fulfillmentMapper,
            PlayOpRecordMapper opRecordMapper,
            BenefitTaskMapper taskMapper,
            ConsultTokenSigner tokenSigner,
            PayNotifySigner payNotifySigner,
            BizLock bizLock,
            ContentionMetrics contention,
            @Value("${mp.consult-token.ttl-seconds}") long tokenTtlSeconds) {
        this.tx = tx;
        this.skuMapper = skuMapper;
        this.itemMapper = itemMapper;
        this.bizRecordMapper = bizRecordMapper;
        this.fulfillmentMapper = fulfillmentMapper;
        this.opRecordMapper = opRecordMapper;
        this.taskMapper = taskMapper;
        this.tokenSigner = tokenSigner;
        this.payNotifySigner = payNotifySigner;
        this.bizLock = bizLock;
        this.contention = contention;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    // ------------------------------------------------------------------
    // ⓪ 预咨询
    // ------------------------------------------------------------------

    /**
     * 试算并签发咨询凭证。只读，不占库存、不建单、不落操作记录。
     *
     * <p><b>算价与签发在同一次读里完成</b>：先算出价再单独查一次 SKU 去签，两次读之间运营改价就会 签出一张与展示价不符的凭证 —— 用户看到 9.9、凭证里签着
     * 19.9，比价照常通过而用户被多收钱。
     */
    @Override
    public PreConsultResp preConsult(PreConsultReq req) {
        // 三者都进凭证签名。为空则签出一张字段缺失的凭证，下单时逐字段比对反而通过
        ReqFields.required(req.getUserId(), "userId");
        ReqFields.required(req.getActivityId(), "activityId");
        ReqFields.required(req.getSkuId(), "skuId");

        ActivityConfResp activity = activityService.queryActivityConf(req.getActivityId());
        if (activity == null || !activity.isAvailable()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "活动不可参与: " + req.getActivityId());
        }
        BenefitSku sku = requireOnSaleSku(req.getSkuId());

        // 成交价由服务端算定（PRD BR-B-03）。V2 无优惠计算，成交价即售卖价；
        // V3 接优惠后此处替换为计价逻辑，凭证的签发位置不变
        long dealPrice = sku.getSalePrice();

        // packageVersion 必须进签名：它决定权益包的内容，而换版时价格可以一分不动 ——
        // 只签价格挡不住「按月卡+券咨询、按只剩券的新版履约」
        String token =
                tokenSigner.sign(
                        req.getUserId(),
                        req.getActivityId(),
                        req.getSkuId(),
                        dealPrice,
                        sku.getPackageVersion(),
                        activity.getCurVersion(),
                        tokenTtlSeconds);

        PreConsultResp resp = new PreConsultResp();
        resp.setActivityId(req.getActivityId());
        resp.setSkuId(req.getSkuId());
        resp.setConfigVersion(activity.getCurVersion());
        resp.setOriginPrice(sku.getListPrice());
        resp.setDealPrice(dealPrice);
        resp.setConsultToken(token);
        // 从凭证自身读回，而不是在此另算一次 —— 另算会与签名里的值漂移，
        // 端上据此判断「还没过期」而下单被拒
        resp.setExpireAt(tokenSigner.verify(token).expireAtEpochMilli());

        log.info(
                "preConsult issued, user={}, sku={}, dealPrice={}",
                req.getUserId(),
                req.getSkuId(),
                dealPrice);
        return resp;
    }

    // ------------------------------------------------------------------
    // ① 下单
    // ------------------------------------------------------------------

    /**
     * 下单。<b>锁只是减少走到 L3 的冲突，正确性由 {@code uk_idempotent} 兜底</b>（技术方案 §6.1）。
     *
     * <p>锁键取业务幂等键的四元组，与唯一索引同维度 —— 锁挡下的与唯一索引挡下的是同一批请求， 只是前者在进库之前。去掉锁后本方法的正确性不变，只是并发重试会更多地撞到唯一索引上。
     */
    @Override
    public CreateTradeResp createTrade(CreateTradeReq req) {
        // 四元组同时是锁键与 uk_idempotent 的组成。校验须先于加锁：任一为空则不同请求
        // 拼出同一把锁，且落库时撞上同一个幂等键，第二笔被当作重复下单返回他人订单
        ReqFields.required(req.getUserId(), "userId");
        ReqFields.required(req.getActivityId(), "activityId");
        ReqFields.required(req.getSkuId(), "skuId");
        ReqFields.required(req.getClientReqNo(), "clientReqNo");

        return bizLock.aroundCreateTrade(
                req.getUserId(),
                req.getActivityId(),
                req.getSkuId(),
                req.getClientReqNo(),
                () -> doCreateTrade(req));
    }

    private CreateTradeResp doCreateTrade(CreateTradeReq req) {
        // V1 冻结 quantity = 1。显式拒绝而非默默忽略 —— 后者会让调用方付一份钱得一份权益且无报错
        if (req.getQuantity() != 1) {
            throw new BizException(
                    ErrorCode.INVALID_PARAM, "V1 仅支持 quantity=1，实际 " + req.getQuantity());
        }

        // ① 验凭证：签名 + 时效 + 逐字段比对。不通过则 4003，不建单
        ConsultTokenPayload token = verifyConsultToken(req);

        ActivityConfResp activity = activityService.queryActivityConf(req.getActivityId());
        if (activity == null || !activity.isAvailable()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "活动不可参与: " + req.getActivityId());
        }

        BenefitSku sku = requireOnSaleSku(req.getSkuId());

        // ①.5 权益包版本比对。<b>比价挡不住这一类</b>：换版时价格可以一分不动 ——
        // 用户按「月卡 + 券」咨询，运营把 SKU 改指向只剩券的新版，价格仍是 99 元，
        // 于是验签过、比价也过，而建单时读的是新版快照，用户按月卡+券付钱只拿到券。
        // 价格没变不代表承诺没变
        if (token.packageVersion() != sku.getPackageVersion()) {
            log.warn(
                    "createTrade package version changed, user={}, sku={}, token={}, current={}",
                    req.getUserId(),
                    req.getSkuId(),
                    token.packageVersion(),
                    sku.getPackageVersion());
            // 判 4003 而非 1711：1711 的语义是「价格不一致」（技术方案 §4.1），
            // 而此处价格可能完全没变。不为此新造码 —— 对端上的处置与 4003 相同：重新咨询
            throw new BizException(ErrorCode.INVALID_TOKEN, "权益内容已变化，请刷新后重试");
        }

        // ④.5 比价：服务端重算价与凭证成交价比对。不等则 1711，不建单
        long recalcPrice = sku.getSalePrice();
        if (token.dealPrice() != recalcPrice) {
            // 不取较低价、不静默使用新价（PRD BR-B-08）：取低价平台亏损，取新价用户看到 9.9
            // 却被扣 19.9 —— 后者是明确的产品事故。一律拒绝，由端上重新咨询
            log.warn(
                    "createTrade price mismatch, user={}, sku={}, token={}, recalc={}",
                    req.getUserId(),
                    req.getSkuId(),
                    token.dealPrice(),
                    recalcPrice);
            throw new BizException(ErrorCode.PRICE_MISMATCH, "价格已变化，请刷新后重试");
        }

        List<SnapshotItem> snapshot = buildSnapshot(sku);
        String priceSnapshot =
                toJson(Map.of("listPrice", sku.getListPrice(), "salePrice", sku.getSalePrice()));
        String benefitSnapshot = toJson(snapshot);

        // 应付金额取重算价而非凭证价：两者已比对相等，取服务端这一份是为了让「金额来自服务端」
        // 在代码里也成立 —— 日后比价逻辑若被削弱，这里不会跟着变成「按客户端带来的价格收款」。
        //
        // 配置版本取活动当前值而非凭证中的：凭证里那份记录的是「签发时活动是哪个版本」，
        // 供审计用；主单要冻结的是下单这一刻的版本，履约与退款一律读它
        Insert inserted =
                insertOrder(
                        req,
                        recalcPrice,
                        activity.getCurVersion(),
                        sku.getPurchaseLimitQty() == null ? 0 : sku.getPurchaseLimitQty(),
                        priceSnapshot,
                        benefitSnapshot);
        if (inserted.duplicated()) {
            return toCreateResp(inserted.record());
        }

        String bizNo = inserted.record().getPlayBizRecordNo();

        // 事务外：调支付下单。事务内调 RPC 违反《开发规范》§7.4
        PayCreateReq payReq = new PayCreateReq();
        payReq.setOutTradeNo(bizNo);
        payReq.setAmount(inserted.record().getOrderAmount());
        payReq.setCurrency(inserted.record().getCurrency());
        payReq.setUserId(req.getUserId());
        PayCreateResp payResp = mockPayService.createPay(payReq);

        if (payResp.getRetStatus() != RetStatus.SUCCESS) {
            throw new UnsupportedOperationException(
                    "V2 实现支付下单的非 SUCCESS 分支，实际返回 " + payResp.getRetStatus());
        }

        // 事务二：回填支付单号
        tx.fillTradeNo(bizNo, payResp.getTradeNo());
        inserted.record().setTradeNo(payResp.getTradeNo());

        log.info("createTrade done, bizNo={}, tradeNo={}", bizNo, payResp.getTradeNo());
        return toCreateResp(inserted.record());
    }

    /** 建单结果：{@code duplicated} 为真时 {@code record} 是已存在的原单。 */
    private record Insert(PlayBizRecord record, boolean duplicated) {}

    /**
     * 建单，按冲突来源分流 —— {@code play_biz_record} 上三道唯一索引，处置相反：
     *
     * <ul>
     *   <li>{@code uk_idempotent}：幂等命中，返回原单（《开发规范》§7.3）
     *   <li>{@code uk_biz_no}：单号碰撞，换号重试。生成器是概率保证，唯一索引才是确定性保证
     * </ul>
     *
     * <p>合并处理的后果：碰撞当幂等命中会返回<b>另一用户的订单</b>；幂等命中当碰撞则为同一请求建出第二笔单。
     *
     * <p><b>库存预占也在 {@code createOrder} 事务内，故两条分支都不会留下多占的库存</b>（PRD BR-B-06
     * 「同一幂等请求不重复预占库存」）：幂等命中时事务已回滚，预占随之撤销；单号碰撞重试时同理， 下一轮重新预占。若把预占提到事务外，幂等重试就会每次多占一份，且没有任何机制会还回去。
     */
    private Insert insertOrder(
            CreateTradeReq req,
            long salePrice,
            int configVersion,
            int purchaseLimitQty,
            String priceSnapshot,
            String benefitSnapshot) {
        for (int attempt = 1; attempt <= BIZ_NO_RETRY; attempt++) {
            String bizNo = BizNoGenerator.bizNo();
            try {
                return new Insert(
                        tx.createOrder(
                                req,
                                bizNo,
                                salePrice,
                                configVersion,
                                purchaseLimitQty,
                                priceSnapshot,
                                benefitSnapshot),
                        false);
            } catch (DuplicateKeyException e) {
                PlayBizRecord existing = findByIdempotent(req);
                if (existing != null) {
                    // 计数打在这一支，而非 catch 开头：catch 有两个来源，含义完全不同 ——
                    // 幂等命中是「并发抢同一个键」，正是 L2 锁要减少的；单号碰撞是 UUIDv7
                    // 撞号，与锁无关，锁再有效也不会变少。计成同一个数会让去锁对照失去意义
                    contention.onDuplicateKey();
                    log.info(
                            "createTrade duplicated, return existing order, user={}, clientReqNo={}",
                            req.getUserId(),
                            req.getClientReqNo());
                    return new Insert(existing, true);
                }
                // 幂等键查不到 → 冲突来自单号本身，换一个再试
                log.warn("bizNo collision, retry {}/{}, bizNo={}", attempt, BIZ_NO_RETRY, bizNo);
            }
        }
        throw new BizException(ErrorCode.DOWNSTREAM_UNKNOWN, "业务单号连续冲突，建单失败");
    }

    // ------------------------------------------------------------------
    // ② 支付回调
    // ------------------------------------------------------------------

    /**
     * 支付结果通知。
     *
     * <p><b>验签在锁外、锁在验签之后</b>：未通过验签的请求不该占用锁资源 —— 否则伪造通知可以靠 刷同一个业务号把真实通知挡在锁外，形成一种廉价的拒绝服务。
     *
     * <p>锁键取 {@code outTradeNo}（= 主单号），<b>与关单锁同键</b> —— 「用户点取消的同时付款成功」 是这两条链路必须互斥的场景。同一订单的多条通知（含乱序的
     * SUCCESS 与 CLOSED）也因此被串行化， 条件更新更少走到冲突分支。
     */
    @Override
    public RetStatus payCallback(PayCallbackReq req) {
        // outTradeNo 是锁键与定位依据，notifySeq 参与幂等键 —— 任一为空都会让不同订单的
        // 通知落到同一把锁、同一个 idempotent_key 上。校验先于验签：两者都不涉及业务数据，
        // 而缺字段的请求连签名字段集都凑不齐
        ReqFields.required(req.getOutTradeNo(), "outTradeNo");
        ReqFields.required(req.getNotifySeq(), "notifySeq");

        // ① 验签，先于一切（PRD FR-B03 ①、BR-B-12「未通过验签的通知不得更新任何业务状态」）。
        //
        // 必须在定位主单之前：放到后面则未验签的请求已经能探测「这个 bizNo 存不存在」，
        // 且任何一处提前抛出的异常都可能在验签之外留下痕迹。验签是信任边界的第一道，
        // 边界之外的输入在通过它以前不该触碰任何业务数据
        if (!payNotifySigner.verify(req.signFields(), req.getSign())) {
            // 不回显期望签名，也不区分「没带签名」与「签名不对」—— 两者对调用方是同一处置，
            // 区分开来等于告诉攻击者「格式猜对了，只是算错了」
            log.error("payCallback signature invalid, outTradeNo={}", req.getOutTradeNo());
            throw new BizException(ErrorCode.PAY_NOTIFY_SIGN_INVALID, "支付通知验签失败");
        }

        // ② 验签通过后才加锁：同一订单的多条通知、以及并发的关单，在此串行化
        return bizLock.aroundPayCallback(req.getOutTradeNo(), () -> doPayCallback(req));
    }

    private RetStatus doPayCallback(PayCallbackReq req) {
        // 按 outTradeNo（= bizNo）定位，不依赖 trade_no 是否已回填
        String bizNo = req.getOutTradeNo();
        PlayBizRecord order = findByBizNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }

        PayStatus target = parsePayStatus(req.getPayStatus());

        // 金额校验：仅验签不够 —— 验签只证明消息来自支付方，不证明金额与本单应付一致。
        // 校验失败不推进任何状态（V2 补对账记录与 P0 告警）
        //
        // 只校验收款通知。FAILED / CLOSED 说的是「这笔没收成」，支付方在这两类通知里
        // 不带金额或带 0 是常态 —— 一律要求等于应付额，会把线上所有关闭通知判成 1731，
        // 订单永远停在 WAIT_PAY 而告警里全是不存在的资损。校验的对象是「收了多少钱」，
        // 没收钱时它没有可校验的内容
        if (target == PayStatus.PAY_SUCCESS) {
            if (req.getPayAmount() != order.getOrderAmount()
                    || !order.getCurrency().equals(req.getCurrency())) {
                log.error(
                        "payCallback amount mismatch, bizNo={}, expect={}, actual={}",
                        bizNo,
                        order.getOrderAmount(),
                        req.getPayAmount());
                throw new BizException(ErrorCode.PAY_AMOUNT_MISMATCH, "支付金额或币种不一致");
            }
        }

        // 履约不在此处触发：GRANT 任务已在同一事务内落库，由调度器驱动（技术方案 §6.5）。
        // V1 曾在事务提交后同步调 grantBenefit —— 「改状态」与「触发履约」之间存在崩溃窗口，
        // 窗口内进程挂掉即已收款未发奖，且不留任何可供恢复的痕迹。
        tx.applyPayCallback(req, order, target);
        return RetStatus.SUCCESS;
    }

    // ------------------------------------------------------------------
    // ②.5 关单
    // ------------------------------------------------------------------

    /**
     * 关闭订单，按下游四分类分支处置。
     *
     * <p><b>关单前必须先问支付方</b>（PRD FR-B04 ②、BR-B-17「关闭与支付并发时以支付系统结果为准」）： 平台的 {@code pay_status}
     * 只能证明平台知道什么，证明不了支付方那边有没有收到钱。直接置 {@code CLOSED} 会在「用户正在付款」的窗口里把已收款的单关掉。
     *
     * <p>四类各自的处置见技术方案 §4.5，关键在 {@code UNKNOWN}：
     *
     * <ul>
     *   <li>{@code SUCCESS} —— 确认未支付，置 {@code CLOSED}，同事务落释放任务
     *   <li>{@code FAIL} —— 对方回报已支付，拒绝关闭（{@code 1741}），转由支付通知推进
     *   <li>{@code UNKNOWN} / {@code PROCESSING} —— 结果未定，置 {@code CLOSING} 并查单，<b>不释放库存</b>
     * </ul>
     *
     * @return 关单本身的四分类结果，供任务调度器决定退避
     */
    @Override
    public RetStatus closeOrder(String bizNo, String opSeq) {
        // 关单与支付通知会并发（用户点取消的同时付款成功）。锁把两者串行化，
        // 但真正的互斥仍是主单的条件更新 —— 锁失效时 WAIT_PAY 只能被推进一次
        return bizLock.aroundCloseOrder(bizNo, () -> doCloseOrder(bizNo, opSeq));
    }

    private RetStatus doCloseOrder(String bizNo, String opSeq) {
        PlayBizRecord order = findByBizNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }

        // 已是终态：重复关单直接返回，不再打扰支付方（BR-B-18）
        PayStatus current = PayStatus.valueOf(order.getPayStatus());
        if (current == PayStatus.CLOSED) {
            log.info("closeOrder already closed, skip, bizNo={}", bizNo);
            return RetStatus.SUCCESS;
        }
        if (current == PayStatus.PAY_SUCCESS) {
            // 已支付不可关（BR-B-16）。抛业务码而非静默返回 —— 调用方（用户点取消、
            // 运营清理）需要知道「没关成，因为已经付过了」
            throw new BizException(ErrorCode.ORDER_ALREADY_PAID, "订单已支付，拒绝关闭: " + bizNo);
        }
        if (current == PayStatus.PAY_FAILED) {
            log.info("closeOrder on failed payment, nothing to close, bizNo={}", bizNo);
            return RetStatus.SUCCESS;
        }

        RetStatus downstream = askPayToClose(bizNo);

        switch (downstream) {
            case SUCCESS -> {
                tx.applyClosed(bizNo, order, opSeq);
                return RetStatus.SUCCESS;
            }
            case FAIL -> {
                // 支付方回报已收款。不置任何终态 —— 推进到 PAY_SUCCESS 是支付通知的职责，
                // 此处越俎代庖会在「通知尚未到达」时把金额等字段写成猜测值
                log.warn("closeOrder rejected: already paid downstream, bizNo={}", bizNo);
                throw new BizException(ErrorCode.ORDER_ALREADY_PAID, "订单已支付，拒绝关闭: " + bizNo);
            }
            default -> {
                // UNKNOWN / PROCESSING：结果未定，进中间态并查单
                tx.applyClosing(bizNo, order, opSeq);
                return downstream;
            }
        }
    }

    /**
     * 问支付方能否关单。异常一律映射 {@code UNKNOWN}。
     *
     * <p>判 {@code FAIL} 等于替支付方断言「已收款」，而那个断言没有依据 —— 会让一笔本可关闭的单 永远停在 {@code WAIT_PAY}，库存与额度被永久占着。
     */
    private RetStatus askPayToClose(String bizNo) {
        try {
            PayCloseResp resp = mockPayService.closePay(bizNo);
            return resp.getRetStatus();
        } catch (Exception e) {
            log.warn("close pay failed, treat as UNKNOWN, bizNo={}", bizNo, e);
            return RetStatus.UNKNOWN;
        }
    }

    /**
     * 查单收敛 {@code CLOSING}：再问一次支付方，按结果推进到确定态。
     *
     * <p>由 {@code QUERY_CLOSE} 任务驱动。复用 {@code closePay} 而非另开查询接口 —— 两者要问的是同一个
     * 问题（这笔到底支付了没），分开则判定逻辑迟早漂移。
     *
     * @return 收敛结果；仍未定则返回 {@code UNKNOWN} / {@code PROCESSING}，调用方据此继续退避
     */
    @Override
    public RetStatus reconcileClose(String bizNo) {
        PlayBizRecord order = findByBizNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }

        PayStatus current = PayStatus.valueOf(order.getPayStatus());
        if (current != PayStatus.CLOSING) {
            // 已被别的路径收敛（支付通知先到了）。不是错误
            log.info("reconcileClose already settled, bizNo={}, payStatus={}", bizNo, current);
            return RetStatus.SUCCESS;
        }

        RetStatus downstream = askPayToClose(bizNo);
        switch (downstream) {
            case SUCCESS -> {
                tx.applyClosed(bizNo, order, "");
                return RetStatus.SUCCESS;
            }
            case FAIL -> {
                // 确认已支付：转入正常履约，补建 GRANT 与 STOCK_CONSUME
                tx.applyPaidAfterClosing(bizNo, order);
                return RetStatus.SUCCESS;
            }
            default -> {
                return downstream;
            }
        }
    }

    // ------------------------------------------------------------------
    // ③ 履约编排
    // ------------------------------------------------------------------

    @Override
    public RetStatus grantBenefit(String bizNo) {
        PlayBizRecord order = findByBizNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }

        // 重入分支：终态不重新发起。GRANT_SUCCESS 与 GRANT_FAILED 都是终态，且都不在
        // startGranting 的入边内（入边为 NOT_START / GRANT_UNKNOWN）—— 不在此拦截则会照常
        // 调一次下游，而 finishGrant 的条件更新命中 0 行，状态原地不动且不报错
        GrantStatus current = GrantStatus.valueOf(order.getGrantStatus());
        if (current == GrantStatus.GRANT_SUCCESS) {
            log.info("grantBenefit already done, skip, bizNo={}", bizNo);
            return RetStatus.SUCCESS;
        }
        if (current == GrantStatus.GRANT_FAILED) {
            // 返回 FAIL 而非 SUCCESS：这是确定的失败结论，调度器据此置 DONE 停止重试。
            // 返回 SUCCESS 会把「发放失败」记成「任务成功」，掩盖需要人工介入的单
            log.info("grantBenefit already failed, no re-dispatch, bizNo={}", bizNo);
            return RetStatus.FAIL;
        }

        // 事务 A：置 GRANTING + 落操作记录中间态。
        // 必须先于 RPC —— 否则调用发出后崩溃将没有任何痕迹，而查单收敛以这条记录为锚点
        tx.startGrant(order);

        // 按 provider_type 分组：grantOpNo 粒度是「一次调用 = 一个供应方」，
        // 组合权益跨多供应方时天然拆成 N 次调用、N 个幂等键
        Map<String, List<SnapshotItem>> groups = groupByProvider(order.getBenefitSnapshot());

        // 虚拟线程扇出，记下未收敛的那些 —— 它们各自需要一条查单任务
        List<String> unresolvedOpNos = new ArrayList<>();
        boolean allSuccess = true;
        boolean anyUnresolved = false;
        for (Map.Entry<String, RetStatus> e : fanOutGrant(order, groups).entrySet()) {
            RetStatus one = e.getValue();
            if (one != RetStatus.SUCCESS) {
                allSuccess = false;
            }
            if (one == RetStatus.UNKNOWN || one == RetStatus.PROCESSING) {
                anyUnresolved = true;
                unresolvedOpNos.add(IdempotentKeys.grantOpNo(bizNo, e.getKey()));
            }
        }

        // 事务 B：回写终态 + 未收敛项落查单任务，同事务
        //
        // 有任何一组未收敛就置 GRANT_UNKNOWN，而不是「多数成功即成功」：主单状态要能回答
        // 「这笔到底发完没有」，部分未知时答案就是不知道。置 GRANT_FAILED 更糟 —— 那会让
        // 对账把一笔可能已发放的单当作待补偿
        GrantStatus target =
                anyUnresolved
                        ? GrantStatus.GRANT_UNKNOWN
                        : (allSuccess ? GrantStatus.GRANT_SUCCESS : GrantStatus.GRANT_FAILED);
        tx.finishGrant(bizNo, target, unresolvedOpNos);

        log.info("grantBenefit done, bizNo={}, groups={}, result={}", bizNo, groups.size(), target);

        // 返回值供任务调度器决定退避：UNKNOWN 短退避、PROCESSING 长退避、FAIL 不再重试
        if (anyUnresolved) {
            return RetStatus.UNKNOWN;
        }
        return allSuccess ? RetStatus.SUCCESS : RetStatus.FAIL;
    }

    /**
     * 履约扇出：每个供应方一条虚拟线程并行调用（技术方案 §7.2、《开发规范》§6.5）。V3 PR-9。
     *
     * <p><b>禁止 fail-fast，这是本方法最重要的一条</b>。{@code StructuredTaskScope.ShutdownOnFailure} 或 {@code
     * invokeAny} 的语义是「任一失败即取消其余」——用在发奖上意味着券发放失败时，正在飞行中的 月卡 RPC 被
     * interrupt。<b>那笔调用可能已经到达供应方并成功执行</b>，而本地既没拿到结果也没写入 终态：权益已发出却无任何记录。后续按「核心权益失败 →
     * 回收附加权益后退款」处置时，回收清单里 没有月卡 —— 退了钱、月卡还在用户手里，直接资损。
     *
     * <p>故用 {@code newVirtualThreadPerTaskExecutor} + {@code invokeAll}：<b>所有子任务都跑完</b>，失败项 各自落
     * {@code UNKNOWN} 并挂查单任务，由收敛机制查明真实结果。
     *
     * <p><b>{@code invokeAll} 必须带超时</b>：不带的话一个卡死的供应方会让整个扇出无限期挂起，而调用方 是任务调度器，它的租约会先到期，任务被另一实例接管重跑 ——
     * 于是同一笔发放有两个实例同时在途。
     *
     * <p><b>超时被 cancel 的项归 {@code UNKNOWN} 而非 {@code FAIL}</b>：{@code CancellationException} 说明的是
     * 「本地放弃等待」，不是「供应方没做」—— 它可能已经执行成功。判 {@code FAIL} 就是替下游断言「没做」， 而这个断言没有依据。
     *
     * <p><b>不依赖 {@code StructuredTaskScope}</b>：它在 JDK 21 仍是 preview（JEP 453，需 {@code
     * --enable-preview}），交付物不应依赖预览 API。
     *
     * @return 供应方 → 该组的四分类结果，顺序与入参一致
     */
    private Map<String, RetStatus> fanOutGrant(
            PlayBizRecord order, Map<String, List<SnapshotItem>> groups) {
        List<String> providers = new ArrayList<>(groups.keySet());
        List<Callable<RetStatus>> jobs = new ArrayList<>(providers.size());
        for (String provider : providers) {
            // 每个 Callable 自带 traceId：虚拟线程不继承调用者的 ThreadLocal，
            // 不显式传递则扇出的每一条日志都没有 traceId，而这正是最需要按链路排查的部分
            String traceId = TraceIdHolder.get();
            jobs.add(
                    () -> {
                        TraceIdHolder.set(traceId);
                        try {
                            return grantOneProvider(order, provider, groups.get(provider));
                        } finally {
                            TraceIdHolder.clear();
                        }
                    });
        }

        Map<String, RetStatus> results = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RetStatus>> futures =
                    executor.invokeAll(jobs, FANOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                String provider = providers.get(i);
                try {
                    results.put(provider, futures.get(i).get());
                } catch (CancellationException | ExecutionException ex) {
                    // 超时被取消，或子任务抛异常。两者都归 UNKNOWN —— 供应方可能已经执行成功
                    log.warn(
                            "grant fan-out unresolved, bizNo={}, provider={}",
                            order.getPlayBizRecordNo(),
                            provider,
                            ex);
                    results.put(provider, RetStatus.UNKNOWN);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 恢复中断标志后按未定处置：已发出的调用由查单任务兜底，
            // 而 finishGrant 会为每一个未定项落一条 QUERY_GRANT
            log.warn("grant fan-out interrupted, bizNo={}", order.getPlayBizRecordNo(), e);
            for (String provider : providers) {
                results.putIfAbsent(provider, RetStatus.UNKNOWN);
            }
        }
        return results;
    }

    /** 一个供应方一次调用，幂等键确定性派生。 */
    private RetStatus grantOneProvider(
            PlayBizRecord order, String providerType, List<SnapshotItem> items) {
        String bizNo = order.getPlayBizRecordNo();
        String grantOpNo = IdempotentKeys.grantOpNo(bizNo, providerType);

        GrantRewardReq req = new GrantRewardReq();
        req.setPlayType("BENEFIT_SELL");
        req.setActivityId(order.getActivityId());
        req.setBizOrderNo(bizNo);
        req.setOpNo(grantOpNo);
        req.setReceiverId(order.getUserId());

        List<RewardItem> rewardItems = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            SnapshotItem s = items.get(i);
            RewardItem ri = new RewardItem();
            // 组内下标从 0 起：uk_op_item(op_no, item_seq) 的第一维已隔开不同组
            ri.setItemSeq(i);
            ri.setRewardType(s.benefitType());
            ri.setProviderType(s.providerType());
            ri.setProviderProductId(s.providerProductId());
            ri.setQty(1);
            ri.setCore(s.core());
            rewardItems.add(ri);
        }
        req.setRewardItems(rewardItems);

        GrantRewardResp resp = rewardService.grantReward(req);

        // 履约明细走 upsert：重入时更新为最新结果，不新增行（uk_biz_item）。
        // 明细态与下游四分类一一对应，不再压成「成功/失败」两值 —— UNKNOWN 压成 FAILED
        // 会让这一行看起来是「确定没发」，而查单收敛正要从这些行里找出待查的项
        ItemGrantStatus itemStatus = toItemStatus(resp.getRetStatus());
        for (int i = 0; i < items.size(); i++) {
            SnapshotItem s = items.get(i);
            String providerOrderNo =
                    resp.getItems() != null && i < resp.getItems().size()
                            ? resp.getItems().get(i).getProviderOrderNo()
                            : null;
            fulfillmentMapper.upsert(
                    BizNoGenerator.fulfillmentNo(),
                    bizNo,
                    s.benefitItemId(),
                    s.providerType(),
                    s.providerProductId(),
                    providerOrderNo,
                    grantOpNo,
                    itemStatus.name());
        }
        return resp.getRetStatus();
    }

    /**
     * 下游四分类 → 履约明细态。
     *
     * <p>两个枚举刻意不同名（{@code RetStatus.FAIL} 对 {@code ItemGrantStatus.FAILED}），此处是唯一 的转换点。{@code
     * PROCESSING} 与 {@code UNKNOWN} 都落到明细的未定态，由查单收敛。
     */
    private static ItemGrantStatus toItemStatus(RetStatus downstream) {
        return switch (downstream) {
            case SUCCESS -> ItemGrantStatus.SUCCESS;
            case FAIL -> ItemGrantStatus.FAILED;
            case PROCESSING -> ItemGrantStatus.GRANTING;
            case UNKNOWN -> ItemGrantStatus.UNKNOWN;
        };
    }

    // ------------------------------------------------------------------
    // ④ 退款准入与权益回收（V3 PR-7）
    // ------------------------------------------------------------------

    /**
     * 退款准入 + 权益回收（FR-B08、BR-B-30）。
     *
     * <p>五态分流见接口注释。<b>核心判据是「发放结果是否确定」，不是「是否成功」</b>。
     *
     * <p>组件序：同步使用态 → 五态准入 → 进 {@code REVOKING} → 调 reward 回收 → 按四分类分流。
     */
    @Override
    public RevokeAdmitResp revokeAndAdmit(RevokeAdmitReq req) {
        ReqFields.required(req.getBizNo(), "bizNo");
        // refundReqNo 是两把幂等键的唯一来源，缺了它键会退化成 bizNo + "_V_null"，
        // 同一单的第二次退款请求派生出同一把键 —— 唯一索引把它当成重传吞掉
        ReqFields.required(req.getRefundReqNo(), "refundReqNo");

        return bizLock.aroundRefund(req.getBizNo(), () -> doRevokeAndAdmit(req));
    }

    private RevokeAdmitResp doRevokeAndAdmit(RevokeAdmitReq req) {
        String bizNo = req.getBizNo();
        PlayBizRecord order = findByBizNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }

        // ① 未支付的单没有钱可退。放在最前 —— 它比发放态更基本
        if (!PayStatus.PAY_SUCCESS.name().equals(order.getPayStatus())) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单未支付，无款可退: " + order.getPayStatus());
        }

        // ①' 实付金额未知则不受理。**必须挡在回收之前**：回收发生在退款之前且不可逆，
        // 若放到 createRefund 才拒绝，权益已经收走而钱退不出去 —— 用户既没权益也没钱，
        // 正是本链路要防的那一类。pay_amount 可空是 applyPaidAfterClosing 有意留下的
        // （支付方只答了「已收款」，没说收了多少），故这条路径真实存在，不是理论情形
        requirePayAmount(order);

        // ② 幂等命中：这一笔退款请求已受理过，返回原结果。
        //
        // 判据必须是「同一个 refundReqNo」，不能只看主单是否已在退款中。首版写成
        // 「refund_status 已是 REFUNDING/REFUND_SUCCESS 就返回成功」，于是客服换个工单号
        // 再点一次，会拿到 admitted=true —— 而那笔退款根本没发生。钱没多退（后续闸挡住了），
        // 但调用方据此以为受理成功，工单被误标为已处理，用户的第二次诉求就此消失
        String revokeNo = IdempotentKeys.revokeNo(bizNo, req.getRefundReqNo());
        RefundStatus refundStatus = RefundStatus.valueOf(order.getRefundStatus());
        if (refundStatus == RefundStatus.REFUND_SUCCESS || refundStatus == RefundStatus.REFUNDING) {
            if (opRecordMapper.selectByIdempotentKey(revokeNo) != null) {
                log.info("revokeAndAdmit idempotent hit, bizNo={}, revokeNo={}", bizNo, revokeNo);
                return admitted(RetStatus.SUCCESS, revokeNo);
            }
            // 另一笔退款请求正在处理或已完成 —— 本单至多一笔有效退款（BR-B-31）
            log.info(
                    "revokeAndAdmit rejected, another refund in progress, bizNo={}, status={}",
                    bizNo,
                    refundStatus);
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "本单已有退款在处理中: " + refundStatus);
        }

        // ③ 五态准入判定（技术方案 §7.5）
        GrantStatus grantStatus = GrantStatus.valueOf(order.getGrantStatus());
        if (grantStatus == GrantStatus.GRANTING || grantStatus == GrantStatus.GRANT_UNKNOWN) {
            // 结果未定：回收对象不明。等查单收敛后再判 —— 此刻既不能回收也不能退
            log.info(
                    "revokeAndAdmit rejected, grant not settled, bizNo={}, grant={}",
                    bizNo,
                    grantStatus);
            throw new BizException(ErrorCode.GRANT_NOT_SETTLED, "发放结果未定，暂不受理退款: " + grantStatus);
        }

        // ④ 进 REVOKING + 落操作记录，同事务。三道闸的第一、二道在此
        if (!tx.admitRefund(order, revokeNo, req.getOperator())) {
            // 条件更新未命中：状态非法或并发已推进。回查当前态给出准确的拒绝原因
            PlayBizRecord latest = findByBizNo(bizNo);
            String current = latest == null ? "?" : latest.getRefundStatus();
            log.info("revokeAndAdmit not admitted by state gate, bizNo={}, now={}", bizNo, current);
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "退款状态不允许受理: " + current);
        }

        // ⑤ NOT_START / GRANT_FAILED：无权益在外，不必回收。
        //
        // 这一支是准入表里最容易写错的地方：写成「未发放成功就不许退款」会把这两类单永久
        // 锁死 —— 而「已支付未履约」正是对账要自动补偿的头号场景，退不了款则收敛率破防
        if (grantStatus == GrantStatus.NOT_START || grantStatus == GrantStatus.GRANT_FAILED) {
            // 无权益在外，没有明细需要留痕 —— 传空列表而非造一条假的回收记录
            tx.settleRevoke(bizNo, revokeNo, List.of());
            log.info("revokeAndAdmit no revoke needed, bizNo={}, grant={}", bizNo, grantStatus);
            RevokeAdmitResp resp = admitted(RetStatus.SUCCESS, revokeNo);
            resp.setRevokeRequired(false);
            resp.setUsageStatus("NOT_GRANTED");
            return resp;
        }

        // ⑥ GRANT_SUCCESS：先回收后退款
        return revokeGranted(order, revokeNo, req.getRefundReqNo());
    }

    /**
     * 回收已发放的权益，按四分类分流。
     *
     * <p><b>逐笔发奖各回收一次</b>：一单可能跨多个供应方、有多条明细，每条各有自己的 {@code grantOpNo}。 回收键在其后缀上再分一层 —— 与 {@code
     * grantOpNo} 的「一次调用 = 一个供应方」粒度对齐。
     *
     * <p><b>汇总取最不确定的那一项</b>，与 {@code reward} 侧的 {@code summarize} 同一判据：只要有一项 未定，整笔回收就未收敛 —— 汇总成
     * {@code SUCCESS} 会让退款在权益可能还在的情况下推进。
     */
    private RevokeAdmitResp revokeGranted(
            PlayBizRecord order, String revokeNo, String refundReqNo) {
        String bizNo = order.getPlayBizRecordNo();
        List<BenefitFulfillmentRecord> granted = fulfillmentMapper.selectGranted(bizNo);
        if (granted.isEmpty()) {
            // grant_status 是 GRANT_SUCCESS 但没有成功明细 —— 数据不一致，交人工。
            // 不静默当作「无需回收」：那会退掉一笔可能已发放的单
            log.error("revokeAndAdmit found no granted item while GRANT_SUCCESS, bizNo={}", bizNo);
            tx.unresolvedRevoke(bizNo, revokeNo, RetStatus.UNKNOWN, List.of());
            return unresolved(revokeNo, null);
        }

        boolean anyUnresolved = false;
        boolean anyFail = false;
        // 逐项各自记录：整笔的汇总结果决定主单状态，不决定哪几项真的被收走了
        String usageStatus = null;
        List<RevokedItem> revoked = new ArrayList<>();
        for (BenefitFulfillmentRecord item : granted) {
            RevokeRewardReq rr = new RevokeRewardReq();
            // 每条明细一把回收键，粒度与 grantOpNo 对齐（一次发放调用 = 一次回收调用）。
            //
            // 由三段直接拼出，不接 revokeNo + grantOpNo 两把完整的键 —— 首版那样写，
            // 实测 90+ 字符溢出 VARCHAR(64)，而插入异常被「异常一律 UNKNOWN」捕获，
            // 表现与供应方超时完全一样：主单进 REVOKING、落 REVOKE 任务、回报结果未定，
            // 而回收请求根本没发出去
            rr.setRevokeNo(IdempotentKeys.revokeItemNo(bizNo, refundReqNo, item.getProviderType()));
            rr.setBizOrderNo(bizNo);
            rr.setOpNo(item.getGrantOpNo());
            rr.setReceiverId(order.getUserId());

            RetStatus one;
            String itemUsage = null;
            try {
                RevokeRewardResp rs = rewardService.revokeReward(rr);
                one = rs.getRetStatus();
                itemUsage = rs.getUsageStatus();
            } catch (Exception e) {
                // 异常映射 UNKNOWN 而非 FAIL：权益可能已被收走。判 FAIL 会让人工按
                // 「权益还在」处置，而据此拒绝退款则用户既没权益也没钱
                log.warn("revokeReward threw, treat as UNKNOWN, revokeNo={}", revokeNo, e);
                one = RetStatus.UNKNOWN;
            }

            // 汇总取最阻断的那一项，不取最后一项。逐条覆盖的写法会让汇总值取决于
            // selectGranted 的返回顺序 —— 而 reasonCode 正按它在 1752/1753 之间选码，
            // 「已核销」这个确定原因会被后面一项的 REVOKED 盖掉，人工据此以为只是没收成
            usageStatus = mergeUsage(usageStatus, itemUsage);

            if (one == RetStatus.UNKNOWN || one == RetStatus.PROCESSING) {
                anyUnresolved = true;
            } else if (one == RetStatus.FAIL) {
                anyFail = true;
            } else {
                // 确实收走了这一件，无论整笔汇总成什么都要留痕
                revoked.add(new RevokedItem(item.getGrantOpNo(), itemUsage));
            }
        }

        if (anyUnresolved) {
            // 未定：主单保持 REVOKING，落 REVOKE 任务由收敛处置，不推进退款
            tx.unresolvedRevoke(bizNo, revokeNo, RetStatus.UNKNOWN, revoked);
            log.info("revokeAndAdmit unresolved, keep REVOKING, bizNo={}", bizNo);
            return unresolved(revokeNo, usageStatus);
        }
        if (anyFail) {
            // 确定失败（多为已核销）：权益还在，不得退款。可人工重试重入
            tx.failRevoke(bizNo, revokeNo, revoked);
            log.info("revokeAndAdmit revoke failed, bizNo={}, usage={}", bizNo, usageStatus);
            RevokeAdmitResp resp = new RevokeAdmitResp();
            resp.setAdmitted(false);
            resp.setRetStatus(RetStatus.FAIL);
            resp.setReasonCode(
                    "USED".equals(usageStatus) || "PARTIALLY_USED".equals(usageStatus)
                            ? ErrorCode.BENEFIT_ALREADY_USED
                            : ErrorCode.REVOKE_NOT_DONE);
            resp.setRevokeNo(revokeNo);
            resp.setUsageStatus(usageStatus);
            resp.setRevokeRequired(true);
            return resp;
        }

        // 全部回收成功：落回收单号与回收时间，主单停在 REVOKING 等 createRefund 推进
        tx.settleRevoke(bizNo, revokeNo, revoked);
        log.info("revokeAndAdmit revoked, bizNo={}, revokeNo={}", bizNo, revokeNo);
        RevokeAdmitResp resp = admitted(RetStatus.SUCCESS, revokeNo);
        resp.setUsageStatus(usageStatus);
        resp.setRevokeRequired(true);
        return resp;
    }

    /**
     * 汇总多个供应方回传的使用态，<b>取最阻断的那一项</b>。
     *
     * <p>与 {@code retStatus} 的汇总同一判据：一单跨多个供应方时，只要有一件收不回来，整笔就收不 回来 —— 而 {@code reasonCode} 正按这个值在
     * {@code 1752}（已核销，确定收不回）与 {@code 1753}（回收 未完成）之间选码。
     *
     * <p><b>原先是逐条覆盖</b>，于是汇总值取决于 {@code selectGranted} 的返回顺序：A 已核销、B 回收成功 时最终留下 {@code
     * REVOKED}，调用方看到「失败，但券没被用过」，与事实相反。人工据此会当成 一次普通的回收出错去重试，而它永远不会成功。
     *
     * <p>优先级：{@code USED} / {@code PARTIALLY_USED} / {@code EXPIRED}（确定收不回）＞ {@code UNKNOWN}（不知道） ＞
     * 其余（{@code REVOKED} / {@code UNUSED}）。
     */
    private static String mergeUsage(String current, String candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return usageRank(candidate) > usageRank(current) ? candidate : current;
    }

    /** 阻断程度，数值越大越阻断。未知的取值按 0 处理 —— 新增取值不会意外顶掉已核销。 */
    private static int usageRank(String usageStatus) {
        return switch (usageStatus) {
            case "USED", "PARTIALLY_USED", "EXPIRED" -> 2;
            case "UNKNOWN" -> 1;
            default -> 0;
        };
    }

    /**
     * 要求实付金额已知。
     *
     * <p><b>不以 {@code order_amount} 代入</b>：实付 ≠ 应付时按应付退是多退或少退，而多退一笔钱要走 人工追讨。这与 {@code
     * advanceToPaySuccess} 拒绝以应付额填充 {@code pay_amount} 是同一条理由 —— 那里不写，这里也不该猜。
     *
     * <p><b>抛 {@code 1754} 而非拆箱 NPE</b>：`callPayRefund` 把任何异常映射为 {@code UNKNOWN}，NPE 落进去
     * 后的表现与支付方超时完全一样 —— 主单进 {@code REFUNDING}、落查单任务、回报「结果未定」，而退款 请求根本没发出去。且查单收敛救不回来：支付方查无该单恒答
     * {@code UNKNOWN}，重试至死信后这笔 单永停 {@code REFUNDING}。与 PR-7 的键长溢出同族，形态更隐蔽。
     */
    private static void requirePayAmount(PlayBizRecord order) {
        if (order.getPayAmount() == null) {
            throw new BizException(
                    ErrorCode.PAY_AMOUNT_UNKNOWN, "实付金额未知，无法退款: " + order.getPlayBizRecordNo());
        }
    }

    private static RevokeAdmitResp admitted(RetStatus status, String revokeNo) {
        RevokeAdmitResp resp = new RevokeAdmitResp();
        resp.setAdmitted(true);
        resp.setRetStatus(status);
        resp.setRevokeNo(revokeNo);
        return resp;
    }

    /**
     * 回收结果未定：<b>准入通过但不得退款</b>。
     *
     * <p>{@code admitted=true} 与 {@code retStatus=UNKNOWN} 的组合表示「这单可以退，但现在还不能退」 —— 与 {@code
     * admitted=false}（这单不能退）是两回事。调用方据此等待收敛而非提示用户失败。
     */
    private static RevokeAdmitResp unresolved(String revokeNo, String usageStatus) {
        RevokeAdmitResp resp = new RevokeAdmitResp();
        resp.setAdmitted(true);
        resp.setRetStatus(RetStatus.UNKNOWN);
        resp.setReasonCode(ErrorCode.REVOKE_NOT_DONE);
        resp.setRevokeNo(revokeNo);
        resp.setUsageStatus(usageStatus);
        resp.setRevokeRequired(true);
        return resp;
    }

    // ------------------------------------------------------------------
    // ⑤ 退款执行与收敛（V3 PR-8）
    // ------------------------------------------------------------------

    /**
     * 退款执行（FR-B08）。<b>必须在 {@code revokeAndAdmit} 之后</b>。
     *
     * <p>「先回收后退款」这条顺序由 {@code startRefund} 的前置态谓词强制：{@code REVOKING} 只能由 准入置入，绕过准入直接调本方法会命中 0
     * 行（技术方案 §5.6，顺序不可颠倒）。
     */
    @Override
    public RetStatus createRefund(String bizNo, String refundReqNo) {
        ReqFields.required(bizNo, "bizNo");
        ReqFields.required(refundReqNo, "refundReqNo");
        return bizLock.aroundRefund(bizNo, () -> doCreateRefund(bizNo, refundReqNo));
    }

    private RetStatus doCreateRefund(String bizNo, String refundReqNo) {
        PlayBizRecord order = findByBizNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }

        String refundNo = IdempotentKeys.refundNo(bizNo, refundReqNo);
        RefundStatus current = RefundStatus.valueOf(order.getRefundStatus());
        if (current == RefundStatus.REFUND_SUCCESS || current == RefundStatus.REFUNDING) {
            // 幂等命中的判据是「同一个 refundReqNo」，不是「这单已在退款中」。
            //
            // 只看状态的话，客服换个工单号再点一次会拿到 SUCCESS —— 而那笔退款根本没发生。
            // 钱不会多退（三道闸挡得住），但工单被误标为已处理，用户的第二次诉求就此消失
            if (refundNo.equals(order.getRefundNo())) {
                log.info("createRefund idempotent hit, bizNo={}, refundNo={}", bizNo, refundNo);
                return current == RefundStatus.REFUND_SUCCESS
                        ? RetStatus.SUCCESS
                        : RetStatus.UNKNOWN;
            }
            // 另一笔退款请求已占用本单（BR-B-31 一单至多一笔有效退款）
            log.info("createRefund rejected, another refund holds this order, bizNo={}", bizNo);
            throw new BizException(
                    ErrorCode.CONCURRENT_CONFLICT, "本单已有其他退款请求: " + order.getRefundNo());
        }
        if (current != RefundStatus.REVOKING) {
            // 未经准入或回收未完成。REVOKE_FAILED / NONE 都落在这里 —— 前者权益还在，
            // 后者根本没走过准入。两者都不允许退款
            log.info("createRefund rejected, refund status={}, bizNo={}", current, bizNo);
            throw new BizException(ErrorCode.REVOKE_NOT_DONE, "回收未完成，不得退款: " + current);
        }

        // 金额未知则不受理。准入侧已挡过一道，此处再挡是因为**本方法才是真正解引用它的地方**：
        // 准入那道保证「回收不会白做」，这道保证「不会拆箱成 NPE 再被映射成 UNKNOWN」。
        // 两道各自可被单独调用（本方法是公开接口，不强制经过准入），故都要有
        requirePayAmount(order);

        // 三道闸的第一、二道：条件更新挡状态非法与并发，uk_biz_op 挡「两个 refundNo 退两次」
        if (!tx.startRefund(order, refundNo)) {
            log.info("createRefund not admitted by state gate, bizNo={}", bizNo);
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "退款状态不允许受理");
        }

        return callPayRefund(order, refundNo);
    }

    /**
     * 调支付方退款，按四分类分流。
     *
     * <p><b>异常映射 {@code UNKNOWN} 而非 {@code FAIL}</b>：钱可能已经退出去了。判 {@code FAIL} 会让人工 按「没退成」处置并重发 ——
     * 那就是重复退款，而这条链路的目标正是「重复退款 = 0」。
     */
    private RetStatus callPayRefund(PlayBizRecord order, String refundNo) {
        String bizNo = order.getPlayBizRecordNo();
        RetStatus result;
        try {
            PayRefundResp resp = mockPayService.refund(bizNo, refundNo, order.getPayAmount());
            result = resp.getRetStatus();
        } catch (Exception e) {
            log.warn("pay refund threw, treat as UNKNOWN, refundNo={}", refundNo, e);
            result = RetStatus.UNKNOWN;
        }

        switch (result) {
            case SUCCESS -> {
                tx.settleRefund(bizNo);
                log.info("createRefund done, bizNo={}, refundNo={}", bizNo, refundNo);
            }
            case FAIL -> {
                tx.failRefund(bizNo);
                log.warn("createRefund failed, bizNo={}, refundNo={}", bizNo, refundNo);
            }
            default -> {
                // UNKNOWN / PROCESSING：主单保持 REFUNDING，落查单任务由收敛处置。
                // 不重发 —— 重发一笔可能已成功的退款就是重复退款
                tx.unresolvedRefund(bizNo, refundNo, result);
                log.info("createRefund unresolved, keep REFUNDING, bizNo={}", bizNo);
            }
        }
        return result;
    }

    /**
     * 收敛退款 {@code UNKNOWN}：按原 {@code refundNo} 查单。
     *
     * <p><b>查单而非重发</b>：这是「重复退款 = 0」与「该退必退」能同时成立的唯一形态 —— 查单是读 操作，无论问多少次都不会多退一分钱。
     */
    @Override
    public RetStatus reconcileRefund(String bizNo) {
        PlayBizRecord order = findByBizNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }
        String refundNo = order.getRefundNo();
        if (refundNo == null) {
            // 没有退款单号却在收敛退款 —— 数据不一致，交人工而非猜一个号
            log.error("reconcileRefund found no refundNo, bizNo={}", bizNo);
            return RetStatus.UNKNOWN;
        }

        RetStatus result;
        try {
            result = mockPayService.queryRefund(refundNo).getRetStatus();
        } catch (Exception e) {
            log.warn("query refund threw, treat as UNKNOWN, refundNo={}", refundNo, e);
            result = RetStatus.UNKNOWN;
        }

        switch (result) {
            case SUCCESS -> {
                tx.settleRefund(bizNo);
                log.info("reconcileRefund converged to SUCCESS, bizNo={}", bizNo);
            }
            case FAIL -> {
                tx.failRefund(bizNo);
                log.info("reconcileRefund converged to FAIL, bizNo={}", bizNo);
            }
            default -> log.info("reconcileRefund unresolved, keep querying, bizNo={}", bizNo);
        }
        return result;
    }

    /**
     * 收敛回收 {@code UNKNOWN}：按原 {@code revokeNo} 重问供应方。
     *
     * <p><b>收敛为成功后主单停在 {@code REVOKING}，不自动发起退款</b>：回收与退款是两个独立的决定，
     * 由调用方（或人工处置）显式推进。自动串起来会让一次回收收敛顺带把钱退出去 —— 而此刻可能 已有别的处置在进行。
     */
    @Override
    public RetStatus reconcileRevoke(String bizNo) {
        PlayBizRecord order = findByBizNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }
        if (!RefundStatus.REVOKING.name().equals(order.getRefundStatus())) {
            // 已被别的路径收敛（人工处置、同步链路）。返回 SUCCESS 让任务了结 ——
            // 它要做的事已经有人做了，继续重试没有对象
            log.info(
                    "reconcileRevoke nothing to do, status={}, bizNo={}",
                    order.getRefundStatus(),
                    bizNo);
            return RetStatus.SUCCESS;
        }

        List<BenefitFulfillmentRecord> granted = fulfillmentMapper.selectGranted(bizNo);
        if (granted.isEmpty()) {
            // 主单说发放成功，明细里却没有一条成功的 —— 数据不一致，与同步链路
            // （revokeGranted 的同名分支）必须给出同一个答案：交人工，不推进。
            //
            // 收敛为 SUCCESS 会让一次回收都没发生的单进入「回收已完成」，而
            // createRefund 的前置态谓词正是 REVOKING —— 于是「先回收后退款」被从
            // 内部绕过，三道闸一道都不会响。同一条规则的两条实现只验了同步那条，
            // 与 §6.6 记的「多层实现只验了外层」是同一族
            log.error("reconcileRevoke found no granted item while REVOKING, bizNo={}", bizNo);
            return RetStatus.UNKNOWN;
        }

        // 回收键的锚点是操作记录，一次取出而非在循环里反复查：它对整单只有一条
        // （op_seq 恒空串），逐项查会得到同一行
        PlayOpRecord op =
                opRecordMapper.selectOne(
                        Wrappers.<PlayOpRecord>lambdaQuery()
                                .eq(PlayOpRecord::getPlayBizRecordNo, bizNo)
                                .eq(PlayOpRecord::getOpType, OpType.REVOKE_BENEFIT.name()));
        if (op == null) {
            log.error("reconcileRevoke found no revoke op record, bizNo={}", bizNo);
            return RetStatus.UNKNOWN;
        }
        String revokeNo = op.getOpNo();

        boolean anyUnresolved = false;
        boolean anyFail = false;
        String usageStatus = null;
        List<RevokedItem> revoked = new ArrayList<>();

        for (BenefitFulfillmentRecord item : granted) {
            // 已落 revoke_no 即该项已回收成功，不再重问 —— 留痕正是「这一项收没收走」的判据
            if (item.getRevokeNo() != null) {
                continue;
            }

            RevokeRewardReq rr = new RevokeRewardReq();
            // 复用与同步链路完全相同的键：收敛的前提是「以原键重问」，派生规则若与
            // 发起侧不一致，下游会把它当成一次新的回收 —— 那就是二次回收
            rr.setRevokeNo(
                    IdempotentKeys.revokeItemNo(
                            bizNo,
                            IdempotentKeys.refundReqNoOfRevoke(revokeNo, bizNo),
                            item.getProviderType()));
            rr.setBizOrderNo(bizNo);
            rr.setOpNo(item.getGrantOpNo());
            rr.setReceiverId(order.getUserId());

            RetStatus one;
            String itemUsage = null;
            try {
                RevokeRewardResp rs = rewardService.revokeReward(rr);
                one = rs.getRetStatus();
                itemUsage = rs.getUsageStatus();
            } catch (Exception e) {
                log.warn("reconcileRevoke call threw, treat as UNKNOWN, bizNo={}", bizNo, e);
                one = RetStatus.UNKNOWN;
            }
            usageStatus = mergeUsage(usageStatus, itemUsage);

            if (one == RetStatus.UNKNOWN || one == RetStatus.PROCESSING) {
                anyUnresolved = true;
            } else if (one == RetStatus.FAIL) {
                anyFail = true;
            } else {
                revoked.add(new RevokedItem(item.getGrantOpNo(), itemUsage));
            }
        }

        if (anyUnresolved) {
            // 仍未定：本轮已确定收走的那几项照常留痕，下一轮据此跳过它们，
            // 不会对同一件权益反复重问。主单保持 REVOKING，任务继续重试
            tx.unresolvedRevoke(bizNo, revokeNo, RetStatus.UNKNOWN, revoked);
            log.info("reconcileRevoke still unresolved, bizNo={}", bizNo);
            return RetStatus.UNKNOWN;
        }
        if (anyFail) {
            tx.failRevoke(bizNo, revokeNo, revoked);
            log.info("reconcileRevoke converged to FAIL, bizNo={}", bizNo);
            return RetStatus.SUCCESS;
        }
        tx.settleRevoke(bizNo, revokeNo, revoked);
        log.info("reconcileRevoke converged to SUCCESS, bizNo={}", bizNo);
        return RetStatus.SUCCESS;
    }

    // ------------------------------------------------------------------
    // ⑥ 查询
    // ------------------------------------------------------------------

    @Override
    public QueryOrderResp queryOrder(String bizNo) {
        PlayBizRecord order = findByBizNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }

        List<BenefitFulfillmentRecord> records =
                fulfillmentMapper.selectList(
                        Wrappers.<BenefitFulfillmentRecord>lambdaQuery()
                                .eq(BenefitFulfillmentRecord::getPlayBizRecordNo, bizNo));

        List<FulfillmentItem> items = new ArrayList<>(records.size());
        for (BenefitFulfillmentRecord r : records) {
            FulfillmentItem f = new FulfillmentItem();
            f.setFulfillmentNo(r.getFulfillmentNo());
            f.setBenefitItemId(r.getBenefitItemId());
            f.setProviderType(r.getProviderType());
            f.setProviderOrderNo(r.getProviderOrderNo());
            f.setGrantStatus(ItemGrantStatus.valueOf(r.getGrantStatus()));
            items.add(f);
        }

        QueryOrderResp resp = new QueryOrderResp();
        resp.setBizNo(order.getPlayBizRecordNo());
        resp.setUserId(order.getUserId());
        resp.setActivityId(order.getActivityId());
        resp.setSkuId(order.getSkuId());
        // 三子状态各自返回，不派生成单一 biz_status
        resp.setPayStatus(PayStatus.valueOf(order.getPayStatus()));
        resp.setGrantStatus(GrantStatus.valueOf(order.getGrantStatus()));
        resp.setRefundStatus(RefundStatus.valueOf(order.getRefundStatus()));
        resp.setOrderAmount(order.getOrderAmount());
        resp.setPayAmount(order.getPayAmount());
        resp.setTradeNo(order.getTradeNo());
        resp.setConfigVersion(order.getConfigVersion());
        resp.setFulfillments(items);
        return resp;
    }

    @Override
    public ConvergenceResp queryConvergence(String bizNo) {
        PlayBizRecord order = findByBizNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }

        ConvergenceResp resp = new ConvergenceResp();
        resp.setBizNo(bizNo);
        resp.setPayStatus(order.getPayStatus());
        resp.setGrantStatus(order.getGrantStatus());
        resp.setRefundStatus(order.getRefundStatus());

        List<PlayOpRecord> ops =
                opRecordMapper.selectList(
                        Wrappers.<PlayOpRecord>lambdaQuery()
                                .eq(PlayOpRecord::getPlayBizRecordNo, bizNo)
                                .orderByAsc(PlayOpRecord::getId));
        List<ConvergenceResp.OpRecordSnapshot> opSnaps = new ArrayList<>(ops.size());
        for (PlayOpRecord o : ops) {
            ConvergenceResp.OpRecordSnapshot s = new ConvergenceResp.OpRecordSnapshot();
            s.setOpType(o.getOpType());
            s.setOpSeq(o.getOpSeq());
            // status 与 downstreamResult 分列透出，合并即无法区分 PROCESSING 与 UNKNOWN
            s.setStatus(o.getStatus());
            s.setDownstreamResult(o.getDownstreamResult());
            s.setRetryCount(o.getRetryCount());
            opSnaps.add(s);
        }
        resp.setOpRecords(opSnaps);

        List<BenefitTask> tasks = taskMapper.selectByBizNo(bizNo);
        List<ConvergenceResp.TaskSnapshot> taskSnaps = new ArrayList<>(tasks.size());
        for (BenefitTask t : tasks) {
            ConvergenceResp.TaskSnapshot s = new ConvergenceResp.TaskSnapshot();
            s.setTaskType(t.getTaskType());
            s.setOpNo(t.getOpNo());
            s.setStatus(t.getStatus());
            s.setRetryCount(t.getRetryCount());
            s.setNextTime(str(t.getNextTime()));
            s.setLeaseOwner(t.getLeaseOwner());
            s.setLeaseExpire(str(t.getLeaseExpire()));
            taskSnaps.add(s);
        }
        resp.setTasks(taskSnaps);
        return resp;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static String str(java.time.LocalDateTime t) {
        return t == null ? null : t.toString();
    }

    /**
     * 验凭证：签名、时效、逐字段比对。
     *
     * <p><b>验签之后必须逐字段比对</b>。验签只证明这张凭证由平台签发且未被篡改，不证明它是发给<b>本次 请求</b>的：用户 A 拿到的合法凭证，放进用户 B
     * 的请求里同样验签通过。少比一个字段就漏一类越权 ——
     *
     * <ul>
     *   <li>不比 {@code userId}：可拿他人凭证下单
     *   <li>不比 {@code skuId}：可拿低价商品的凭证买高价商品，且比价这一关照样过 —— 凭证价与它自己那件 商品的重算价本来就相等
     * </ul>
     *
     * <p>{@code packageVersion} 的比对<b>不在本方法内</b>，它需要读 SKU，故与比价一起放在 {@code createTrade} 里 ——
     * 本方法只做「不查库就能判的」那部分。
     *
     * <p>{@code configVersion} 不作为拒绝依据：它是活动级版本，与权益内容无关（后者由 {@code packageVersion}
     * 决定）。活动改版而价格与权益包都未变时，用户看到的承诺确实没变，据此拒绝只会打断下单。
     */
    private ConsultTokenPayload verifyConsultToken(CreateTradeReq req) {
        // 签名与时效不通过时在此抛 4003
        ConsultTokenPayload token = tokenSigner.verify(req.getConsultToken());

        boolean matches =
                token.userId().equals(req.getUserId())
                        && token.activityId().equals(req.getActivityId())
                        && token.skuId().equals(req.getSkuId());
        if (!matches) {
            // 不回显凭证内容：那等于告诉调用方「这张凭证本该配哪个用户和商品」
            log.warn(
                    "createTrade token mismatch, user={}, activity={}, sku={}",
                    req.getUserId(),
                    req.getActivityId(),
                    req.getSkuId());
            throw new BizException(ErrorCode.INVALID_TOKEN, "咨询凭证与请求不一致");
        }
        return token;
    }

    /** 查在售 SKU，不可售即拒。咨询与下单共用，两处判据必须一致。 */
    private BenefitSku requireOnSaleSku(String skuId) {
        BenefitSku sku =
                skuMapper.selectOne(
                        Wrappers.<BenefitSku>lambdaQuery().eq(BenefitSku::getSkuId, skuId));
        if (sku == null || !"ON_SALE".equals(sku.getSaleStatus())) {
            throw new BizException(ErrorCode.INVALID_PARAM, "商品不可售: " + skuId);
        }
        return sku;
    }

    /** 从 sku → package → item 组装快照。履约只读快照，运营改配置不影响存量单。 */
    private List<SnapshotItem> buildSnapshot(BenefitSku sku) {
        List<BenefitItem> items =
                itemMapper.selectList(
                        Wrappers.<BenefitItem>lambdaQuery()
                                .eq(BenefitItem::getBenefitPackageId, sku.getBenefitPackageId())
                                .eq(BenefitItem::getPackageVersion, sku.getPackageVersion())
                                .orderByAsc(BenefitItem::getGrantOrder));
        if (items.isEmpty()) {
            throw new BizException(
                    ErrorCode.INVALID_PARAM, "权益包无权益项: " + sku.getBenefitPackageId());
        }
        List<SnapshotItem> snapshot = new ArrayList<>(items.size());
        for (BenefitItem i : items) {
            snapshot.add(
                    new SnapshotItem(
                            i.getBenefitItemId(),
                            i.getBenefitType(),
                            i.getProviderType(),
                            i.getProviderProductId(),
                            i.getIsCore() != null && i.getIsCore() == 1));
        }
        return snapshot;
    }

    private Map<String, List<SnapshotItem>> groupByProvider(String snapshotJson) {
        Map<String, List<SnapshotItem>> groups = new LinkedHashMap<>();
        for (SnapshotItem i : fromJson(snapshotJson)) {
            groups.computeIfAbsent(i.providerType(), k -> new ArrayList<>()).add(i);
        }
        return groups;
    }

    private PlayBizRecord findByBizNo(String bizNo) {
        return bizRecordMapper.selectOne(
                Wrappers.<PlayBizRecord>lambdaQuery().eq(PlayBizRecord::getPlayBizRecordNo, bizNo));
    }

    private PlayBizRecord findByIdempotent(CreateTradeReq req) {
        return bizRecordMapper.selectOne(
                Wrappers.<PlayBizRecord>lambdaQuery()
                        .eq(PlayBizRecord::getUserId, req.getUserId())
                        .eq(PlayBizRecord::getActivityId, req.getActivityId())
                        .eq(PlayBizRecord::getSkuId, req.getSkuId())
                        .eq(PlayBizRecord::getClientReqNo, req.getClientReqNo()));
    }

    /**
     * 支付通知的 {@code payStatus} → 主单支付态。
     *
     * <p>V2 PR-6 放开 {@code CLOSED}：不放开则「先 SUCCESS 后 CLOSED」的乱序通知在参数校验处即被拒， 执行不到条件更新 ——
     * 而拦截乱序本来就是条件更新的职责，在入口拒掉等于换了个地方失败，且 第二条通知不留痕。
     */
    private static PayStatus parsePayStatus(String raw) {
        return switch (raw) {
            case "SUCCESS" -> PayStatus.PAY_SUCCESS;
            case "FAILED" -> PayStatus.PAY_FAILED;
            case "CLOSED" -> PayStatus.CLOSED;
            default ->
                    throw new BizException(
                            ErrorCode.INVALID_PARAM, "仅接受 SUCCESS / FAILED / CLOSED，实际 " + raw);
        };
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("快照序列化失败", e);
        }
    }

    private static List<SnapshotItem> fromJson(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<SnapshotItem>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("快照反序列化失败", e);
        }
    }

    private static CreateTradeResp toCreateResp(PlayBizRecord r) {
        CreateTradeResp resp = new CreateTradeResp();
        resp.setBizNo(r.getPlayBizRecordNo());
        resp.setTradeNo(r.getTradeNo());
        resp.setPayStatus(r.getPayStatus());
        resp.setOrderAmount(r.getOrderAmount());
        return resp;
    }
}
