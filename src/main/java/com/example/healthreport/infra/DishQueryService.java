package com.example.healthreport.infra;

import com.example.healthreport.dish.Dish;

import java.time.LocalDate;
import java.util.List;

/**
 * 当日在架菜品及食材查询边界。
 * <p>接口只读；实现不得写入或修改 {@code ct_dish}/{@code ct_dish_ingredient}。</p>
 *
 * <p><b>返回值契约</b>：列表非 {@code null}、不含 {@code null} 元素；
 * <b>空列表是合法结果</b>（当日无在架菜品），不得用 {@code null} 表示。
 * 消费方用 {@link #assertValidResult(List)} 在边界上校验一次，
 * 不要让不合法的返回值漏进下游——那里只会炸出没有上下文的 NPE。</p>
 */
public interface DishQueryService {

    /** TODO 查询指定业务日的在架菜品及其食材。 */
    default List<Dish> queryOnShelfDishes(LocalDate bizDate) {
        throw new UnsupportedOperationException("DishQueryService尚未实现");
    }

    /**
     * 在边界上校验查询结果。
     *
     * <p>放在接口上而不是各消费方内部，是因为这是<b>接口的契约</b>：
     * 实现方和消费方看同一段说明、同一个校验，不会各写各的。
     * 菜品自身的完整性（食材列表不含 null 等）由 {@link Dish} 的构造器保证，本方法不重复。</p>
     *
     * @throws IllegalStateException 列表为 null 或含 null 元素
     */
    static List<Dish> assertValidResult(List<Dish> dishList) {
        if (dishList == null) {
            throw new IllegalStateException(
                    "DishQueryService 返回 null；当日无菜品应返回空列表而不是 null");
        }
        if (dishList.contains(null)) {
            throw new IllegalStateException("DishQueryService 返回的菜品列表含 null 元素");
        }
        return dishList;
    }
}
