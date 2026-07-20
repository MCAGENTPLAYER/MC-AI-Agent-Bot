package com.aibot.mod.entity;

import com.google.gson.annotations.SerializedName;

/**
 * AI 规划的任务单元
 */
public class Task {
    /** 任务类型: chop_wood, mine_stone, mine_ore, craft, eat, sleep, farm, store */
    @SerializedName("type")
    public String type;

    /** 目标数量（chop_wood 8 表示砍8个木头） */
    @SerializedName("count")
    public int count;

    /** 目标物品（craft: "stone_pickaxe"） */
    @SerializedName("item")
    public String item;

    /** 人类可读描述 */
    @SerializedName("desc")
    public String desc;

    public Task() {}

    public Task(String type, int count, String item, String desc) {
        this.type = type;
        this.count = count;
        this.item = item;
        this.desc = desc;
    }
}
