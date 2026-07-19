package com.demo.agentscope.skill;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 技能实体。
 * <p>
 * 字段对应 {@code workspace/skills/manifests/{id}.json} 的完整内容；同时也是
 * {@code index.json} 中每条摘要的超集。Jackson 注解保证序列化稳定且无 null 字段污染。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "id", "slug", "name", "description",
        "tags", "steps", "cases", "successCases", "resources",
        "status", "version", "author", "visibility",
        "createdAt", "updatedAt", "publishedAt", "lastUsedAt", "useCount"
})
public class Skill {

    private String id;
    private String slug;
    private String name;
    private String description;

    private List<String> tags = new ArrayList<>();
    private List<String> steps = new ArrayList<>();
    private List<String> cases = new ArrayList<>();
    private List<String> successCases = new ArrayList<>();
    private List<String> resources = new ArrayList<>();

    private SkillStatus status = SkillStatus.DRAFT;
    private int version = 1;
    private String author;
    private String visibility = "public";

    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;
    private Instant lastUsedAt;

    private int useCount;

    public Skill() {
        // Jackson 反序列化需要无参构造器
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }

    public List<String> getSteps() { return steps; }
    public void setSteps(List<String> steps) { this.steps = steps == null ? new ArrayList<>() : steps; }

    public List<String> getCases() { return cases; }
    public void setCases(List<String> cases) { this.cases = cases == null ? new ArrayList<>() : cases; }

    @JsonProperty("successCases")
    public List<String> getSuccessCases() { return successCases; }
    @JsonProperty("successCases")
    public void setSuccessCases(List<String> successCases) {
        this.successCases = successCases == null ? new ArrayList<>() : successCases;
    }

    public List<String> getResources() { return resources; }
    public void setResources(List<String> resources) {
        this.resources = resources == null ? new ArrayList<>() : resources;
    }

    public SkillStatus getStatus() { return status; }
    public void setStatus(SkillStatus status) { this.status = status == null ? SkillStatus.DRAFT : status; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public int getUseCount() { return useCount; }
    public void setUseCount(int useCount) { this.useCount = useCount; }
}
