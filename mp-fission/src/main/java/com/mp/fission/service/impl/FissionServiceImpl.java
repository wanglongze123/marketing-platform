package com.mp.fission.service.impl;

import com.mp.api.activity.dto.ActivityConfResp;
import com.mp.api.activity.dto.QualifyReq;
import com.mp.api.activity.dto.QualifyResp;
import com.mp.api.activity.service.ActivityService;
import com.mp.api.fission.dto.GroupQueryResp;
import com.mp.api.fission.dto.SponsorQueryReq;
import com.mp.api.fission.dto.SponsorQueryResp;
import com.mp.api.fission.service.FissionService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.exception.BizException;
import com.mp.common.util.BizNoGenerator;
import com.mp.fission.entity.FissionGroup;
import com.mp.fission.repository.FissionGroupMapper;
import com.mp.fission.service.FissionTxService;
import java.util.ArrayList;
import java.util.List;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 裂变进场与轮次管理。
 *
 * <p><b>资格判定调公共能力层</b>，玩法层不重复实现 —— 两处判据迟早漂移，而漂移的表现是「咨询说 能参与，进场说不能」。
 *
 * <p><b>本类不带 {@code @Transactional}</b>：事务边界在 {@link FissionTxService}。
 */
@DubboService
@Service
public class FissionServiceImpl implements FissionService {

    private static final Logger log = LoggerFactory.getLogger(FissionServiceImpl.class);

    /** 轮次有效期：7 天。超出上限判 4602 */
    private static final long DEFAULT_ROUND_TTL_SECONDS = 7 * 24 * 3600L;

    /** 达标所需徒弟数。V3 取固定值，运营配置化后改由活动配置快照填充 */
    private static final int DEFAULT_TARGET_COUNT = 3;

    /** 单号碰撞后的换号重试上限 */
    private static final int ID_RETRY_LIMIT = 3;

    // V3 单进程用 Spring 注入；V4 拆服务后改为 @DubboReference(protocol="tri")。
    //
    // 与 benefit-order / reward 的既有形态保持一致，不提前用 @DubboReference：injvm 代理会把
    // BizException 包成 RuntimeException，错误码随之丢失 —— 而 1xxx/4xxx 与 5xxx 的分区正是
    // 调度器判「确定拒绝」还是「结果未知」的依据（V2 PR-8）。跨进程的异常传播要在 V4 连同
    // 序列化一并处理，此时提前引入只会让错误码在单进程阶段就已失效。
    @Autowired private ActivityService activityService;

    private final FissionGroupMapper groupMapper;
    private final FissionTxService tx;

    public FissionServiceImpl(FissionGroupMapper groupMapper, FissionTxService tx) {
        this.groupMapper = groupMapper;
        this.tx = tx;
    }

    // ------------------------------------------------------------------
    // ① 师傅进场
    // ------------------------------------------------------------------

    @Override
    public SponsorQueryResp sponsorQuery(SponsorQueryReq req) {
        requireText(req.getSponsorId(), "sponsorId");
        requireText(req.getActivityId(), "activityId");

        SponsorQueryResp resp = new SponsorQueryResp();
        resp.setActivityId(req.getActivityId());

        // 资格决策：人群、城市、渠道、风控四维，由公共能力层判定
        QualifyResp qualify = activityService.decideQualification(toQualifyReq(req));
        if (!qualify.isPass()) {
            // 无可参与活动是正常业务结果，不抛异常（BR-F-01）—— 抛异常会让「今天没活动」
            // 与「资格服务挂了」在调用方看来一样
            resp.setAvailable(false);
            resp.setReasonCode(qualify.getReasonCode());
            log.info(
                    "sponsorQuery not qualified, sponsorId={}, reason={}",
                    req.getSponsorId(),
                    qualify.getReasonCode());
            return resp;
        }

        resp.setAvailable(true);
        resp.setReasonCode(qualify.getReasonCode());

        // 复用进行中轮次；无则自动开轮
        FissionGroup running = groupMapper.selectRunning(req.getActivityId(), req.getSponsorId());
        if (running == null) {
            running = createRound(req.getActivityId(), req.getSponsorId());
        }

        fillRound(resp, running);
        resp.setInviteToken(inviteToken(running, req.getSponsorId()));
        return resp;
    }

    // ------------------------------------------------------------------
    // ② 手动开轮
    // ------------------------------------------------------------------

    @Override
    public String openGroup(String activityId, String sponsorId) {
        requireText(activityId, "activityId");
        requireText(sponsorId, "sponsorId");

        // 已有轮次占着 active_flag 时拒绝（BR-F-04）：一个师傅在一个活动下同时只能有一轮，
        // 否则邀请来的徒弟不知道该记进哪一轮。
        //
        // 判据取 selectActive（只认 active_flag）而非 selectRunning（带 expire_time 过滤）：
        // 二者对「已过期但未被治理」的轮次给出相反答案 —— 用后者会放行开新轮，而唯一键
        // 挡下它，异常从业务提示退化成 DuplicateKeyException；更糟的是若被 createRound 的
        // 撞键分支捕获，调用方会拿到那条过期轮次，看起来「开轮成功」而实际什么也没发生。
        //
        // 过期轮次须先由治理推到终态并释放键，才允许开下一轮。
        FissionGroup occupying = groupMapper.selectActive(activityId, sponsorId);
        if (occupying != null) {
            throw new BizException(
                    ErrorCode.GROUP_ALREADY_RUNNING, "已存在未终结的轮次: " + occupying.getGroupId());
        }

        return createRound(activityId, sponsorId).getGroupId();
    }

