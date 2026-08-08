package com.mp.fission.reconcile;

import com.mp.common.enums.TaskType;
import com.mp.common.util.BizNoGenerator;
import com.mp.common.util.IdempotentKeys;
import com.mp.fission.entity.FissionRelation;
import com.mp.fission.repository.FissionReconcileMapper;
import com.mp.fission.repository.FissionRelationMapper;
import com.mp.fission.repository.FissionTaskMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 裂变侧对账（技术方案 §6.8 第 7、10、12、13 项）。V3 PR-10。
 *
 * <p><b>与权益侧分成两个服务，不合成一个</b>：两者读写不同的库、绑不同的事务管理器。合成一个类会让 它同时持有两套数据源的 mapper —— 而按包路径绑定数据源正是 V2
 * 定下的隔离方式。
 *
 * <p>处置动作与权益侧同形：<b>可自愈的一律补建任务</b>（任务自带幂等闸），不直接改关系状态。第 13 项 是唯一会改字段的（清 {@code
 * granting_until}），理由见该方法的注释。
 */
@Service
public class FissionReconcileService {

    private static final Logger log = LoggerFactory.getLogger(FissionReconcileService.class);

    private static final int SCAN_LIMIT = 200;

    private final FissionReconcileMapper reconcileMapper;
    private final FissionRelationMapper relationMapper;
    private final FissionTaskMapper taskMapper;

    /** 差异判定的时间下界，语义同权益侧 —— 查的是「长期未推进」，不是「此刻还没推进」 */
    private final int staleSeconds;

    public FissionReconcileService(
            FissionReconcileMapper reconcileMapper,
            FissionRelationMapper relationMapper,
            FissionTaskMapper taskMapper,
            @Value("${mp.reconcile.stale-seconds:300}") int staleSeconds) {
        this.reconcileMapper = reconcileMapper;
        this.relationMapper = relationMapper;
        this.taskMapper = taskMapper;
        this.staleSeconds = staleSeconds;
    }

    /** 跑一轮裂变侧对账，返回各项差异数。每项独立捕获异常，一项失败不中断整轮。 */
    public Map<String, Integer> reconcileOnce() {
        Map<String, Integer> diffs = new ConcurrentHashMap<>();
        put(diffs, "SPONSOR_NOT_REWARDED", this::repairSponsorMissing);
        put(diffs, "RELATION_PROGRESS_LAG", this::checkProgressLag);
        put(diffs, "GRANT_DONE_RELATION_LAG", this::checkGrantDoneRelationLag);
        put(diffs, "GRANTING_UNTIL_EXPIRED", this::repairGrantingExpired);
        log.info("fission reconcile round done, diffs={}", diffs);
        return diffs;
    }

    private void put(Map<String, Integer> diffs, String key, ItemRunner runner) {
        try {
            int n = runner.run();
            if (n > 0) {
                diffs.put(key, n);
            }
        } catch (Exception e) {
            log.error("fission reconcile item failed, item={}", key, e);
        }
    }

    /**
     * 第 7 项：徒弟已发奖但师傅返奖任务缺失 → 补建 {@code SPONSOR_REWARD} 任务。
     *
     * <p><b>返奖键派生自 {@code outFlowNo}，而关系行上没有这个值</b>。读 {@code relation.out_biz_no} 当 {@code
     * outFlowNo} 用是错的：技术方案 §4.1 把两者定义为不同的东西 —— {@code OutBizNo} 标识一次业务关系，{@code OutFlowNo}
     * 标识本次操作，一次关系下可发生多次操作，二者不天然相等 （加入与确权是两次调用，各带各的 {@code OutFlowNo}，而关系行存的是加入时那次的 {@code
     * OutBizNo}）。
     *
     * <p><b>取错的后果正是本项要防的那件事</b>：补出的键与主链路那把不同 → {@code uk_biz_type_op} 不冲突 → 任务照常入队 → 下游按新 {@code
     * opNo} 当成一笔全新的发放。师傅奖发两次，直接资损。 这一项本是「师傅奖漏发」的兜底，修的是漏发，却造出重发。
     *
     * <p><b>锚点取 {@code QUERY_GRANT} 任务</b>：它的 {@code biz_no} 就是 {@code relationId}、{@code op_no} 就是
     * {@code followerGrantNo}，由 {@code prepareDone} 与操作记录同事务落库，按关系号锚定无歧义。 去掉 {@code _FL} 后缀即得
     * {@code outFlowNo} —— 与 {@code settleDoneFromQuery} 的反推<b>走同一个 方法</b>（{@link
     * IdempotentKeys#outFlowNoOfFollowerGrant}），故两条通路的键规则不可能漂移。
     *
     * <p>取不到则跳过并告警，<b>宁可不补也不猜</b>：猜一把键的代价是重复发奖，不可逆；不补的代价只是 这一条继续挂着等人工，可逆。
     *
     * <p>这一项的差异意味着<b>那次四写事务只成了一半</b>：关系已 {@code DONE} 而返奖任务不存在，师傅奖永久 漏发且无重试载体。补建任务即补上那个载体。
     */
    private int repairSponsorMissing() {
        List<String> relationIds =
                reconcileMapper.scanSponsorRewardMissing(staleSeconds, SCAN_LIMIT);
        for (String relationId : relationIds) {
            FissionRelation relation = relationMapper.selectByRelationId(relationId);
            if (relation == null) {
                continue;
            }
            // 由查单任务的 op_no 反推 outFlowNo，不读关系行的 out_biz_no —— 两者是不同的东西
            String followerGrantNo = reconcileMapper.selectFollowerGrantNo(relationId);
            if (followerGrantNo == null) {
                // 关系已 DONE 却没有查单任务：数据不一致，补不出正确的键。不猜 ——
                // 猜错即重复发奖，那比「继续漏发」更不可逆
                log.error(
                        "fission reconcile cannot derive sponsor key, no QUERY_GRANT task,"
                                + " relationId={}",
                        relationId);
                continue;
            }
            String outFlowNo = IdempotentKeys.outFlowNoOfFollowerGrant(followerGrantNo);
            taskMapper.enqueue(
                    BizNoGenerator.fissionTaskNo(),
                    relationId,
                    TaskType.SPONSOR_REWARD.name(),
                    IdempotentKeys.sponsorFlowNo(outFlowNo),
                    0,
                    "{}");
            log.warn(
                    "fission reconcile repaired missing sponsor reward, relationId={}", relationId);
        }
        return relationIds.size();
    }

