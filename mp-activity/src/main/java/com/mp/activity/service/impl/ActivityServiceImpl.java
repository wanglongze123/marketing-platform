package com.mp.activity.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mp.activity.entity.MarketingActivity;
import com.mp.activity.repository.MarketingActivityMapper;
import com.mp.api.activity.dto.ActivityConfResp;
import com.mp.api.activity.service.ActivityService;
import java.time.LocalDateTime;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;

/** 活动配置查询。V1 直接查库无缓存，V3 加本地缓存 + 版本快照。 */
@DubboService
@Service
public class ActivityServiceImpl implements ActivityService {

    private final MarketingActivityMapper activityMapper;

    public ActivityServiceImpl(MarketingActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Override
    public ActivityConfResp queryActivityConf(String activityId) {
        MarketingActivity a =
                activityMapper.selectOne(
                        Wrappers.<MarketingActivity>lambdaQuery()
                                .eq(MarketingActivity::getActivityId, activityId)
                                .eq(MarketingActivity::getDeleted, 0));
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

        LocalDateTime now = LocalDateTime.now();
        resp.setAvailable(
                "ONLINE".equals(a.getStatus())
                        && now.isAfter(a.getStartTime())
                        && now.isBefore(a.getEndTime()));
        return resp;
    }
}
