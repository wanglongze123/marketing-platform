package com.mp.api.activity.service;

import com.mp.api.activity.dto.ActivityConfResp;
import com.mp.api.activity.dto.CreateActivityReq;
import com.mp.api.activity.dto.PublishActivityResp;
import com.mp.api.activity.dto.QualifyReq;
import com.mp.api.activity.dto.QualifyResp;

/**
 * 活动配置与资格决策（公共能力层）。
 *
 * <p><b>Token 解密不在本服务</b>（技术方案 §4.2）：解密结果含裂变组等玩法私有概念，放公共能力层 就成了公共层依赖玩法层，方向反了。签名器做成公共 SDK（{@code
 * mp-common} 的 {@code ConsultTokenSigner}），各玩法自行调用；activity 只提供规则配置。
 *
 * <p><b>SKU / 权益包 / 权益项的运营配置本期不提供接口</b>，由 SQL 初始化 —— 在此显式声明范围， 而非默默省略。
 */
public interface ActivityService {

    /** 查活动配置。V3 直接查库无缓存，V4 加本地缓存 + 定时刷新（技术方案 §7.7）。 */
    ActivityConfResp queryActivityConf(String activityId);

    /**
     * 创建活动，建为 {@code DRAFT}（FR-C01）。
     *
     * <p>配置此时允许不完整，完整性由 {@link #publishActivity} 把关。同 {@code clientReqNo} 重复提交 返回原活动，不新建。
     *
     * @return 活动号
     */
    String createActivity(CreateActivityReq req);

    /**
     * 发布活动：六项校验 → 生成不可变版本快照 → {@code DRAFT → SCHEDULED}（BR-C-04）。
     *
     * <p><b>校验不过则不生成版本、不改状态</b>：留下一个「版本已生成但状态没推进」的中间态，会让 下一次发布拿到一个跳号的版本，而那个号对应的配置从未生效过。
     *
     * <p>版本一经生成不可变（BR-C-03），历史单据据此履约（BR-C-05）。
     */
    PublishActivityResp publishActivity(String activityId, String operator);

    /**
     * 变更活动状态（PRD §4.1 的流转表）。
     *
     * <p>非法迁移抛 {@code 4103} 而非静默忽略 —— 后者会让运营以为改成功了，而后台看到的状态与操作 不符且无错可查。
     *
     * @param opSeq 调用方请求号，同活动同状态的重复请求靠它去重
     */
    void changeActivityStatus(
            String activityId, String targetStatus, String opSeq, String operator);

    /**
     * 资格决策：人群、城市、渠道、风控四维判定（FR-C02）。
     *
     * <p><b>只读，无任何副作用</b>。返回标准原因码，区分「不符合条件」（{@code 1201}）与「系统异常」 （{@code
     * 5201}）；咨询阶段通过不代表创建单据时通过（BR-C-08）。
     */
    QualifyResp decideQualification(QualifyReq req);
}
