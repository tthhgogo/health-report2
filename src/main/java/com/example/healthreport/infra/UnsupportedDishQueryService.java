package com.example.healthreport.infra;

import org.springframework.stereotype.Component;

/** 未接入食堂只读查询前的显式失败实现，不返回空菜品假数据。 */
@Component
public class UnsupportedDishQueryService implements DishQueryService {
}
