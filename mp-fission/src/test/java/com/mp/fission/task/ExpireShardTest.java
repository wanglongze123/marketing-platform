package com.mp.fission.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 分片区间的划分，纯逻辑，不依赖数据库。
 *
 * <p><b>这是 V3 单进程唯一无法由集成测试覆盖的部分</b>：单进程恒为 1 个分片，{@code fromId=0}、 {@code
 * toId=Long.MAX_VALUE}，多分片的划分逻辑一行都执行不到。而 V4 加分片时改的只是「我是 第几个分片」的来源，划分算法不改 ——
 * 现在不测，届时是在一条会扫百万行的批量语句上验证 一段从未运行过的算术。
 *
 * <p>命名为 {@code *Test} 而非 {@code *IT}：无外部依赖。
 */
class ExpireShardTest {

    /**
     * <b>区间的并集必须是整个 {@code id} 空间，不留缝</b>。
     *
     * <p>首片下界取 0、末片上界取 {@link Long#MAX_VALUE}，而非 {@code MIN(id)} / {@code MAX(id)} 本身 ——
     * 后者会让「算完区间之后才插入的行」落在所有分片之外。那些行此刻尚未到期，下一轮重算区间 即被覆盖，但把覆盖建立在「它们还不到期」这个时序假设上，不如让区间本身无缝。
     */
    @Test
    void shardsTileTheEntireIdSpaceWithoutGaps() {
        int total = 4;
        long min = 100;
        long max = 999;

        long prevTo = -1;
        for (int i = 0; i < total; i++) {
            ExpireShard shard = new ExpireShard(i, total);
            long from = shard.fromId(min, max);
            long to = shard.toId(min, max);

            assertThat(from).as("分片 %d 须紧接上一片，不留缝也不重叠", i).isEqualTo(prevTo + 1);
            assertThat(to).as("分片 %d 上界须不小于下界", i).isGreaterThanOrEqualTo(from);
            prevTo = to;
        }

        assertThat(new ExpireShard(0, total).fromId(min, max)).as("首片下界须为 0").isZero();
        assertThat(prevTo).as("末片上界须为 Long.MAX_VALUE").isEqualTo(Long.MAX_VALUE);
    }

    /** 单分片（V3 单进程的实际取值）覆盖全空间。 */
    @Test
    void singleShardCoversEverything() {
        ExpireShard only = new ExpireShard(0, 1);
        assertThat(only.fromId(1, 1000)).isZero();
        assertThat(only.toId(1, 1000)).isEqualTo(Long.MAX_VALUE);
    }

    /**
     * <b>行数不能被分片数整除时，余数逐片分摊而非全压在最后一片</b>。
     *
     * <p>1000 行分 3 片：334 / 333 / 333，而非 333 / 333 / 334。前者各片负载相差至多 1；后者在 分片数大时最后一片会显著偏重 ——
     * 而分片的全部意义就是均摊。
     */
    @Test
    void remainderIsSpreadAcrossShardsNotDumpedOnTheLast() {
        int total = 3;
        long min = 1;
        long max = 1000;

        // 中间片是唯一两端都由算术决定的片，首末两片各有一端被钉死
        ExpireShard mid = new ExpireShard(1, total);
        long midSpan = mid.toId(min, max) - mid.fromId(min, max) + 1;

        ExpireShard first = new ExpireShard(0, total);
        // 首片下界为 0，其「有效」跨度从 min 起算
        long firstSpan = first.toId(min, max) - min + 1;

        assertThat(Math.abs(firstSpan - midSpan)).as("各片跨度相差至多 1").isLessThanOrEqualTo(1);
        assertThat(firstSpan).isEqualTo(334);
        assertThat(midSpan).isEqualTo(333);
    }

    /** 跨度极大时不溢出 —— 入参来自自增主键，没有上界保证。 */
    @Test
    void hugeSpanDoesNotOverflow() {
        long min = 1;
        long max = Long.MAX_VALUE - 1;
        int total = 7;

        long prevTo = -1;
        for (int i = 0; i < total; i++) {
            ExpireShard shard = new ExpireShard(i, total);
            long from = shard.fromId(min, max);
            long to = shard.toId(min, max);
            assertThat(from).as("分片 %d 不得因溢出跳变为负", i).isEqualTo(prevTo + 1);
            assertThat(to).isGreaterThanOrEqualTo(from);
            prevTo = to;
        }
        assertThat(prevTo).isEqualTo(Long.MAX_VALUE);
    }

    /**
     * {@code bizNo} 与 {@code parse} 互为逆。
     *
     * <p>两者是同一处事实的两面，改一个必须改另一个 —— 本用例是那条约束的机器化形式。
     */
    @Test
    void bizNoRoundTrips() {
        ExpireShard shard = new ExpireShard(2, 5);
        assertThat(shard.bizNo()).isEqualTo("EXPIRE_SHARD_2_OF_5");
        assertThat(ExpireShard.parse(shard.bizNo())).isEqualTo(shard);
    }

    /**
     * 非分片键抛异常而非静默返回一个默认分片。
     *
     * <p>静默降级的后果是：处理器把任意任务的 {@code biz_no} 当成分片键，扫一个由无关字符串算出的区间 —— 而它会「成功」返回 0 行，看起来一切正常。
     */
    @Test
    void parseRejectsForeignKeys() {
        assertThatThrownBy(() -> ExpireShard.parse("FR_something"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpireShard.parse("EXPIRE_SHARD_0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpireShard.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 越界的分片序号在构造时即拒绝，不等到算出一个空区间才发现。 */
    @Test
    void outOfRangeIndexIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new ExpireShard(3, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExpireShard(-1, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExpireShard(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
