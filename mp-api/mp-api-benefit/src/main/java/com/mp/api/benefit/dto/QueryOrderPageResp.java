package com.mp.api.benefit.dto;

import java.util.List;

/**
 * 订单列表出参。
 *
 * <p>带 {@code total} 而非只回一页数据：客服排查需要知道「共几笔」才能判断是否还有下一页。
 */
public class QueryOrderPageResp {

    private List<OrderListItem> items;

    /** 符合条件的总行数，不受分页影响 */
    private long total;

    private int page;
    private int size;

    public List<OrderListItem> getItems() {
        return items;
    }

    public void setItems(List<OrderListItem> items) {
        this.items = items;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
