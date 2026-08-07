package com.mp.activity.service.impl;

import com.mp.activity.repository.MarketingActivityMapper;
import com.mp.api.activity.dto.ActivityConfResp;
import com.mp.api.activity.service.ActivityService;
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
}
