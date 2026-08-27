package com.example.healthreport.dish;

import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 合并一道菜在各维度上的五态判定，顺序固定：
 * 先看能否产生拒绝的维度是否缺数据（缺则隐藏），再看是否有拒绝，最后才看推荐。
 * 顺序不能换——先取推荐会让该拦的菜混进推荐列表。
 */
@Service
public class TagStateResolver {

    /** 先过敏拒绝、再其他拒绝、再完整性门槛、再推荐，最后中立。 */
    public DishDisposition resolve(List<Fact> factList) {
        if (factList == null) {
            throw new IllegalArgumentException("标签事实不能为空");
        }
        for (Fact fact : factList) {
            if (fact.isAllergy() && fact.getState() == TagState.REJECT) {
                return DishDisposition.NOT_RECOMMENDED;
            }
        }
        for (Fact fact : factList) {
            if (fact.getState() == TagState.REJECT) {
                return DishDisposition.NOT_RECOMMENDED;
            }
        }
        for (Fact fact : factList) {
            if (fact.isRejectCapable() && (fact.getState() == TagState.TAG_MISSING
                    || fact.getState() == TagState.UNKNOWN)) {
                return DishDisposition.HIDDEN;
            }
        }
        for (Fact fact : factList) {
            if (fact.getState() == TagState.RECOMMEND) {
                return DishDisposition.RECOMMENDED;
            }
        }
        return DishDisposition.NEUTRAL;
    }

    /** 一个维度参与裁决所需的最小事实。 */
    @Getter
    public static final class Fact {
        private final TagState state;
        private final boolean rejectCapable;
        private final boolean allergy;

        public Fact(TagState state, boolean rejectCapable, boolean allergy) {
            if (state == null) {
                throw new IllegalArgumentException("维度状态不能为空");
            }
            this.state = state;
            this.rejectCapable = rejectCapable;
            this.allergy = allergy;
        }
    }
}
