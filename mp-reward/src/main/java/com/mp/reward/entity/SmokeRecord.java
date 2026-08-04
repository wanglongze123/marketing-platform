package com.mp.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** V0 冒烟表实体，验证 Flyway + MyBatis-Plus + 事务。<b>V1 结束时删除</b>，它是脚手架不是功能。 */
@TableName("smoke_record")
public class SmokeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizNo;

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
