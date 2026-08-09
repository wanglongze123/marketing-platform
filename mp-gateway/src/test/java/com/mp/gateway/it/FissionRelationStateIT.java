package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.common.enums.RelationStatus;
import com.mp.common.util.BizNoGenerator;
import com.mp.fission.entity.FissionRelation;
import com.mp.fission.repository.FissionRelationMapper;
import com.mp.fission.service.FissionTxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 关系状态机与 {@code active_flag} 部分唯一，对应《分阶段方案》§6.5 退出标准 2、3。
 *
 * <p><b>第 3 条是裂变最容易静默失败的一处</b>：三条终态路径若漏掉任一条的 {@code active_flag} 释放，该行仍占着 {@code (group_id,
 * follower_id, 'ACTIVE')}，该师徒下一轮分享插入时 唯一键冲突 —— 而「先插后判」模式会把冲突当幂等命中，静默返回那条已终结的关系。
 *
 * <p>故判据必须是<b>「是不是新行」</b>而非「有没有报错」：静默失败的形态是接口返回成功。
 */
class FissionRelationStateIT extends AbstractMySqlIT {

    @Autowired private FissionRelationMapper relationMapper;
    @Autowired private FissionTxService tx;

    private String newActiveRelation(String groupId, String followerId) {
        String relationId = BizNoGenerator.fissionRelationNo();
        relationMapper.insertActive(
                relationId,
                groupId,
                "ACT_FISSION_001",
                "U_sponsor",
                followerId,
                "",
                RelationStatus.INVITED.name(),
                "IM",
                "2030-12-31 23:59:59.999");
        return relationId;
    }

    /**
     * 标准 3：三条终态路径都释放 {@code active_flag}，释放后同师徒可重新建立关系。
     *
     * <p>逐条走 {@code DONE} / {@code EXPIRED} / {@code CANCEL}，每条之后再建一次关系并断言 <b>拿到的是新行</b> ——
     * 只断言「建关系没报错」的话，返回旧行的实现照常全绿。
     */
    @Test
    void allThreeTerminalPathsReleaseActiveFlagAndAllowRebuild() {
        record Path(RelationStatus from, RelationStatus to) {}

        Path[] paths = {
            new Path(RelationStatus.JOINED, RelationStatus.DONE),
            new Path(RelationStatus.INVITED, RelationStatus.EXPIRED),
            new Path(RelationStatus.INVITED, RelationStatus.CANCEL),
        };

        for (Path path : paths) {
            String groupId = "FG_TERM_" + path.to();
            String followerId = "U_follower";

            String first = newActiveRelation(groupId, followerId);
            if (path.from() == RelationStatus.JOINED) {
                // DONE 的前置是 JOINED，先推进
                relationMapper.advanceStatus(
                        first, RelationStatus.INVITED.name(), RelationStatus.JOINED.name());
            }

            assertThat(tx.terminateRelation(first, path.from(), path.to()))
                    .as("%s 终结应成功", path.to())
                    .isEqualTo(1);

            FissionRelation terminated = relationMapper.selectByRelationId(first);
            assertThat(terminated.getStatus()).isEqualTo(path.to().name());
            assertThat(terminated.getActiveFlag())
                    .as("%s 必须释放 active_flag 为 relation_id", path.to())
                    .isEqualTo(first);

            // 同师徒重新建立关系：必须是新行，不是静默返回的旧行
            String second = newActiveRelation(groupId, followerId);
            assertThat(second).as("%s 之后重建应产生新关系", path.to()).isNotEqualTo(first);
            assertThat(relationMapper.selectActive(groupId, followerId).getRelationId())
                    .as("进行中的那条应是新建的，而非已终结的旧行")
                    .isEqualTo(second);
        }
    }

