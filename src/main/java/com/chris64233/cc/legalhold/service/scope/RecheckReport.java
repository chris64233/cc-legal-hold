package com.chris64233.cc.legalhold.service.scope;

import com.chris64233.cc.legalhold.domain.DataObject;
import java.util.List;

/**
 * 终确认重核验结果。整批维度：任一条目不匹配则整个版本拒绝。
 */
public record RecheckReport(
        boolean allMatch,
        List<Item> items) {

    public static RecheckReport ok(List<Item> items) {
        return new RecheckReport(true, items);
    }

    public static RecheckReport failed(List<Item> items) {
        return new RecheckReport(false, items);
    }

    /**
     * @param matched     提交时约束与确认时是否一致
     * @param change      变化说明（matched=true 时为 null）
     */
    public record Item(
            Long objectId,
            String businessKey,
            boolean matched,
            List<String> snapshotOtherCases,
            List<String> currentOtherCases,
            String snapshotRetentionDeadline,
            String currentRetentionDeadline,
            String change) {

        public static Item unchanged(DataObject object, ReleaseConstraintSnapshot snapshot,
                                     List<String> currentOtherCases,
                                     String currentDeadline) {
            return new Item(object.getId(), object.getBusinessKey(), true,
                    snapshot.otherCases(), currentOtherCases,
                    snapshot.retentionDeadline() == null ? null
                            : snapshot.retentionDeadline().toString(),
                    currentDeadline, null);
        }

        public static Item changed(DataObject object, ReleaseConstraintSnapshot snapshot,
                                   List<String> currentOtherCases,
                                   String currentDeadline, String change) {
            return new Item(object.getId(), object.getBusinessKey(), false,
                    snapshot.otherCases(), currentOtherCases,
                    snapshot.retentionDeadline() == null ? null
                            : snapshot.retentionDeadline().toString(),
                    currentDeadline, change);
        }
    }
}
