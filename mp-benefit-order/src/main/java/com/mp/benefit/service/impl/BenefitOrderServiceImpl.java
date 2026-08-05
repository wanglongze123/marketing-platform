package com.mp.benefit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mp.api.activity.dto.ActivityConfResp;
import com.mp.api.activity.service.ActivityService;
import com.mp.api.benefit.dto.BenefitItemView;
import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.benefit.dto.FulfillmentItem;
import com.mp.api.benefit.dto.OpRecordItem;
import com.mp.api.benefit.dto.OrderListItem;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.benefit.dto.QueryOrderPageReq;
import com.mp.api.benefit.dto.QueryOrderPageResp;
import com.mp.api.benefit.dto.QueryOrderResp;
import com.mp.api.benefit.dto.QuerySkuResp;
import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.api.mock.dto.PayCreateReq;
import com.mp.api.mock.dto.PayCreateResp;
import com.mp.api.mock.service.MockPayService;
import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.api.reward.dto.GrantRewardResp;
import com.mp.api.reward.dto.RewardItem;
import com.mp.api.reward.service.RewardService;
import com.mp.benefit.entity.BenefitFulfillmentRecord;
import com.mp.benefit.entity.BenefitItem;
import com.mp.benefit.entity.BenefitSku;
import com.mp.benefit.entity.PlayBizRecord;
import com.mp.benefit.entity.PlayOpRecord;
import com.mp.benefit.repository.BenefitFulfillmentRecordMapper;
import com.mp.benefit.repository.BenefitItemMapper;
import com.mp.benefit.repository.BenefitSkuMapper;
import com.mp.benefit.repository.PlayBizRecordMapper;
import com.mp.benefit.repository.PlayOpRecordMapper;
import com.mp.benefit.service.OrderTxService;
import com.mp.benefit.service.SnapshotItem;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.ItemGrantStatus;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RefundStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.exception.BizException;
import com.mp.common.util.BizNoGenerator;
import com.mp.common.util.IdempotentKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单号碰撞重试次数。UUIDv7 连撞三次实际不可能，超出即视为库层异常而非碰撞 */
    private static final int BIZ_NO_RETRY = 3;

    /** 列表页每页上限。兜底而非信任调用方传值 —— 不限制时一次大 size 请求即可拖垮库 */
    private static final int MAX_PAGE_SIZE = 100;

    // V0/V1 单进程用 Spring 注入；V3 拆服务后改回 @DubboReference(protocol="tri")
    @Autowired private ActivityService activityService;
    @Autowired private RewardService rewardService;
    @Autowired private MockPayService mockPayService;

    private final OrderTxService tx;
    private final BenefitSkuMapper skuMapper;
    private final BenefitItemMapper itemMapper;
    private final PlayBizRecordMapper bizRecordMapper;
    private final BenefitFulfillmentRecordMapper fulfillmentMapper;

    /** 只读查询用。写路径的操作记录一律经 OrderTxService，不走这里 */
    private final PlayOpRecordMapper opRecordMapper;

    public BenefitOrderServiceImpl(
            OrderTxService tx,
            BenefitSkuMapper skuMapper,
            BenefitItemMapper itemMapper,
            PlayBizRecordMapper bizRecordMapper,
            BenefitFulfillmentRecordMapper fulfillmentMapper,
            PlayOpRecordMapper opRecordMapper) {
        this.tx = tx;
        this.skuMapper = skuMapper;
        this.itemMapper = itemMapper;
        this.bizRecordMapper = bizRecordMapper;
        this.fulfillmentMapper = fulfillmentMapper;
        this.opRecordMapper = opRecordMapper;
    }

    // ------------------------------------------------------------------
    // ① 下单
    // ------------------------------------------------------------------

    @Override
    public CreateTradeResp createTrade(CreateTradeReq req) {
        // V1 冻结 quantity = 1。显式拒绝而非默默忽略 —— 后者会让调用方付一份钱得一份权益且无报错
        if (req.getQuantity() != 1) {
            throw new BizException(
                    ErrorCode.INVALID_PARAM, "V1 仅支持 quantity=1，实际 " + req.getQuantity());
        }

        ActivityConfResp activity = activityService.queryActivityConf(req.getActivityId());
        if (activity == null || !activity.isAvailable()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "活动不可参与: " + req.getActivityId());
        }

        BenefitSku sku =
                skuMapper.selectOne(
                        Wrappers.<BenefitSku>lambdaQuery()
                                .eq(BenefitSku::getSkuId, req.getSkuId()));
        if (sku == null || !"ON_SALE".equals(sku.getSaleStatus())) {
            throw new BizException(ErrorCode.INVALID_PARAM, "商品不可售: " + req.getSkuId());
        }

        List<SnapshotItem> snapshot = buildSnapshot(sku);
        String priceSnapshot =
                toJson(Map.of("listPrice", sku.getListPrice(), "salePrice", sku.getSalePrice()));
        String benefitSnapshot = toJson(snapshot);

        // 读活动当前版本并冻结，而非硬编码 —— 逻辑与终态一致，V1 阶段该值恒为 1
        Insert inserted =
                insertOrder(
                        req,
                        sku.getSalePrice(),
                        activity.getCurVersion(),
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
     */
    private Insert insertOrder(
            CreateTradeReq req,
            long salePrice,
            int configVersion,
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
                                priceSnapshot,
                                benefitSnapshot),
                        false);
            } catch (DuplicateKeyException e) {
                PlayBizRecord existing = findByIdempotent(req);
                if (existing != null) {
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

    @Override
    public RetStatus payCallback(PayCallbackReq req) {
        // 按 outTradeNo（= bizNo）定位，不依赖 trade_no 是否已回填
        String bizNo = req.getOutTradeNo();
        PlayBizRecord order = findByBizNo(bizNo);
        if (order == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }

        // 金额校验：仅验签不够 —— 验签只证明消息来自支付方，不证明金额与本单应付一致。
        // 校验失败不推进任何状态（V2 补对账记录与 P0 告警）
        if (req.getPayAmount() != order.getOrderAmount()
                || !order.getCurrency().equals(req.getCurrency())) {
            log.error(
                    "payCallback amount mismatch, bizNo={}, expect={}, actual={}",
                    bizNo,
                    order.getOrderAmount(),
                    req.getPayAmount());
            throw new BizException(ErrorCode.PAY_AMOUNT_MISMATCH, "支付金额或币种不一致");
        }

        PayStatus target = parsePayStatus(req.getPayStatus());
        boolean advanced = tx.applyPayCallback(req, order, target);

        // 仅推进到 PAY_SUCCESS 时触发履约。事务提交后调用，事务内不发 RPC。
        // V2 改为事务内落 GRANT 任务由调度器驱动 —— 届时删掉这行同步调用。
        if (advanced && target == PayStatus.PAY_SUCCESS) {
            grantBenefit(bizNo);
        }
        return RetStatus.SUCCESS;
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

        // 重入分支：已完成则直接返回，不再调 reward
        if (GrantStatus.GRANT_SUCCESS.name().equals(order.getGrantStatus())) {
            log.info("grantBenefit already done, skip, bizNo={}", bizNo);
            return RetStatus.SUCCESS;
        }

        // 事务 A：置 GRANTING + 落操作记录中间态。
        // 必须先于 RPC —— 否则调用发出后崩溃将没有任何痕迹，而查单收敛以这条记录为锚点
        tx.startGrant(order);

        // 按 provider_type 分组：grantOpNo 粒度是「一次调用 = 一个供应方」，
        // 组合权益跨多供应方时天然拆成 N 次调用、N 个幂等键
        Map<String, List<SnapshotItem>> groups = groupByProvider(order.getBenefitSnapshot());

        boolean allSuccess = true;
        for (Map.Entry<String, List<SnapshotItem>> g : groups.entrySet()) {
            if (grantOneProvider(order, g.getKey(), g.getValue()) != RetStatus.SUCCESS) {
                allSuccess = false;
            }
        }

        // 事务 B：回写终态
        GrantStatus target = allSuccess ? GrantStatus.GRANT_SUCCESS : GrantStatus.GRANT_FAILED;
        tx.finishGrant(bizNo, target);

        log.info("grantBenefit done, bizNo={}, groups={}, result={}", bizNo, groups.size(), target);
        return allSuccess ? RetStatus.SUCCESS : RetStatus.FAIL;
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

        // 履约明细走 upsert：重入时更新为最新结果，不新增行（uk_biz_item）
        boolean ok = resp.getRetStatus() == RetStatus.SUCCESS;
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
                    (ok ? ItemGrantStatus.SUCCESS : ItemGrantStatus.FAILED).name());
        }
        return resp.getRetStatus();
    }

    // ------------------------------------------------------------------
    // ④ 查询
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

    // ------------------------------------------------------------------
    // ⑤ 只读查询
    //
    // 无副作用：不写状态、不落操作记录、不调下游，故不经 OrderTxService。
    // 加这些接口不改变《分阶段方案》§4.6 的「活动/SKU 管理接口范围外」——
    // 那条约束配置写入，此处只读。
    // ------------------------------------------------------------------

    @Override
    public QueryOrderPageResp queryOrderPage(QueryOrderPageReq req) {
        // 状态取值先校验再进 SQL：非法值直接拒绝而非当作「查不到」返回空列表 ——
        // 后者会让端侧把拼错的枚举名误读成「该状态下没有订单」
        PayStatus payStatus = parseOrNull(req.getPayStatus(), PayStatus.class, "payStatus");
        GrantStatus grantStatus =
                parseOrNull(req.getGrantStatus(), GrantStatus.class, "grantStatus");

        int page = Math.max(req.getPage(), 1);
        // 上限兜底：不限制时一次 size=100000 的请求可拖垮库
        int size = Math.min(Math.max(req.getSize(), 1), MAX_PAGE_SIZE);

        LambdaQueryWrapper<PlayBizRecord> q =
                Wrappers.<PlayBizRecord>lambdaQuery()
                        .eq(hasText(req.getUserId()), PlayBizRecord::getUserId, req.getUserId())
                        .eq(
                                hasText(req.getActivityId()),
                                PlayBizRecord::getActivityId,
                                req.getActivityId())
                        .eq(payStatus != null, PlayBizRecord::getPayStatus, name(payStatus))
                        .eq(grantStatus != null, PlayBizRecord::getGrantStatus, name(grantStatus))
                        // 与 idx_user(user_id, create_time) 的排序方向一致
                        .orderByDesc(PlayBizRecord::getCreateTime)
                        .orderByDesc(PlayBizRecord::getId);

        Page<PlayBizRecord> result = bizRecordMapper.selectPage(Page.of(page, size), q);

        List<OrderListItem> items = new ArrayList<>(result.getRecords().size());
        for (PlayBizRecord r : result.getRecords()) {
            OrderListItem item = new OrderListItem();
            item.setBizNo(r.getPlayBizRecordNo());
            item.setSkuId(r.getSkuId());
            item.setActivityId(r.getActivityId());
            item.setPayStatus(PayStatus.valueOf(r.getPayStatus()));
            item.setGrantStatus(GrantStatus.valueOf(r.getGrantStatus()));
            item.setRefundStatus(RefundStatus.valueOf(r.getRefundStatus()));
            item.setOrderAmount(r.getOrderAmount());
            item.setPayAmount(r.getPayAmount());
            item.setTradeNo(r.getTradeNo());
            item.setCreateTime(r.getCreateTime());
            items.add(item);
        }

        QueryOrderPageResp resp = new QueryOrderPageResp();
        resp.setItems(items);
        resp.setTotal(result.getTotal());
        resp.setPage(page);
        resp.setSize(size);
        return resp;
    }

    @Override
    public QuerySkuResp querySku(String skuId) {
        if (!hasText(skuId)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "skuId 不能为空");
        }
        BenefitSku sku =
                skuMapper.selectOne(
                        Wrappers.<BenefitSku>lambdaQuery().eq(BenefitSku::getSkuId, skuId));
        if (sku == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "商品不存在: " + skuId);
        }

        // 不筛 sale_status：下架商品也要能查详情 —— 历史订单详情页需要展示它卖的是什么。
        // 是否允许下单由 createTrade 判定，不在查询接口这里拦
        List<BenefitItem> items =
                itemMapper.selectList(
                        Wrappers.<BenefitItem>lambdaQuery()
                                .eq(BenefitItem::getBenefitPackageId, sku.getBenefitPackageId())
                                .eq(BenefitItem::getPackageVersion, sku.getPackageVersion())
                                .orderByAsc(BenefitItem::getGrantOrder));

        List<BenefitItemView> views = new ArrayList<>(items.size());
        for (BenefitItem i : items) {
            BenefitItemView v = new BenefitItemView();
            v.setBenefitItemId(i.getBenefitItemId());
            v.setBenefitType(i.getBenefitType());
            v.setProviderType(i.getProviderType());
            v.setProviderProductId(i.getProviderProductId());
            v.setCore(i.getIsCore() != null && i.getIsCore() == 1);
            v.setGrantOrder(i.getGrantOrder());
            views.add(v);
        }

        QuerySkuResp resp = new QuerySkuResp();
        resp.setSkuId(sku.getSkuId());
        resp.setActivityId(sku.getActivityId());
        resp.setSkuName(sku.getSkuName());
        resp.setSkuType(sku.getSkuType());
        resp.setSaleStatus(sku.getSaleStatus());
        resp.setListPrice(sku.getListPrice());
        resp.setSalePrice(sku.getSalePrice());
        resp.setBenefitPackageId(sku.getBenefitPackageId());
        resp.setPackageVersion(sku.getPackageVersion());
        resp.setItems(views);
        return resp;
    }

    @Override
    public List<OpRecordItem> queryOpRecords(String bizNo) {
        // 单不存在时抛错而非回空列表：空列表无法区分「单不存在」与「单存在但无操作记录」，
        // 而后者本身就是异常信号（建单必落 CREATE_TRADE 记录），排查时不能被掩盖
        if (findByBizNo(bizNo) == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单不存在: " + bizNo);
        }

        List<PlayOpRecord> records =
                opRecordMapper.selectList(
                        Wrappers.<PlayOpRecord>lambdaQuery()
                                .eq(PlayOpRecord::getPlayBizRecordNo, bizNo)
                                .orderByAsc(PlayOpRecord::getCreateTime)
                                .orderByAsc(PlayOpRecord::getId));

        List<OpRecordItem> items = new ArrayList<>(records.size());
        for (PlayOpRecord r : records) {
            OpRecordItem item = new OpRecordItem();
            item.setOpNo(r.getOpNo());
            item.setOpType(r.getOpType());
            item.setOpSeq(r.getOpSeq());
            // status 与 downstreamResult 分列回显，不合并
            item.setStatus(r.getStatus());
            item.setDownstreamResult(r.getDownstreamResult());
            item.setRetryCount(r.getRetryCount());
            item.setErrorCode(r.getErrorCode());
            item.setOutOrderNo(r.getOutOrderNo());
            item.setParentOpNo(r.getParentOpNo());
            item.setCreateTime(r.getCreateTime());
            item.setFinishTime(r.getFinishTime());
            items.add(item);
        }
        return items;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    /** 空值放过（不筛该项），非法值拒绝。 */
    private static <E extends Enum<E>> E parseOrNull(String raw, Class<E> type, String field) {
        if (!hasText(raw)) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 取值非法: " + raw);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

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

    private static PayStatus parsePayStatus(String raw) {
        // V1 只接受 SUCCESS / FAILED。CLOSED 是 V2 关单链路的语义，此处显式拒绝 ——
        // 提前写的分支没有测试覆盖，且 V2 引入 CLOSING 中间态后逻辑还要重写
        return switch (raw) {
            case "SUCCESS" -> PayStatus.PAY_SUCCESS;
            case "FAILED" -> PayStatus.PAY_FAILED;
            default ->
                    throw new BizException(
                            ErrorCode.INVALID_PARAM, "V1 仅接受 SUCCESS / FAILED，实际 " + raw);
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
