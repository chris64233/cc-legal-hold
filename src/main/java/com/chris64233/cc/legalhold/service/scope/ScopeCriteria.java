package com.chris64233.cc.legalhold.service.scope;

import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.web.dto.ScopeCriteriaRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 范围条件的服务端不可变表示。命中 = 显式业务标识命中 或
 * （类别/时间选择器生效 且 类别命中 且 创建时间落在 [after, before] 窗口内）。
 *
 * <p>选择器仅在至少提供一个维度（类别集合非空 或 任一时间边界）时才生效；
 * 只给显式业务标识时范围恰好等于这些标识，不会因“空类别=不限类别”而扩散到全部对象。
 */
public record ScopeCriteria(
        Set<String> businessKeys,
        Set<String> categories,
        Instant createdAfter,
        Instant createdBefore) {

    public static ScopeCriteria from(ScopeCriteriaRequest request) {
        return new ScopeCriteria(
                Set.copyOf(request.safeBusinessKeys()),
                Set.copyOf(request.safeCategories()),
                request.createdAfter(),
                request.createdBefore());
    }

    /** 关闭案件时目标范围强制为空。 */
    public static ScopeCriteria empty() {
        return new ScopeCriteria(Set.of(), Set.of(), null, null);
    }

    private boolean selectorActive() {
        return !categories.isEmpty() || createdAfter != null || createdBefore != null;
    }

    public boolean matches(DataObject dataObject) {
        if (businessKeys.contains(dataObject.getBusinessKey())) {
            return true;
        }
        if (!selectorActive()) {
            return false;
        }
        if (!categories.isEmpty() && !categories.contains(dataObject.getCategory())) {
            return false;
        }
        Instant createdAt = dataObject.getCreatedAt();
        if (createdAfter != null && createdAt.isBefore(createdAfter)) {
            return false;
        }
        if (createdBefore != null && createdAt.isAfter(createdBefore)) {
            return false;
        }
        return true;
    }

    public List<String> businessKeyList() {
        return businessKeys.stream().sorted().toList();
    }
}
