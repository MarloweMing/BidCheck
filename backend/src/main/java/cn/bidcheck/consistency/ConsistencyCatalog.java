package cn.bidcheck.consistency;

import java.util.*;

/** Versioned business vocabulary shared by extraction, comparisons and presentation. */
public final class ConsistencyCatalog {
    public static final String VERSION = "consistency-catalog-2.1-person-credentials";
    public static final Set<String> DIMENSION_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.listOf("object", "stage", "trigger", "condition", "version", "range", "period", "currency", "taxBasis", "unitBasis", "dayType")));
    private static final Map<String,String> CATEGORIES = new LinkedHashMap<>();
    private static final Map<String,Map<String,Object>> DEFINITIONS = new LinkedHashMap<>();
    static {
        category("project_identity", "项目信息与主体"); category("scope_quantity", "工作范围与数量");
        category("price_structure", "报价结构与费用"); category("schedule", "工期与交付计划");
        category("validity", "有效期与日期"); category("product", "产品身份与配置");
        category("technical", "技术参数与响应"); category("quality", "质量与验收");
        category("warranty", "质保与免费范围"); category("service", "售后时效与时间");
        category("payment", "付款与商务条件"); category("personnel", "人员与投入");
        category("qualification", "资质与证明"); category("performance", "历史业绩");
        category("organization", "联合体与分包"); category("declaration", "声明函与对象");
        category("solution", "方案与承诺"); category("document_reference", "版本与引用");
        add("companyName", "投标企业名称", "project_identity", "IDENTIFIER", "PROJECT", "投标人名称", "投标单位名称");
        add("projectName", "项目名称", "project_identity", "TEXT", "PROJECT", "项目名称", "采购项目名称", "招标项目名称");
        add("projectNumber", "项目编号", "project_identity", "IDENTIFIER", "PROJECT", "项目编号", "采购编号", "招标编号");
        add("purchaserName", "采购人名称", "project_identity", "TEXT", "PROJECT", "采购人名称", "招标人名称");
        add("projectLocation", "项目地点", "project_identity", "TEXT", "PROJECT", "项目地点", "建设地点", "交付地点", "实施地点");
        add("lotName", "标段名称", "project_identity", "TEXT", "OBJECT", "标段名称", "标包名称");
        add("deliveryScope", "交付范围", "scope_quantity", "TEXT", "OBJECT", "交付范围", "工作范围", "供货范围");
        add("quantity", "数量", "scope_quantity", "NUMBER", "OBJECT", "数量", "设备数量", "供货数量");
        add("trainingCount", "培训次数", "scope_quantity", "NUMBER", "OBJECT", "培训次数");
        add("licenseCount", "许可数量", "scope_quantity", "NUMBER", "OBJECT", "许可数量", "账号数量", "授权数量");
        add("totalPrice", "投标总报价", "price_structure", "MONEY", "PROJECT", "投标总报价", "投标总价");
        add("unitPrice", "单价", "price_structure", "MONEY", "OBJECT", "单价", "综合单价");
        add("lineAmount", "行金额", "price_structure", "MONEY", "OBJECT", "行金额", "分项金额", "分项合价", "合价");
        add("taxRate", "税率", "price_structure", "PERCENT", "OBJECT", "税率");
        add("taxAmount", "税额", "price_structure", "MONEY", "OBJECT", "税额");
        add("feeCoverage", "费用包含承诺", "price_structure", "CLAIM", "OBJECT", "费用包含", "费用承担", "收费承诺", "维护费用", "费用承诺");
        add("constructionPeriod", "工期", "schedule", "DURATION", "PROJECT", "工期", "施工工期", "合同工期");
        add("deliveryPeriod", "交货期", "schedule", "DURATION", "PROJECT", "交货期", "交付周期", "交货周期", "交付期限");
        add("servicePeriod", "服务期", "schedule", "DURATION", "OBJECT", "服务期", "服务期限", "服务周期");
        add("plannedCompletionPeriod", "计划完成周期", "schedule", "DURATION", "PROJECT", "计划完成周期", "计划交付周期", "计划交货周期", "计划完成期限");
        add("startDate", "开工日期", "schedule", "DATE", "PROJECT", "开工日期", "计划开工日期");
        add("completionDate", "竣工日期", "schedule", "DATE", "PROJECT", "竣工日期", "计划竣工日期");
        add("openingTime", "开标时间", "validity", "DATE", "PROJECT", "开标时间", "开标日期");
        add("bidValidity", "投标有效期", "validity", "DURATION", "PROJECT", "投标有效期", "响应有效期");
        add("authorizationExpiry", "授权到期日", "validity", "DATE", "OBJECT", "授权到期日", "授权截止日期");
        add("guaranteeExpiry", "保函到期日", "validity", "DATE", "OBJECT", "保函到期日", "保函截止日期");
        add("productBrand", "产品品牌", "product", "IDENTIFIER", "OBJECT", "品牌", "产品品牌");
        add("productModel", "产品型号", "product", "IDENTIFIER", "OBJECT", "型号", "产品型号", "规格型号");
        add("manufacturer", "制造商", "product", "TEXT", "OBJECT", "制造商", "生产厂家", "生产商");
        add("origin", "产地", "product", "TEXT", "OBJECT", "产地", "原产地");
        add("productVersion", "产品版本", "product", "IDENTIFIER", "OBJECT", "产品版本", "软件版本", "配置版本");
        add("technicalParameter", "技术参数", "technical", "PARAMETER", "OBJECT", "技术参数", "参数值");
        add("technicalResponse", "技术响应", "technical", "TEXT", "OBJECT", "技术响应", "实际响应", "偏离说明");
        add("qualityTarget", "质量目标", "quality", "TEXT", "OBJECT", "质量目标", "质量标准");
        add("acceptanceStandard", "验收标准", "quality", "TEXT", "OBJECT", "验收标准", "验收依据");
        add("acceptanceMetric", "验收指标", "quality", "PARAMETER", "OBJECT", "验收指标");
        add("warrantyPeriod", "质保期", "warranty", "DURATION", "OBJECT", "质保期", "保修期", "免费质保期", "保修期限");
        add("warrantyScope", "质保范围", "warranty", "TEXT", "OBJECT", "质保范围", "保修范围", "免费维护范围");
        add("responseTime", "响应时间", "service", "DURATION", "OBJECT", "响应时间", "故障响应时间", "售后响应时间");
        add("arrivalTime", "到场时间", "service", "DURATION", "OBJECT", "到场时间", "现场到达时间");
        add("recoveryTime", "恢复时间", "service", "DURATION", "OBJECT", "恢复时间", "业务恢复时间");
        add("repairTime", "修复时间", "service", "DURATION", "OBJECT", "修复时间", "故障修复时间");
        add("serviceHours", "服务时间", "service", "TEXT", "OBJECT", "服务时间", "服务时段", "服务工作时间");
        add("paymentTerms", "付款条件", "payment", "TEXT", "PROJECT", "付款条件", "支付条件", "付款方式");
        add("paymentRatio", "付款比例", "payment", "PERCENT", "PROJECT", "付款比例", "支付比例");
        add("paymentMilestone", "付款节点", "payment", "TEXT", "PROJECT", "付款节点", "支付节点");
        add("personName", "关键岗位人员", "personnel", "IDENTIFIER", "OBJECT", "项目经理", "项目负责人");
        add("personRole", "人员职务", "personnel", "TEXT", "PERSON", "人员职务", "拟任职务");
        // No generic aliases: these fields require a source-local holder/credential record.
        add("credentialPersonName", "证件持有人姓名", "personnel", "IDENTIFIER", "PERSON");
        add("credentialPersonRole", "人员岗位", "personnel", "TEXT", "PERSON");
        add("idNumber", "身份证号码", "personnel", "IDENTIFIER", "PERSON");
        add("drivingLicenseNumber", "驾驶证号码", "personnel", "IDENTIFIER", "PERSON");
        add("drivingFileNumber", "驾驶证档案编号", "personnel", "IDENTIFIER", "PERSON");
        add("archiveNumber", "证件档案编号", "personnel", "IDENTIFIER", "PERSON");
        add("age", "年龄", "personnel", "TEXT", "PERSON");
        add("education", "学历", "personnel", "TEXT", "PERSON");
        add("professionalMajor", "所学专业", "personnel", "TEXT", "PERSON");
        add("graduationSchool", "毕业院校", "personnel", "TEXT", "PERSON");
        add("classification", "材料分类", "personnel", "TEXT", "PERSON");
        add("passportNumber", "护照号码", "personnel", "IDENTIFIER", "PERSON");
        add("personCertificateName", "人员证书名称", "personnel", "TEXT", "PERSON");
        add("personCertificateNumber", "人员证书编号", "personnel", "IDENTIFIER", "PERSON");
        add("personGender", "人员性别", "personnel", "TEXT", "PERSON");
        add("personBirthDate", "出生日期", "personnel", "DATE", "PERSON");
        add("vehicleClass", "准驾车型", "personnel", "TEXT", "PERSON");
        add("credentialValidFrom", "证件有效期起始日", "personnel", "DATE", "PERSON");
        add("credentialValidUntil", "证件有效期截止日", "personnel", "DATE", "PERSON");
        add("credentialValidPeriod", "证件有效期限", "personnel", "TEXT", "PERSON");
        add("credentialAddress", "证件住址", "personnel", "TEXT", "PERSON");
        add("credentialIssuingAuthority", "证件签发机关", "personnel", "TEXT", "PERSON");
        add("personIdentityCoverage", "人员身份关联", "personnel", "TEXT", "PERSON");
        add("ethnicGroup", "民族", "personnel", "TEXT", "PERSON");
        add("nationality", "国籍", "personnel", "TEXT", "PERSON");
        add("firstIssueDate", "初次领证日期", "personnel", "DATE", "PERSON");
        add("certificateLevel", "人员证书等级", "personnel", "TEXT", "PERSON");
        add("credentialPhone", "联系电话", "personnel", "TEXT", "PERSON");
        add("credentialEmployer", "工作单位", "personnel", "TEXT", "PERSON");
        add("socialSecurityNumber", "社会保障号码", "personnel", "IDENTIFIER", "PERSON");
        add("professionalTitle", "人员职称", "personnel", "TEXT", "PERSON");
        add("personEmployer", "人员单位", "personnel", "TEXT", "PERSON", "任职单位", "注册单位", "社保单位");
        add("onsiteCommitment", "驻场承诺", "personnel", "TEXT", "PERSON", "驻场承诺", "驻场安排", "投入比例");
        add("teamCount", "团队人数", "personnel", "NUMBER", "PROJECT", "团队人数", "项目团队人数");
        add("certificateNumber", "证书编号", "qualification", "IDENTIFIER", "CERTIFICATE", "证书编号", "证书号");
        add("certificateHolder", "证书持有人", "qualification", "TEXT", "CERTIFICATE", "证书持有人", "持证人");
        add("qualificationGrade", "资质等级", "qualification", "TEXT", "CERTIFICATE", "资质等级", "证书等级");
        add("certificateExpiry", "证书到期日", "qualification", "DATE", "CERTIFICATE", "证书到期日", "证书有效期至");
        add("performanceProject", "业绩项目", "performance", "TEXT", "CONTRACT", "业绩项目名称", "历史项目名称");
        add("contractNumber", "合同编号", "performance", "IDENTIFIER", "CONTRACT", "合同编号", "合同号");
        add("contractAmount", "合同金额", "performance", "MONEY", "CONTRACT", "合同金额", "业绩合同金额");
        add("acceptanceDate", "业绩验收日期", "performance", "DATE", "CONTRACT", "验收日期", "最终验收日期");
        add("performanceRole", "业绩承担角色", "performance", "TEXT", "CONTRACT", "承担角色", "业绩承担角色");
        add("jointLeader", "联合体牵头方", "organization", "TEXT", "PROJECT", "联合体牵头方", "联合体牵头人");
        add("jointMembers", "联合体成员", "organization", "TEXT", "PROJECT", "联合体成员");
        add("workAllocation", "责任分工", "organization", "TEXT", "OBJECT", "责任分工", "工作分配", "承担工作");
        add("subcontractCommitment", "分包承诺", "organization", "CLAIM", "OBJECT", "分包承诺", "分包安排", "是否分包");
        add("declarationManufacturer", "声明制造商", "declaration", "TEXT", "OBJECT", "声明制造商", "声明函制造商");
        add("declaredIndustry", "声明所属行业", "declaration", "TEXT", "OBJECT", "所属行业", "声明所属行业");
        add("statisticalYear", "统计年度", "declaration", "IDENTIFIER", "OBJECT", "统计年度", "声明统计年度");
        add("deploymentMode", "部署方式", "solution", "TEXT", "OBJECT", "部署方式", "部署模式");
        add("dataLocation", "数据存放位置", "solution", "TEXT", "OBJECT", "数据存放位置", "数据存储位置");
        add("networkRequirement", "联网要求", "solution", "TEXT", "OBJECT", "联网要求", "网络连接要求");
        add("backupFrequency", "备份频率", "solution", "TEXT", "OBJECT", "备份频率", "备份周期");
        add("backupRetention", "备份保留期限", "solution", "DURATION", "OBJECT", "备份保留期限", "备份保留时间");
        add("documentVersion", "文件版本", "document_reference", "IDENTIFIER", "OBJECT", "文件版本", "文档版本");
        add("referenceTarget", "引用目标", "document_reference", "TEXT", "OBJECT", "引用目标", "引用章节", "附件引用");
        add("custom", "自定义字段", "technical", "DYNAMIC", "OBJECT");
    }
    private ConsistencyCatalog() { }
    private static void category(String id, String title) { CATEGORIES.put(id,title); }
    private static void add(String id,String title,String category,String type,String subject,String... aliases) {
        Map<String,Object> item=new LinkedHashMap<>();
        item.put("id",id); item.put("title",title); item.put("categoryId",category); item.put("valueType",type);
        item.put("subjectMode",subject); item.put("aliases",cn.bidcheck.compat.Java8Api.listOf(aliases));
        DEFINITIONS.put(id,Collections.unmodifiableMap(item));
    }
    public static List<String> fields() { return cn.bidcheck.compat.Java8Api.listCopyOf(DEFINITIONS.keySet()); }
    public static Map<String,Object> definition(String field) { return DEFINITIONS.getOrDefault(field,cn.bidcheck.compat.Java8Api.mapOf()); }
    public static List<Map<String,Object>> definitions() { return cn.bidcheck.compat.Java8Api.listCopyOf(DEFINITIONS.values()); }
    public static List<String> categories() { return cn.bidcheck.compat.Java8Api.listCopyOf(CATEGORIES.keySet()); }
    public static String categoryTitle(String id) { return CATEGORIES.getOrDefault(id,id); }
}
