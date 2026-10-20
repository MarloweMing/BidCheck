package cn.bidcheck.persistence;
public class TaskRow {
    public String id;
    public String projectId;
    public String taskType;
    public String inputJson;
    public String status;
    public String stage;
    public int progress;
    public int attempt;
    public String error;
    public String createdAt;
    public String updatedAt;
    public String leaseOwner;
    public Long leaseUntil;
}
