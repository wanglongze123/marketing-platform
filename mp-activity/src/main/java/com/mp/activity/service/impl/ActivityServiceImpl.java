package com.mp.activity.service.impl;

import com.mp.activity.entity.MarketingActivity;
import com.mp.activity.repository.ActivityOpRecordMapper;
import com.mp.activity.repository.MarketingActivityMapper;
import com.mp.activity.service.ActivityTxService;
import com.mp.activity.service.PublishChecker;
import com.mp.api.activity.dto.ActivityConfResp;
import com.mp.api.activity.dto.CreateActivityReq;
import com.mp.api.activity.dto.PublishActivityResp;
import com.mp.api.activity.dto.QualifyReq;
import com.mp.api.activity.dto.QualifyResp;
import com.mp.api.activity.service.ActivityService;
import com.mp.common.enums.ActivityStatus;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.QualifyReason;
import com.mp.common.exception.BizException;
import com.mp.common.util.BizNoGenerator;
import java.util.List;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 活动配置与资格决策。
 *
 * <p>V1 只有 {@code queryActivityConf} 一个只读方法，V3 补齐创建、发布、状态变更与资格决策 —— 裂变进场（{@code
 * FissionSponsorQuery}）的前两步就是「按场景索引候选活动」与「执行资格校验」， 无此二者则裂变活动无从创建、进场校验只能写死返回 true。
 *
 * <p><b>本类不带 {@code @Transactional}</b>：事务边界在 {@link ActivityTxService}，两者分属不同 bean。
 * 同类内部调用的注解不经代理、不生效、不报错（V1 缺陷 ①）。
 */
