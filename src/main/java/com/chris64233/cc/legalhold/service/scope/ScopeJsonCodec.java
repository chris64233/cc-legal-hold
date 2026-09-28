package com.chris64233.cc.legalhold.service.scope;

import com.chris64233.cc.legalhold.service.ConflictException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 范围条件与释放快照的 JSON 编解码。落库字段不可变，读失败属数据损坏，直接按冲突拒绝。
 * 使用 Spring 容器中已注册 JSR-310 时间模块的 ObjectMapper。
 */
@Component
public class ScopeJsonCodec {

    private final ObjectMapper objectMapper;

    public ScopeJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writeCriteria(ScopeCriteria criteria) {
        return write(new CriteriaPayload(
                criteria.businessKeys().stream().sorted().toList(),
                criteria.categories().stream().sorted().toList(),
                criteria.createdAfter(), criteria.createdBefore()));
    }

    public ScopeCriteria readCriteria(String json) {
        CriteriaPayload payload = read(json, CriteriaPayload.class);
        return new ScopeCriteria(
                payload.businessKeys() == null ? Set.of() : Set.copyOf(payload.businessKeys()),
                payload.categories() == null ? Set.of() : Set.copyOf(payload.categories()),
                payload.createdAfter(), payload.createdBefore());
    }

    public String writeSnapshots(List<ReleaseConstraintSnapshot> snapshots) {
        return write(snapshots);
    }

    public List<ReleaseConstraintSnapshot> readSnapshots(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(json,
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, ReleaseConstraintSnapshot.class)));
        } catch (JacksonException e) {
            throw new ConflictException("范围释放快照损坏: " + e.getMessage());
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new ConflictException("范围数据序列化失败: " + e.getMessage());
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new ConflictException("范围数据损坏: " + e.getMessage());
        }
    }

    private record CriteriaPayload(
            List<String> businessKeys,
            List<String> categories,
            Instant createdAfter,
            Instant createdBefore) {
    }
}
