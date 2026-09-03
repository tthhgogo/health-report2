package com.example.healthreport.assemble.dishrecommend;

import com.github.promeg.pinyinhelper.Pinyin;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 菜名拼音首字母稳定排序（需求 §8-4/§8-5）。
 * <p>请求时实时计算不落库；非汉字开头的菜名统一排在汉字之后。
 * 原属 DisplayOrder，排序总则删除后按职责移入模块四包。</p>
 */
@Component
public class DishNameSorter {

    /** 参与排序的菜品条目。 */
    public interface DishNameItem {

        /** 菜品展示名。 */
        String getDishName();

        /** 企业内唯一菜品 ID，用于稳定打破同名平局。 */
        long getDishId();
    }

    /** 返回排序后的不可变副本，不修改入参。 */
    public <T extends DishNameItem> List<T> sortByPinyin(List<T> sourceList) {
        if (sourceList == null) {
            throw new IllegalArgumentException("菜品排序输入不能为空");
        }
        List<T> resultList = new ArrayList<T>(sourceList);
        Collections.sort(resultList, new Comparator<T>() {
            @Override
            public int compare(T left, T right) {
                boolean leftChinese = startsWithChinese(left.getDishName());
                boolean rightChinese = startsWithChinese(right.getDishName());
                if (leftChinese != rightChinese) {
                    return leftChinese ? -1 : 1;
                }
                String leftPinyin = Pinyin.toPinyin(left.getDishName(), "");
                String rightPinyin = Pinyin.toPinyin(right.getDishName(), "");
                int pinyinResult = leftPinyin.compareToIgnoreCase(rightPinyin);
                if (pinyinResult != 0) {
                    return pinyinResult;
                }
                int nameResult = left.getDishName().compareTo(right.getDishName());
                if (nameResult != 0) {
                    return nameResult;
                }
                return Long.compare(left.getDishId(), right.getDishId());
            }
        });
        return Collections.unmodifiableList(resultList);
    }

    private boolean startsWithChinese(String dishName) {
        return dishName != null && !dishName.isEmpty() && Pinyin.isChinese(dishName.charAt(0));
    }
}