@DubboService
@Service
public class ActivityServiceImpl implements ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityServiceImpl.class);

    /** 单号碰撞后的换号重试上限 */
    private static final int ID_RETRY_LIMIT = 3;

    private final MarketingActivityMapper activityMapper;
    private final ActivityOpRecordMapper opRecordMapper;
    private final ActivityTxService tx;

    public ActivityServiceImpl(
            MarketingActivityMapper activityMapper,
            ActivityOpRecordMapper opRecordMapper,
            ActivityTxService tx) {
        this.activityMapper = activityMapper;
        this.opRecordMapper = opRecordMapper;
        this.tx = tx;
    }

    @Override
    public ActivityConfResp queryActivityConf(String activityId) {
        MarketingActivityMapper.ActivityRow a = activityMapper.selectWithAvailability(activityId);
        if (a == null) {
            return null;
        }

        ActivityConfResp resp = new ActivityConfResp();
        resp.setActivityId(a.getActivityId());
        resp.setName(a.getName());
        resp.setPlayType(a.getPlayType());
        resp.setScene(a.getScene());
        resp.setStatus(a.getStatus());
        resp.setCurVersion(a.getCurVersion());
        // 可用性由数据库判定：时间窗口的两端存在库里，比较也须用库的时钟
        resp.setAvailable(a.isAvailable());
        return resp;
    }

    // ------------------------------------------------------------------
    // ① 创建
    // ------------------------------------------------------------------

    @Override
    public String createActivity(CreateActivityReq req) {
        requireText(req.getClientReqNo(), "clientReqNo");
        requireText(req.getName(), "name");
        requireText(req.getPlayType(), "playType");
        requireText(req.getScene(), "scene");
        requireText(req.getStartTime(), "startTime");
        requireText(req.getEndTime(), "endTime");

        // JSON 语法在此把关。这几列在库里是 JSON 类型，非法值会被 MySQL 拒成
        // DataIntegrityViolationException —— 那是个 5xxx 形态的系统异常，而「运营填错了格式」
        // 是确定的入参错误（4001）。不在此拦截，调用方拿到的错误码分区就是错的
        requireJsonOrBlank(req.getCityScope(), "cityScope");
        requireJsonOrBlank(req.getChannelScope(), "channelScope");
        requireJsonOrBlank(req.getCrowdRule(), "crowdRule");
        requireJsonOrBlank(req.getRiskRule(), "riskRule");
        requireJsonOrBlank(req.getPlayConfig(), "playConfig");
        requireJsonOrBlank(req.getRewardConfig(), "rewardConfig");

        String idempotentKey = "ACT_CREATE_" + req.getClientReqNo();

        // 幂等前置查：命中直接返回原活动，不再走插入
        String existing = opRecordMapper.selectActivityIdByIdempotentKey(idempotentKey);
        if (existing != null) {
            log.info("createActivity idempotent hit, activityId={}", existing);
            return existing;
        }

        // 单号碰撞与幂等命中是两回事，必须分开处置（V1 缺陷 ③）：
        // 前者换号重试，后者返回原活动。合并处理会在碰撞时返回另一个活动
        for (int attempt = 0; attempt < ID_RETRY_LIMIT; attempt++) {
            String activityId = BizNoGenerator.activityId();
            try {
                tx.createDraft(
                        activityId,
                        idempotentKey,
                        req.getName(),
                        req.getPlayType(),
                        req.getScene(),
                        req.getStartTime(),
                        req.getEndTime(),
                        req.getCityScope(),
                        req.getChannelScope(),
                        req.getCrowdRule(),
                        req.getRiskRule(),
                        req.getPlayConfig(),
                        req.getRewardConfig(),
                        req.getOperator());
                log.info("createActivity done, activityId={}", activityId);
                return activityId;
            } catch (DuplicateKeyException e) {
                String hit = opRecordMapper.selectActivityIdByIdempotentKey(idempotentKey);
                if (hit != null) {
                    // 幂等键冲突：并发的同一请求已建好，返回它那一笔
                    log.info("createActivity concurrent idempotent hit, activityId={}", hit);
                    return hit;
                }
                // 活动号碰撞：换号重试
                log.warn("activityId collision, retry, attempt={}", attempt + 1);
            }
        }
        throw new BizException(ErrorCode.DOWNSTREAM_UNKNOWN, "活动号连续碰撞，请重试");
    }

    // ------------------------------------------------------------------
    // ② 发布
    // ------------------------------------------------------------------

    @Override
    public PublishActivityResp publishActivity(String activityId, String operator) {
        requireText(activityId, "activityId");

        MarketingActivity activity = activityMapper.selectByActivityId(activityId);
        if (activity == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "活动不存在: " + activityId);
        }

        // 六项校验。不过则不生成版本、不改状态 —— 留下「版本已生成但状态没推进」的中间态，
        // 会让下一次发布拿到跳号的版本，而那个号对应的配置从未生效过
        List<String> failures =
                PublishChecker.check(activity, playConfigOf(activity), rewardConfigOf(activity));
        if (!failures.isEmpty()) {
            log.info(
                    "publishActivity check failed, activityId={}, failures={}",
                    activityId,
                    failures);
            throw new BizException(
                    ErrorCode.PUBLISH_CHECK_FAILED, "发布校验不通过: " + String.join("; ", failures));
        }

        int fromVersion = activity.getCurVersion() == null ? 0 : activity.getCurVersion();
        int toVersion = fromVersion + 1;
        String idempotentKey = "ACT_PUBLISH_" + activityId + "_" + toVersion;

        try {
            tx.publish(
                    activityId,
                    fromVersion,
                    toVersion,
                    playConfigOf(activity),
                    rewardConfigOf(activity),
                    idempotentKey,
                    operator);
        } catch (DuplicateKeyException | IllegalStateException e) {
            // 并发发布：另一方已推进。不重试 —— 重试会基于陈旧的 fromVersion 再算一次
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "活动正在被并发发布: " + activityId);
        }

        PublishActivityResp resp = new PublishActivityResp();
        resp.setActivityId(activityId);
        resp.setVersion(toVersion);
        resp.setStatus(ActivityStatus.SCHEDULED.name());
        log.info("publishActivity done, activityId={}, version={}", activityId, toVersion);
        return resp;
    }

    // ------------------------------------------------------------------
    // ③ 状态变更
    // ------------------------------------------------------------------

    @Override
    public void changeActivityStatus(
            String activityId, String targetStatus, String opSeq, String operator) {
        requireText(activityId, "activityId");
        requireText(targetStatus, "targetStatus");

        MarketingActivity activity = activityMapper.selectByActivityId(activityId);
        if (activity == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "活动不存在: " + activityId);
        }

        ActivityStatus from = ActivityStatus.valueOf(activity.getStatus());
        ActivityStatus to = parseStatus(targetStatus);

        if (from == to) {
            log.info("changeActivityStatus already at target, skip, activityId={}", activityId);
            return;
        }
        if (!from.canTransitTo(to)) {
            // 抛出而非静默忽略：静默会让运营以为改成功了，而后台状态与操作不符且无错可查
            throw new BizException(
                    ErrorCode.INVALID_STATUS_TRANSITION, "非法状态迁移: " + from + " → " + to);
        }

        String idempotentKey = "ACT_STATUS_" + activityId + "_" + safeSeq(opSeq);
        try {
            int rows =
                    tx.changeStatus(
                            activityId,
                            from.name(),
                            to.name(),
                            safeSeq(opSeq),
                            idempotentKey,
                            operator);
            if (rows == 0) {
                throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "活动状态已被并发变更: " + activityId);
            }
        } catch (DuplicateKeyException e) {
            // 同一 opSeq 重复请求：幂等命中，视为成功
            log.info("changeActivityStatus idempotent hit, activityId={}", activityId);
        }
        log.info("changeActivityStatus done, activityId={}, {} → {}", activityId, from, to);
    }

    // ------------------------------------------------------------------
    // ④ 资格决策
    // ------------------------------------------------------------------

    /**
     * 四维判定，<b>只读无副作用</b>。
     *
     * <p>任一维度的依赖不可用时返回 {@code CONTEXT_UNAVAILABLE} + {@code 5201}，而非「不通过」 ——
     * 后者会让风控挂掉时全部用户被告知「你不符合条件」，业务上误判、排查时也看不出故障。
     */
    @Override
    public QualifyResp decideQualification(QualifyReq req) {
        requireText(req.getUserId(), "userId");

        QualifyResp resp = new QualifyResp();
        try {
            MarketingActivity activity = resolveActivity(req);
            if (activity == null) {
                return reject(resp, QualifyReason.ACTIVITY_UNAVAILABLE, null);
            }
            resp.setActivityId(activity.getActivityId());

            // 一维：活动可用性。由库判定时间窗，与 queryActivityConf 同一口径
            MarketingActivityMapper.ActivityRow row =
                    activityMapper.selectWithAvailability(activity.getActivityId());
            if (row == null || !row.isAvailable()) {
                return reject(resp, QualifyReason.ACTIVITY_UNAVAILABLE, activity.getActivityId());
            }

            // 二维：城市。以服务端解析结果为准，客户端上传值仅作提示（BR-C-06）
            String resolvedCity = resolveCity(req);
            resp.setResolvedCity(resolvedCity);
            if (!inScope(activity.getCityScope(), resolvedCity)) {
                return reject(resp, QualifyReason.CITY_NOT_MATCH, activity.getActivityId());
            }

            // 三维：渠道
            if (!inScope(activity.getChannelScope(), req.getChannel())) {
                return reject(resp, QualifyReason.CHANNEL_NOT_MATCH, activity.getActivityId());
            }

            // 四维：人群与风控
            if (!matchCrowd(activity.getCrowdRule(), req.getUserId())) {
                return reject(resp, QualifyReason.CROWD_NOT_MATCH, activity.getActivityId());
            }
            if (hitRisk(activity.getRiskRule(), req)) {
                return reject(resp, QualifyReason.RISK_REJECTED, activity.getActivityId());
            }

            resp.setPass(true);
            resp.setReasonCode(QualifyReason.PASS.name());
            return resp;
        } catch (Exception e) {
            // 依赖异常 → 5201。不判「不符合条件」：那是把系统故障说成用户不合格
            log.error("decideQualification context error, userId={}", req.getUserId(), e);
            resp.setPass(false);
            resp.setReasonCode(QualifyReason.CONTEXT_UNAVAILABLE.name());
            resp.setErrorCode(ErrorCode.QUALIFY_CONTEXT_ERROR);
            return resp;
        }
    }

    private QualifyResp reject(QualifyResp resp, QualifyReason reason, String activityId) {
        resp.setPass(false);
        resp.setReasonCode(reason.name());
        resp.setErrorCode(
                reason.isSystemError() ? ErrorCode.QUALIFY_CONTEXT_ERROR : ErrorCode.NOT_QUALIFIED);
        if (activityId != null) {
            resp.setActivityId(activityId);
        }
        return resp;
    }

    private MarketingActivity resolveActivity(QualifyReq req) {
        if (req.getActivityId() != null && !req.getActivityId().isBlank()) {
            return activityMapper.selectByActivityId(req.getActivityId());
        }
        return null;
    }

    /**
     * 服务端解析城市。
     *
     * <p>V3 以客户端声明值兜底，V4 接真实的 IP 归属库。<b>这一层必须存在</b>：直接用 {@code req.getCity()}
     * 判定时，用户改一个请求字段就能绕开城市限制，而 BR-C-06 要的正是「以服务端 上下文为准」。留出这个方法，接入归属库时改一处即可。
     */
    private String resolveCity(QualifyReq req) {
        return req.getCity();
    }

    /** 范围判定：范围为空表示不限。 */
    private static boolean inScope(String scopeJson, String value) {
        if (scopeJson == null || scopeJson.isBlank() || "[]".equals(scopeJson.trim())) {
            return true;
        }
        if (value == null || value.isBlank()) {
            // 配了范围但取不到值：判不通过而非放行 —— 放行等于配置形同虚设
            return false;
        }
        return scopeJson.contains("\"" + value + "\"");
    }

    /** 人群规则：为空表示全量人群。 */
    private static boolean matchCrowd(String crowdRule, String userId) {
        if (crowdRule == null || crowdRule.isBlank() || "{}".equals(crowdRule.trim())) {
            return true;
        }
        return !crowdRule.contains("\"exclude\":true");
    }

    /**
     * 风控：命中黑名单即拒。
     *
     * <p><b>规则配了但不可用时抛出，由上层归为 {@code 5201}</b> —— 而不是「当作没配」放行。风控是 fail-close
     * 的：读不懂规则时放行，等于风控挂掉的那段时间里门是开着的（PRD FR-F04 把 账户状态、频控这几项列为 fail-close 正是同一条）。
     *
     * <p>这个分支必须真的可达。写一个「理论上会异常」但实际永远不触发的 try-catch，等于给自己一个 「已经处理了故障」的错觉，而 {@code 5201}
     * 从未被任何代码路径产出过。
     */
    private static boolean hitRisk(String riskRule, QualifyReq req) {
        if (riskRule == null || riskRule.isBlank() || "{}".equals(riskRule.trim())) {
            return false;
        }
        if (!riskRule.trim().startsWith("{")) {
            // 规则存在却不是对象：配置已损坏，读不懂就不敢放行
            throw new IllegalStateException("风控规则不可解析: " + riskRule);
        }
        return riskRule.contains("\"" + req.getUserId() + "\"");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 草稿态配置，发布时整体快照进版本表。空值归一为 {@code {}}，版本表两列均 NOT NULL。 */
    private static String playConfigOf(MarketingActivity activity) {
        return blankToEmptyJson(activity.getPlayConfig());
    }

    private static String rewardConfigOf(MarketingActivity activity) {
        return blankToEmptyJson(activity.getRewardConfig());
    }

    private static String blankToEmptyJson(String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }

    private static ActivityStatus parseStatus(String raw) {
        try {
            return ActivityStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_PARAM, "活动状态取值非法: " + raw);
        }
    }

    private static String safeSeq(String opSeq) {
        return opSeq == null ? "" : opSeq;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不能为空");
        }
    }

    /**
     * 字段为空，或形如 JSON。
     *
     * <p>只做形状校验，不做深解析：深校验要引入 schema 定义，而规则的 schema 属运营配置化范围， 本期不做。形状校验挡得住「填了一段自然语言」这类错误，也就够把 MySQL
     * 的 JSON 列约束 提前到应用层 —— 目的是让错误码落在 {@code 4001} 而非系统异常分区。
     */
    private static void requireJsonOrBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            return;
        }
        String t = value.trim();
        boolean shaped =
                (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
        if (!shaped) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 须为 JSON 对象或数组");
        }
    }
}
