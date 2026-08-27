package com.example.healthreport.dish;

import com.example.healthreport.persistence.CtDishTagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/** 离线打标完成后的标签清理步骤；不是独立调度 Handler。 */
@Slf4j
@Service
public class DishTagCleanupService {

    private final CtDishTagService dishTagService;

    public DishTagCleanupService(CtDishTagService dishTagService) {
        this.dishTagService = dishTagService;
    }

    /** 使用调度入口传入的同一业务日，按 5000 行循环清理。 */
    public void run(LocalDate bizDate) {
        if (bizDate == null) {
            throw new IllegalArgumentException("业务日不能为空");
        }
        int deletedRows;
        int totalDeletedRows = 0;
        int roundCount = 0;
        do {
            deletedRows = dishTagService.deleteExpiredBatch(bizDate);
            totalDeletedRows += deletedRows;
            roundCount++;
        } while (deletedRows > 0);
        // §9.2 要求记「清理任务每轮的删除计数」。轮次数一起记：
        // 这个循环没有单轮上限，轮次异常多就是这张表在涨而不是在收敛。
        log.info("菜品标签清理完成，业务日={}，删除行数={}，循环轮次={}",
                bizDate, totalDeletedRows, roundCount);
    }
}
