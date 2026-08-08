package com.mp.fission.service;

import com.mp.api.reward.dto.RewardItem;
import java.util.List;

/**
 * 裂变奖励项的构造，<b>三个约定值集中在此一处</b>。
 *
 * <p>{@code RewardItem} 的六个字段里有三个对裂变无语义（《分阶段方案》§6.3 的填表结论）：
 *
 * <table>
 *   <tr><th>字段</th><th>裂变取值</th><th>依据</th></tr>
 *   <tr><td>{@code providerType}</td><td>{@code PLATFORM}</td>
 *       <td>裂变奖励由平台直发，无外部供应方；平台直发是一种发放渠道</td></tr>
 *   <tr><td>{@code providerProductId}</td><td>奖励配置 id</td>
 *       <td>「发什么」的标识，与供应方商品 id 同位</td></tr>
 *   <tr><td>{@code core}</td><td>恒 {@code true}</td>
 *       <td>「核心项失败需回收附加项后退款」是权益售卖概念，裂变纯补贴、无逆向</td></tr>
 * </table>
 *
 * <p><b>这是妥协而非设计</b>：三个字段对裂变是「填了但不承载原意」。选它而非改 {@code RewardItem} 的理由是代价对比 —— 改 DTO 要动 {@code
 * mp-api} 的公共契约，牵连已合入的权益售卖链路与 {@code ShapeFreezeTest} 的字段断言；而约定值方案下 {@code reward} 接口零改动。
 *
 * <p><b>妥协是可逆的</b>：V4 若接入真实的裂变奖励供应方，{@code PLATFORM} 变成真实渠道之一，约定值 自然退场。
 *
 * <p>集中在一个工厂而非散在调用点：三个值散落后，下次有人在别处构造奖励项时不会知道它们是约定 而非事实，也就不会去改。
 */
public final class RewardItemFactory {

    private RewardItemFactory() {}

    /** 裂变奖励的发放渠道标识。平台直发，非外部供应方 */
    public static final String PLATFORM = "PLATFORM";

    /**
     * 构造一组裂变奖励项。
     *
     * @param rewardConfigIds 奖励配置 id，决定「发什么」
     */
    public static List<RewardItem> of(String rewardType, List<String> rewardConfigIds) {
        List<RewardItem> items = new java.util.ArrayList<>(rewardConfigIds.size());
        for (int i = 0; i < rewardConfigIds.size(); i++) {
            RewardItem item = new RewardItem();
            // 组内下标从 0 起连续编号，uk_op_item(op_no, item_seq) 的第一维已隔开不同次调用
            item.setItemSeq(i);
            item.setRewardType(rewardType);
            item.setProviderType(PLATFORM);
            item.setProviderProductId(rewardConfigIds.get(i));
            item.setQty(1);
            // 裂变无附加项概念，全部奖励项等价
            item.setCore(true);
            items.add(item);
        }
        return items;
    }
}