    /**
     * 建一轮，轮次号取历史最大值 +1。
     *
     * <p><b>并发兜底靠 {@code uk_activity_sponsor_active} 而非 {@code uk_activity_sponsor_round}</b>：
     * 轮次号由「读 MAX 再 +1」算出，并发时线程 A 建轮 1 提交后，线程 B 读到 1 并算出轮 2 —— 两条记录的 {@code round_no}
     * 不同，轮次号唯一键根本不冲突。挡住「同时两轮进行中」的是 {@code active_flag} 那道部分唯一键（BR-F-04）。
     *
     * <p>撞键后回查已建好的那一轮返回，<b>不重试建新轮</b>：重试会算出 +1 的轮次号，把「并发进场」 变成「开了两轮」—— 那正是这道闸要挡的。
     */
    private FissionGroup createRound(String activityId, String sponsorId) {
        ActivityConfResp conf = activityService.queryActivityConf(activityId);
        if (conf == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "活动不存在: " + activityId);
        }

        Integer maxRound = groupMapper.selectMaxRound(activityId, sponsorId);
        int roundNo = maxRound == null ? 1 : maxRound + 1;

        for (int attempt = 0; attempt < ID_RETRY_LIMIT; attempt++) {
            String groupId = BizNoGenerator.fissionGroupNo();
            try {
                tx.openGroup(
                        groupId,
                        activityId,
                        sponsorId,
                        roundNo,
                        DEFAULT_TARGET_COUNT,
                        conf.getCurVersion(),
                        DEFAULT_ROUND_TTL_SECONDS);
                log.info(
                        "fission round opened, groupId={}, sponsorId={}, roundNo={}",
                        groupId,
                        sponsorId,
                        roundNo);
                return groupMapper.selectByGroupId(groupId);
            } catch (DuplicateKeyException e) {
                // 撞 uk_activity_sponsor_active 或 uk_activity_sponsor_round：并发进场已建轮，回查它。
                //
                // 回查用 selectActive 而非 selectRunning：后者带 expire_time > NOW(3)，与唯一键
                // 的判据不一致 —— 唯一键只认 active_flag，不看有效期。两者口径不同时，
                // 「撞了键却查不到」会让本方法误判为组号碰撞并换号重试，最终抛碰撞异常
                FissionGroup existing = groupMapper.selectActive(activityId, sponsorId);
                if (existing != null) {
                    log.info(
                            "concurrent round creation, reuse existing groupId={}",
                            existing.getGroupId());
                    return existing;
                }
                // 撞 uk_group_id：组号碰撞，换号重试
                log.warn("groupId collision, retry, attempt={}", attempt + 1);
            }
        }
        throw new BizException(ErrorCode.FISSION_QUERY_ERROR, "裂变组号连续碰撞，请重试");
    }

    // ------------------------------------------------------------------
    // ③ 轮次查询
    // ------------------------------------------------------------------

    @Override
    public GroupQueryResp queryGroup(String activityId, String sponsorId, boolean includeHistory) {
        requireText(activityId, "activityId");
        requireText(sponsorId, "sponsorId");

        GroupQueryResp resp = new GroupQueryResp();
        resp.setActivityId(activityId);
        resp.setSponsorId(sponsorId);

        FissionGroup running = groupMapper.selectRunning(activityId, sponsorId);
        if (running != null) {
            resp.setCurrent(toRoundInfo(running));
        }

        // 开关默认关闭（BR-F-05）：默认全开会让每次查询都拉膨胀表明细，
        // 而多数调用方只要当前轮的进度
        if (includeHistory) {
            List<GroupQueryResp.RoundInfo> history = new ArrayList<>();
            for (FissionGroup g : groupMapper.selectHistory(activityId, sponsorId)) {
                history.add(toRoundInfo(g));
            }
            resp.setHistory(history);
        }
        return resp;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static QualifyReq toQualifyReq(SponsorQueryReq req) {
        QualifyReq q = new QualifyReq();
        q.setUserId(req.getSponsorId());
        q.setActivityId(req.getActivityId());
        q.setScene(req.getScene());
        q.setCity(req.getCity());
        q.setChannel(req.getChannel());
        q.setDeviceId(req.getDeviceId());
        q.setClientIp(req.getClientIp());
        return q;
    }

    private static void fillRound(SponsorQueryResp resp, FissionGroup group) {
        resp.setGroupId(group.getGroupId());
        resp.setRoundNo(group.getRoundNo());
        resp.setProgress(group.getProgress());
        resp.setTargetCount(group.getTargetCount());
    }

    private static GroupQueryResp.RoundInfo toRoundInfo(FissionGroup g) {
        GroupQueryResp.RoundInfo info = new GroupQueryResp.RoundInfo();
        info.setGroupId(g.getGroupId());
        info.setRoundNo(g.getRoundNo());
        info.setStatus(g.getStatus());
        info.setProgress(g.getProgress());
        info.setTargetCount(g.getTargetCount());
        return info;
    }

    /**
     * 邀请凭证。
     *
     * <p>V3 PR-2 取确定性拼接的明文串，PR-3 分享链路接入时改为 {@code ConsultTokenSigner} 签名 —— 该 SDK 落在 {@code
     * mp-common}，正是为了「Token 处理不放在 activity」（技术方案 §4.2）。
     *
     * <p>此处不提前签名：签名要绑定的字段（徒弟分支、奖励快照）在 PR-3 才确定，提前签会签一个 不完整的载荷，而凭证一旦签发就会被端上缓存。
     */
    private static String inviteToken(FissionGroup group, String sponsorId) {
        return group.getGroupId() + "_" + sponsorId + "_" + group.getRoundNo();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不能为空");
        }
    }
}
