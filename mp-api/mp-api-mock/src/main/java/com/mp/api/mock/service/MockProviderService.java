package com.mp.api.mock.service;

/** mock 供应方。V0 只有 smoke，V1 加 grant。 */
public interface MockProviderService {

    /** V0 冒烟：返回本层标识。V1 结束时删除。 */
    String smoke(String bizNo);
}
