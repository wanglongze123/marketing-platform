package com.mp.api.activity.service;

import com.mp.api.activity.dto.ActivityConfResp;

/** 活动配置（公共能力层）。V1 只读，管理接口由 seed SQL 替代。 */
public interface ActivityService {

    /** 查活动配置。V1 直接查库无缓存，V3 加本地缓存 + 定时刷新。 */
    ActivityConfResp queryActivityConf(String activityId);
}
