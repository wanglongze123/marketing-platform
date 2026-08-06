package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.benefit.config.BenefitTx;
import com.mp.benefit.entity.PlayBizRecord;
import com.mp.benefit.repository.PlayBizRecordMapper;
import com.mp.benefit.repository.PlayOpRecordMapper;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.OpStatus;
import com.mp.common.enums.OpType;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RefundStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;

/**
 * 事务管理器与库的绑定，对应《分阶段方案》§5.7 退出标准 11 的运行期一半。
 *
 * <p>{@code ShapeFreezeTest} 静态检查证明「注解写了且带 {@code transactionManager} 限定」， 本类证明「限定指向了正确的库」——
 * 二者缺一不可。四套数据源下，指向别库的管理器不会报错： 本库的每条写各自自动提交，异常抛出后已写入的行留在库里，看上去像「事务不回滚」， 实际是根本没有事务。技术方案 §6.5
 * 的本地消息表以「状态、操作记录、任务同库同事务」为前提， 这个前提一旦静默失效，V2 的可靠任务表就退化为尽力而为。
 *
 * <p>验证方式是在 {@code @BenefitTx} 方法内连写两张 {@code db_benefit} 的表后抛异常，断言两张表 都没有留下行。只断言一张表不够 ——
 * 单条写在自动提交下也可能因唯一索引恰好失败而看起来「回滚了」。
 */
@Import(TransactionBindingIT.RollbackProbeConfig.class)
class TransactionBindingIT extends AbstractMySqlIT {

    @Autowired private RollbackProbe probe;

    /** 事务内的两处写在异常后一并回滚，证明 {@code benefitTransactionManager} 绑定的是 {@code db_benefit}。 */
    @Test
    void benefitTransactionRollsBackEveryWriteInTheSameSchema() {
        String bizNo = "BZ_IT_rollback";

        assertThatThrownBy(() -> probe.writeThenFail(bizNo))
                .isInstanceOf(IllegalStateException.class);

        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE play_biz_record_no = ?",
                                bizNo))
                .as("主单写入应随事务回滚")
                .isZero();
        assertThat(opRecordCount(bizNo, OpType.CREATE_TRADE.name()))
                .as("操作记录写入应随同一事务回滚 —— 只回滚其一即意味着两张表不在同一个事务里")
                .isZero();
    }

    /** 无异常时正常提交 —— 否则上一条断言可能只是因为写入本身没成功。 */
    @Test
    void benefitTransactionCommitsWhenNoExceptionIsThrown() {
        String bizNo = "BZ_IT_commit";

        probe.write(bizNo);

        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE play_biz_record_no = ?",
                                bizNo))
                .isEqualTo(1);
        assertThat(opRecordCount(bizNo, OpType.CREATE_TRADE.name())).isEqualTo(1);
    }

    @TestConfiguration
    static class RollbackProbeConfig {
        @org.springframework.context.annotation.Bean
        RollbackProbe rollbackProbe(
                PlayBizRecordMapper bizRecordMapper, PlayOpRecordMapper opRecordMapper) {
            return new RollbackProbe(bizRecordMapper, opRecordMapper);
        }
    }

    /**
     * 探针独立成 bean —— 与 {@code OrderTxService} 同理，同类内部调用不经代理，注解静默失效。
     *
     * <p>不复用 {@code OrderTxService}：它的方法均无失败路径，制造异常要靠脏数据，断言会掺进 「为什么这条数据能触发异常」的解释。探针直白得多。
     */
    @Service
    static class RollbackProbe {

        private final PlayBizRecordMapper bizRecordMapper;
        private final PlayOpRecordMapper opRecordMapper;

        RollbackProbe(PlayBizRecordMapper bizRecordMapper, PlayOpRecordMapper opRecordMapper) {
            this.bizRecordMapper = bizRecordMapper;
            this.opRecordMapper = opRecordMapper;
        }

        @BenefitTx
        void write(String bizNo) {
            bizRecordMapper.insert(newOrder(bizNo));
            opRecordMapper.upsert(
                    bizNo + "_CREATE",
                    bizNo + "_CREATE",
                    bizNo,
                    "U_txProbe",
                    ACTIVITY_ID,
                    OpType.CREATE_TRADE.name(),
                    "",
                    OpStatus.SUCCESS.name());
        }

        @BenefitTx
        void writeThenFail(String bizNo) {
            write(bizNo);
            throw new IllegalStateException("触发回滚");
        }

        private PlayBizRecord newOrder(String bizNo) {
            PlayBizRecord record = new PlayBizRecord();
            record.setPlayBizRecordNo(bizNo);
            record.setActivityId(ACTIVITY_ID);
            record.setSkuId(SKU_ID);
            record.setUserId("U_txProbe");
            record.setClientReqNo("REQ_" + bizNo);
            record.setQuantity(1);
            record.setPayStatus(PayStatus.WAIT_PAY.name());
            record.setGrantStatus(GrantStatus.NOT_START.name());
            record.setRefundStatus(RefundStatus.NONE.name());
            record.setOrderAmount(SALE_PRICE);
            record.setCurrency("CNY");
            record.setConfigVersion(1);
            record.setPriceSnapshot("{}");
            record.setBenefitSnapshot("[]");
            record.setExpireTime(LocalDateTime.now().plusMinutes(30));
            return record;
        }
    }
}