    /**
     * 第 10 项：关系完成但轮次进度未推进 → 告警。
     *
     * <p><b>不自动改 {@code progress}</b>：它是共享计数器，与库存、限购额度同类 —— 正确值取决于历史上 哪些关系完成过，直接改会把一次错误固化成新基线。这与
     * §6.8 对第 6、15 项的处置是同一条。
     *
     * <p>方案里写的处置是「重放关系后处理」，而重放的入口是 {@code settleDone} —— 它的关系推进用条件 更新（{@code WHERE
     * status='JOINED'}），对一条已 {@code DONE} 的关系命中 0 行，进度加不上去。 故此处只能告警，由人工核定该加几。
     */
    private int checkProgressLag() {
        List<String> groupIds = reconcileMapper.scanProgressLag(staleSeconds, SCAN_LIMIT);
        for (String groupId : groupIds) {
            log.error("fission reconcile found progress lag, groupId={} — 禁止自动改数，须人工核", groupId);
        }
        return groupIds.size();
    }

    /**
     * 第 12 项：发奖成功但关系未推进 → 告警并交由查单/事件重放。
     *
     * <p>与第 7 项是四写事务两种半途失败的两面。<b>此处不直接调 {@code settleDone}</b>：那需要 {@code followerGrantNo}
     * 等三个派生键，而对账手上只有 {@code outBizNo}；派生规则若在此再写一遍， 与发起侧漂移的后果是二次发放。故只告警，重放走 {@code manualRepair}
     * 的「重试发奖」动作 —— 那里 复用的是原键。
     */
    private int checkGrantDoneRelationLag() {
        List<String> outBizNos = reconcileMapper.scanGrantDoneRelationLag(staleSeconds, SCAN_LIMIT);
        for (String outBizNo : outBizNos) {
            log.error("fission reconcile found grant-done relation lag, outBizNo={}", outBizNo);
        }
        return outBizNos.size();
    }

    /**
     * 第 13 项：发奖在途标志超时 → 清空豁免，<b>让过期治理能接管</b>。
     *
     * <p>这是唯一一项直接改字段的处置，理由是<b>不改它就没有任何机制能再动这行</b>：{@code granting_until} 未到期时过期治理跳过该关系（§3.3
     * 的豁免），而超时意味着发奖早该收敛却没有 —— 关系既不被治理 接管，也没人推进它，永久悬挂。
     *
     * <p>它与「不自动改数」并不矛盾：清的是<b>豁免标志</b>，不是业务结果。清完之后关系回到治理的扫描 范围内，由既有通路处置 —— 仍然是「把单子推回收敛通路」，不是替它做决定。
     *
     * <p>同时告警：超时本身说明发奖链路出过问题，值得人看一眼。
     */
    private int repairGrantingExpired() {
        List<String> relationIds = reconcileMapper.scanGrantingExpired(SCAN_LIMIT);
        for (String relationId : relationIds) {
            relationMapper.clearGranting(relationId);
            log.error("fission reconcile cleared expired granting flag, relationId={}", relationId);
        }
        return relationIds.size();
    }

    @FunctionalInterface
    private interface ItemRunner {
        int run() throws Exception;
    }
}
