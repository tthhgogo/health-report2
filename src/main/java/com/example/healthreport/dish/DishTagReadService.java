package com.example.healthreport.dish;

import com.example.healthreport.cache.DishTagCache;
import com.example.healthreport.constants.PromptVersions;
import com.example.healthreport.constants.TagRuleVersion;
import com.example.healthreport.llm.dishtag.DishTagProperties;
import com.example.healthreport.persistence.CtDishTagEntity;
import com.example.healthreport.persistence.CtDishTagService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在线标签读取：Redis 未命中必须回源 MySQL，仍缺失才产生 TAG_MISSING。 */
@Service
public class DishTagReadService {

    private final TagHashCalculator tagHashCalculator;
    private final DishTagProperties properties;
    private final DishTagCache dishTagCache;
    private final CtDishTagService persistenceService;
    private final DishTagService dishTagService;

    public DishTagReadService(TagHashCalculator tagHashCalculator, DishTagProperties properties,
                              DishTagCache dishTagCache, CtDishTagService persistenceService,
                              DishTagService dishTagService) {
        this.tagHashCalculator = tagHashCalculator;
        this.properties = properties;
        this.dishTagCache = dishTagCache;
        this.persistenceService = persistenceService;
        this.dishTagService = dishTagService;
    }

    /**
     * 读取生效维度的全部菜品标签，返回 {@code enumKey -> dishId -> TagValue}。
     */
    public Map<String, Map<Long, TagValue>> read(LocalDate bizDate, List<Dish> dishList,
                                                 Set<String> effectiveEnumKeySet) {
        if (bizDate == null || dishList == null || effectiveEnumKeySet == null) {
            throw new IllegalArgumentException("标签读取输入不能为空");
        }
        Map<Long, String> hashByDishIdMap = new LinkedHashMap<Long, String>(dishList.size());
        Set<Long> dishIdSet = new LinkedHashSet<Long>(dishList.size());
        Set<String> tagHashSet = new LinkedHashSet<String>(dishList.size());
        for (Dish dish : dishList) {
            String tagHash = tagHashCalculator.calculate(TagRuleVersion.VALUE,
                    PromptVersions.DISH_TAG, properties.getModelVersionDishtag(), dish);
            hashByDishIdMap.put(dish.getDishId(), tagHash);
            dishIdSet.add(dish.getDishId());
            tagHashSet.add(tagHash);
        }

        Map<String, Map<Long, TagValue>> resultMap = new LinkedHashMap<String, Map<Long, TagValue>>();
        List<Missing> missingList = new ArrayList<Missing>();
        for (String enumKey : effectiveEnumKeySet) {
            List<String> fieldList = new ArrayList<String>(dishList.size());
            for (Dish dish : dishList) {
                fieldList.add(dishTagCache.field(dish.getDishId(),
                        hashByDishIdMap.get(dish.getDishId())));
            }
            Map<String, TagValue> cachedByFieldMap = dishTagCache.getAll(bizDate, enumKey,
                    fieldList);
            Map<Long, TagValue> valueByDishIdMap = new LinkedHashMap<Long, TagValue>(dishList.size());
            for (Dish dish : dishList) {
                String field = dishTagCache.field(dish.getDishId(),
                        hashByDishIdMap.get(dish.getDishId()));
                TagValue value = cachedByFieldMap.get(field);
                if (value == null) {
                    missingList.add(new Missing(enumKey, dish.getDishId(),
                            hashByDishIdMap.get(dish.getDishId())));
                } else {
                    valueByDishIdMap.put(dish.getDishId(), value);
                }
            }
            resultMap.put(enumKey, valueByDishIdMap);
        }

        List<CtDishTagEntity> databaseEntityList = missingList.isEmpty()
                ? Collections.<CtDishTagEntity>emptyList()
                : persistenceService.findCandidates(dishIdSet, tagHashSet, effectiveEnumKeySet);
        Map<String, CtDishTagEntity> databaseByTripleMap = new HashMap<String, CtDishTagEntity>();
        for (CtDishTagEntity entity : databaseEntityList) {
            databaseByTripleMap.put(triple(entity.getEnumKey(), entity.getDishId(),
                    entity.getTagHash()), entity);
        }
        for (Missing missing : missingList) {
            CtDishTagEntity entity = databaseByTripleMap.get(triple(missing.enumKey,
                    missing.dishId, missing.tagHash));
            TagValue value = entity == null ? TagValue.of(TagState.TAG_MISSING)
                    : dishTagService.toTagValue(entity);
            resultMap.get(missing.enumKey).put(missing.dishId, value);
        }
        return resultMap;
    }

    private String triple(String enumKey, long dishId, String tagHash) {
        return enumKey + "|" + dishId + "|" + tagHash;
    }

    /** 一个等待数据库回源的精确组合。 */
    private static final class Missing {
        private final String enumKey;
        private final long dishId;
        private final String tagHash;

        private Missing(String enumKey, long dishId, String tagHash) {
            this.enumKey = enumKey;
            this.dishId = dishId;
            this.tagHash = tagHash;
        }
    }
}