    /** 标准 2：同组同徒至多一条进行中关系 —— 第二次建立撞唯一键。 */
    @Test
    void secondActiveRelationForSameFollowerIsRejectedByUniqueKey() {
        String groupId = "FG_DUP";
        String followerId = "U_dup";
        newActiveRelation(groupId, followerId);

        assertThatThrownBy(() -> newActiveRelation(groupId, followerId))
                .as("uk_group_follower_active 应挡下第二条进行中关系")
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_relation WHERE group_id = ?"
                                        + " AND follower_id = ? AND active_flag = 'ACTIVE'",
                                groupId,
                                followerId))
                .isEqualTo(1);
    }

    /** 终态无出边：已完成的关系不能被改回进行中，否则同一徒弟会触发第二次双向发奖。 */
    @Test
    void terminalRelationCannotTransitBack() {
        String groupId = "FG_TERMINAL";
        String relationId = newActiveRelation(groupId, "U_t");
        relationMapper.advanceStatus(
                relationId, RelationStatus.INVITED.name(), RelationStatus.JOINED.name());
        tx.terminateRelation(relationId, RelationStatus.JOINED, RelationStatus.DONE);

        assertThatThrownBy(
                        () ->
                                tx.terminateRelation(
                                        relationId, RelationStatus.DONE, RelationStatus.CANCEL))
                .isInstanceOf(IllegalArgumentException.class);

        // 条件更新本身也挡住：即便绕过枚举校验，from 已不是 JOINED
        assertThat(
                        relationMapper.terminate(
                                relationId,
                                RelationStatus.JOINED.name(),
                                RelationStatus.CANCEL.name()))
                .as("条件更新的前置状态不匹配，affected_rows 应为 0")
                .isZero();
        assertThat(relationMapper.selectByRelationId(relationId).getStatus())
                .isEqualTo(RelationStatus.DONE.name());
    }

    /** {@code terminate} 拒绝非终态目标 —— 它是终态的唯一入口，不该被用作普通推进。 */
    @Test
    void terminateRejectsNonTerminalTarget() {
        String relationId = newActiveRelation("FG_GUARD", "U_g");

        assertThatThrownBy(
                        () ->
                                tx.terminateRelation(
                                        relationId,
                                        RelationStatus.INVITED,
                                        RelationStatus.CONNECTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("终态");
    }

    /**
     * 重复推进天然幂等：条件更新 {@code affected_rows = 0}（BR-F-17）。
     *
     * <p>非终态之间流转不动 {@code active_flag} —— 它在非终态恒为 {@code ACTIVE}。
     */
    @Test
    void repeatedAdvanceIsIdempotentAndKeepsActiveFlag() {
        String relationId = newActiveRelation("FG_IDEM", "U_i");

        assertThat(
                        relationMapper.advanceStatus(
                                relationId,
                                RelationStatus.INVITED.name(),
                                RelationStatus.CONNECTED.name()))
                .isEqualTo(1);
        assertThat(
                        relationMapper.advanceStatus(
                                relationId,
                                RelationStatus.INVITED.name(),
                                RelationStatus.CONNECTED.name()))
                .as("重复推进应命中 0 行")
                .isZero();

        assertThat(relationMapper.selectByRelationId(relationId).getActiveFlag())
                .as("非终态之间流转不应动 active_flag")
                .isEqualTo("ACTIVE");
    }

    /**
     * {@code out_biz_no} 回填只认第一个：上游用两个不同业务号并发调用时只有一个落进去。
     *
     * <p>这与「{@code out_biz_no} 不进唯一键」是同一个不变量的两半 —— 若它进了唯一键，上游用两个 业务号即可插出两条 JOINED 关系，同一徒弟触发两次双向发奖。
     */
    @Test
    void outBizNoIsFilledOnlyOnce() {
        String groupId = "FG_FILL";
        String followerId = "U_f";
        newActiveRelation(groupId, followerId);

        assertThat(
                        relationMapper.fillOutBizNoAndAdvance(
                                groupId,
                                followerId,
                                "OUT_1",
                                RelationStatus.INVITED.name(),
                                RelationStatus.JOINED.name()))
                .isEqualTo(1);

        // 第二个业务号：状态已是 JOINED 且 out_biz_no 非空，两个谓词各自都挡得住
        assertThat(
                        relationMapper.fillOutBizNoAndAdvance(
                                groupId,
                                followerId,
                                "OUT_2",
                                RelationStatus.INVITED.name(),
                                RelationStatus.JOINED.name()))
                .isZero();

        assertThat(relationMapper.selectActive(groupId, followerId).getOutBizNo())
                .isEqualTo("OUT_1");
    }
}
