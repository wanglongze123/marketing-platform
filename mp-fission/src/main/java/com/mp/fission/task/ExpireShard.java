package com.mp.fission.task;

/**
 * 过期治理的分片：一个连续的 {@code id} 区间，即一个工作单元。
 *
 * <p><b>分片用区间而非取模</b>（技术方案 §3.3）：{@code id % shardTotal = shardIndex} 是函数运算，无法
 * 走索引，批量语句会退化为全表扫描。连续区间既走 {@code idx_expire} 又互不重叠。
 *
 * <p><b>分片写进任务的 {@code biz_no}，不由执行侧读配置</b>。两种做法在 V3 单进程下不可区分 （只有一个分片），差别在
 * V4：任务表的领取不分片，任一实例都可能领到任一分片的任务 —— 执行侧 若按自己的配置算区间，领到 1 号分片的实例会去扫 0 号分片的范围，两个分片的差集永远无人扫描。
 * 把区间的身份放进任务键，谁领到谁就扫谁，领取与分片解耦。
 *
 * <p>连带的一个好处：{@code shardTotal} 改配置后，旧的 {@code EXPIRE_SHARD_0_OF_1} 任务仍会按 旧划分扫一遍（幂等，只是与新分片重叠），跑完置
 * {@code DONE} 且不再被播种，自然退场。
 *
 * <p><b>区间的并集必须是整个 {@code id} 空间</b>，故首片下界取 0、末片上界取 {@link Long#MAX_VALUE}， 而非 {@code MIN(id)} /
 * {@code MAX(id)} 本身：后者会让「算区间之后才插入的行」落在所有分片之外。 那些行此刻尚未到期，下一轮重算区间即被覆盖，但把覆盖建立在「它们还不到期」这个时序假设上，
 * 不如让区间本身无缝。
 *
 * @param index 分片序号，从 0 起
 * @param total 分片总数
 */
public record ExpireShard(int index, int total) {

    private static final String PREFIX = "EXPIRE_SHARD_";

    private static final String SEP = "_OF_";

    public ExpireShard {
        if (total <= 0) {
            throw new IllegalArgumentException("分片总数须为正数，实际 " + total);
        }
        if (index < 0 || index >= total) {
            throw new IllegalArgumentException("分片序号越界: " + index + "/" + total);
        }
    }

    /** 任务的 {@code biz_no}。格式与 {@link #parse} 是同一处事实，改一个必须改另一个。 */
    public String bizNo() {
        return PREFIX + index + SEP + total;
    }

    /** 由任务的 {@code biz_no} 还原分片。 */
    public static ExpireShard parse(String bizNo) {
        if (bizNo == null || !bizNo.startsWith(PREFIX)) {
            throw new IllegalArgumentException("非过期治理分片键: " + bizNo);
        }
        String body = bizNo.substring(PREFIX.length());
        int sep = body.indexOf(SEP);
        if (sep < 0) {
            throw new IllegalArgumentException("非过期治理分片键: " + bizNo);
        }
        try {
            return new ExpireShard(
                    Integer.parseInt(body.substring(0, sep)),
                    Integer.parseInt(body.substring(sep + SEP.length())));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非过期治理分片键: " + bizNo, e);
        }
    }

    /**
     * 本分片的 {@code id} 下界（含）。
     *
     * <p>用整数除法 + 余数分摊而非 {@code span * index / total}：后者在 {@code span} 很大时先乘后除会溢出，
     * 而这条语句的入参来自自增主键，没有上界保证。
     */
    public long fromId(long minId, long maxId) {
        if (index == 0) {
            return 0;
        }
        long span = maxId - minId + 1;
        return minId + span / total * index + Math.min(index, span % total);
    }

    /** 本分片的 {@code id} 上界（含）。 */
    public long toId(long minId, long maxId) {
        if (index == total - 1) {
            return Long.MAX_VALUE;
        }
        long span = maxId - minId + 1;
        return minId + span / total * (index + 1) + Math.min(index + 1L, span % total) - 1;
    }
}
