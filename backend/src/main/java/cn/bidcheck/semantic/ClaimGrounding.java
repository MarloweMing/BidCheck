package cn.bidcheck.semantic;

import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.verification.source.DateFieldValues;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Pattern;

/** Mechanical source contracts. These do not establish legal identity or certificate authenticity. */
final class ClaimGrounding {
    private static final Pattern DATE = Pattern.compile("(\\d{4})\\s*(?:年|[-./])\\s*(\\d{1,2})\\s*(?:月|[-./])\\s*(\\d{1,2})\\s*日?");
    private static final Pattern MONTH = Pattern.compile("(\\d{4})\\s*(?:年|[-./])\\s*(\\d{1,2})(?:\\s*月|(?=[-./]|$|\\s))");
    private static final Pattern COMPANY = Pattern.compile("[\\p{IsHan}A-Za-z0-9（）()·]{2,50}(?:有限责任公司|股份有限公司|有限公司)");

    static String contentUseIssue(String requirement, String status, String explanation, JsonNode claims, Map<String,Evidence> available) {
        boolean substantive = requirement.matches("(?s).*(内容完整|内容齐全|完整性|实质性内容|正文完整).*" );
        boolean completeClaim = assertsSubstantiveContent(explanation);
        if ((!substantive || !"PASS".equals(status)) && !completeClaim) return "";
        for(JsonNode claim:claims) {
            String statement=claim.path("text").asText();
            // Listing a directory is a supported navigation fact, even when other
            // parts of this same checkpoint still need substantive verification.
            if (navigationStatement(claim)) continue;
            if (!"PASS".equals(status) && !assertsSubstantiveContent(statement)) continue;
            if(!statement.matches("(?s).*(章节|第.{1,8}章|方案|正文|内容|措施).*" )
                    || statement.matches("(?s).*(待核|尚未|未能|不能确认|无法确认).*"))continue;
            List<Evidence> supporting=new ArrayList<>();
            for(JsonNode id:claim.path("evidenceIds")) if(id.asText().startsWith("C") && available.containsKey(id.asText()))supporting.add(available.get(id.asText()));
            if(!supporting.isEmpty() && supporting.stream().allMatch(e -> indexOnly(e.quote())))
                return "相关章节的肯定断言仅由目录或索引支持。目录列出章节及页码不能证明各章已有实质正文或内容完整；须补核对应正文后再判断完整性，未召回正文不等于原文件缺失。";
        }
        return "";
    }
    static boolean navigationStatement(JsonNode claim){
        if(!Set.of("TEXT","MATERIAL").contains(claim.path("kind").asText()))return false;
        String statement=claim.path("text").asText();
        if(assertsSubstantiveContent(statement)||statement.matches("(?s).*(?:正文|实质性内容|内容齐全|内容完整|全部已提交|全部已提供|满足要求).*"))return false;
        return statement.matches("(?s).*(?:目录|索引).*" )
            || statement.matches("(?:[^。；;]{0,30})?章节标题(?:及|和|与)子项清单(?:存在|列明|已列明)?[。]?" );
    }
    private static boolean assertsSubstantiveContent(String text) {
        for (String sentence : text.split("[。；！？\\n]")) {
            if (sentence.matches("(?s).*(不能|无法|未能|尚未|待核|需核|仍需|是否|不视为|不代表|不足以).*")) continue;
            if (sentence.matches("(?s).*(各|均|全部|完全|齐全|完整).{0,30}(正文|实质性内容|内容描述|内容齐全).*" )
                    || sentence.matches("(?s).*(正文|实质性内容).{0,30}(均有|齐全|完整|已提供).*" )) return true;
        }
        return false;
    }
    static boolean indexOnly(String text) {
        int leaders=0;for(String line:text.split("\\R")) if(line.matches(".*[.．…]{5,}.*\\d+\\s*"))leaders++;
        if(leaders>=2)return true;
        String beginning=text.substring(0,Math.min(350,text.length()));
        return beginning.matches("(?s).*(评审索引表|投标索引表|响应索引表).*" ) && text.matches("(?s).*(对应页码|页码|P\\d+).*" );
    }

    static String validate(JsonNode claims, Map<String, Evidence> available, String explanation) {
        return validate(claims,available,explanation,null);
    }
    static String validate(JsonNode claims, Map<String, Evidence> available, String explanation,Document bid) {
        Set<String> comparedFields = new HashSet<>();
        for (JsonNode claim : claims) {
            String kind = claim.path("kind").asText();
            List<Evidence> sources = new ArrayList<>();
            for (JsonNode id : claim.path("evidenceIds")) sources.add(available.get(id.asText()));
            if (claim.path("text").asText().matches("(?s).*(社保|社会保险|养老保险|医疗保险|工伤保险|失业保险).*")) {
                var companies = COMPANY.matcher(claim.path("text").asText());
                while (companies.find()) {
                    String company = companies.group().replaceFirst("^.*?(?:为|在|由)", "");
                    if (sources.stream().noneMatch(e -> contains(e.quote(), company)))
                        return "社保缴纳主体没有出现在该断言引用的原文中，不能从牵头人、其他人员或注册单位推定缴费单位；须同时核对人员行及同份材料的单位表头。";
                }
                String statement = claim.path("text").asText();
                if (!"RELATION".equals(kind) && statement.matches("(?s).*(缴纳单位|缴费单位|缴纳主体|缴费主体|所属单位|任职单位).*")
                        && !statement.matches("(?s).*(表头|标题|单位名称栏|未确认|待核|不能确认|无法确认).*"))
                    return "社保主体关系不能用TEXT或期限断言替代：须列明人员、缴费单位及分别支持两端和同份材料归属的引用；单独人员行不能证明单位。";
            }
            if ("RELATION".equals(kind)) {
                String subject = claim.path("subject").asText(), object = claim.path("object").asText(), relation = claim.path("relation").asText();
                if(Set.of("ISSUED_COMMITMENT","DECLARATION_BY").contains(relation)){
                    boolean proved=sources.stream().anyMatch(e->!DeclarationSourceProof.prove(bid,e,subject,object,available.values()).isEmpty());
                    if(proved)continue;
                    // The legacy no-document path accepts only a single excerpt containing the actual
                    // heading, issuer and declaration body; it never joins neighbouring snippets.
                    if(bid==null&&sources.stream().anyMatch(e->contains(e.quote(),subject)&&contains(e.quote(),object)&&e.quote().matches("(?s).*(?:我方承诺|我公司承诺|我方声明|特此声明).*")))continue;
                    return "声明落款与文书标题尚未由同一正文或有明确文书边界的续页对应；这里只核对书面响应，不据承诺推定实际资格事实、盖章或人员聘用。";
                }
                if (meaningful(subject) && meaningful(object) && claim.path("text").asText().matches("(?s).*(社保|社会保险|养老保险|缴费|缴纳).*" )
                        && sources.stream().anyMatch(e -> contains(e.quote(), subject)) && sources.stream().anyMatch(e -> contains(e.quote(), object))
                        && sources.stream().noneMatch(e -> contains(e.quote(), subject) && contains(e.quote(), object)))
                    return "已找到人员行和单位表头原文，但分处不同片段，同份材料与社保主体关联仍待核。不能据同标题或相邻位置自动认定缴费单位，也不能据此声称主体不符。";
                if (!meaningful(subject) || !meaningful(object) || relation.isBlank() || relation.length() > 80
                        || sources.stream().noneMatch(e -> contains(e.quote(), subject) && contains(e.quote(), object)))
                    return "关系断言缺少可追溯的两端主体或原子关系：同一来源须明确支持subject与object，人员所属单位及单位分工应分开引用，不能仅凭协议推定人员归属。";
                if (claim.path("text").asText().matches("(?s).*(该单位|该企业|该公司|并且|同时).*(负责|任职|属于|注册|受聘).*"))
                    return "一个关系断言串联了多条主体关系，须拆为各有原文支持的原子关系，现有引用不能自动证明整条关系链。";
            }
            if ("FIELD_COMPARISON".equals(kind)) {
                String field = claim.path("field").asText();
                JsonNode comparisons = claim.path("comparisons");
                if (!meaningful(field) || !comparisons.isArray() || comparisons.size() < 2 || comparisons.size() > 8)
                    return "字段一致性断言未列出同一字段的逐来源值，不能把注册编号、电子证书编号或执业印章号混为同一字段。";
                Set<String> ownIds = new HashSet<>();claim.path("evidenceIds").forEach(id -> ownIds.add(id.asText()));
                Set<String> locations = new HashSet<>();String previous = null;
                for (JsonNode comparison : comparisons) {
                    String id = comparison.path("evidenceId").asText(), label = comparison.path("label").asText(), value = comparison.path("value").asText();
                    Evidence evidence = available.get(id);
                    if (!ownIds.contains(id) || evidence == null || !meaningful(value) || !compact(field).equals(compact(label))
                            || !fieldValue(evidence.quote(), label, value))
                        return "字段一致性断言的标签或取值未被相应原文支持，不能把不同类别的编号作为一致性依据。";
                    locations.add(id);
                    if (previous != null && !previous.equals(compact(value)))
                        return "模型所列同一字段的原值并不一致，不能确认其一致性解释。";
                    previous = compact(value);
                }
                if (locations.size() < 2) return "字段一致性核对需要至少两个独立来源，重复引用同一来源不能证明一致。";
                comparedFields.add(field);
            }
            if ("DATE".equals(kind) && assertsValidity(claim.path("text").asText())) {
                String field = claim.path("validityField").asText(), reference = claim.path("referenceDate").asText(), basis = claim.path("referenceBasis").asText();
                JsonNode references = claim.path("referenceEvidenceIds");
                Set<String> ownIds = new HashSet<>();claim.path("evidenceIds").forEach(id -> ownIds.add(id.asText()));
                Set<LocalDate> referenceDates = dates(reference);
                Set<LocalDate> starts = dates(claim.path("startDate").asText()), ends = dates(claim.path("endDate").asText());
                if (!meaningful(field) || !meaningful(basis) || referenceDates.size() != 1 || !references.isArray() || references.isEmpty()
                        || starts.size() != 1 || ends.size() != 1)
                    return "有效期覆盖判断缺少明确的适用期字段、参照日期或其来源，不能仅凭证书有效期推断投标时点有效。";
                LocalDate start = starts.iterator().next(), end = ends.iterator().next(), referenceDay = referenceDates.iterator().next();
                List<Evidence> periodSources = sources.stream().filter(e -> fieldDates(e.quote(), field, 2).equals(List.of(start, end))).toList();
                if (periodSources.isEmpty() || end.isBefore(start)) return "有效期起止值没有对应到所引证照的同一适用期字段，不能混用注册期限、使用期限或其他日期。";
                if (referenceDay.isBefore(start) || referenceDay.isAfter(end)) return "参照日期不在所引用的有效期区间内，模型的覆盖解释缺乏支持，已撤回该解释，请核对适用期及最终时点。";
                boolean anchored = false;
                for (JsonNode id : references) {
                    Evidence evidence = available.get(id.asText());
                    if (!ownIds.contains(id.asText()) || evidence == null) return "有效期参照日期未引用实际发送的原文，不能确认适用时点。";
                    if (fieldDates(evidence.quote(), basis, 1).equals(List.of(referenceDay))) anchored = true;
                }
                if (claim.path("text").asText().matches("(?s).*(投标|递交|截止).*" ) && !basis.matches(".*(截止|递交|提交).*")) anchored = false;
                if (!anchored) return "参照日期及日期类别没有同时出现在所引原文，不能用签署或编制日期替代未经确认的投标时点。";
                for (String applicable : List.of("注册有效期", "使用有效期")) {
                    for (Evidence certificate : periodSources) if (certificate.quote().contains(applicable)) {
                        boolean covered = false;
                        for (JsonNode other : claims) if ("DATE".equals(other.path("kind").asText()) && applicable.equals(other.path("validityField").asText())) {
                            for (JsonNode id : other.path("evidenceIds")) if (certificate.equals(available.get(id.asText()))) covered = true;
                        }
                        if (!covered) return "所引证照含不同适用期限，尚未分别核对注册有效期与使用有效期，不能据单一期限给出整体有效结论。";
                    }
                }
            }
        }
        if (!comparedFields.containsAll(assertedFields(explanation)))
            return "模型声称证书或查询字段一致，但没有逐字段、逐来源的标签和值对应，已撤回该解释；注册编号与电子证书编号不能混用。";
        if (explanation.matches("(?s).*(可按.{0,8}计分|可得.{0,4}[0-9一二三四五六七八九十]+分|应得.{0,4}[0-9一二三四五六七八九十]+分|可获.{0,4}[0-9一二三四五六七八九十]+分).*" )
                && explanation.matches("(?s).*(未(?:提供|取得|核实|核验|覆盖)|缺少|缺乏|待核|仍需|建议.{0,20}核对).{0,60}(合同|关键页|金额|施工|社保|签章|签字|负责人|单位).*"))
            return "计分所需的合同关键页、金额口径、人员或相关证明链仍明确待核，不能同时给出可计分的确定解释；应先核实具体缺口，再由评审确认分数。";
        return "";
    }

    static String amountScopeIssue(JsonNode claim, List<Evidence> sources) {
        String basis = claim.path("amountBasis").asText(), statement = claim.path("text").asText();
        if ((basis + statement).matches("(?s).*(建安|建筑安装).*" )
                && !boundAmount(sources, "建安(?:工程)?费|建筑安装(?:工程)?费", claim.path("value").asText()))
            return "所引原文没有明确建安费金额口径；工程总价、工程造价或不含税总价不能直接当作建安费参与业绩门槛或计分。须核对合同对应施工分项及税基。";
        if ((basis + statement).matches("(?s).*(施工分项|施工部分金额|施工费).*" )
                && !boundAmount(sources, "施工(?:部分|分项)(?:金额|价款|费用)?|施工费用?|施工价款|装修工程费", claim.path("value").asText()))
            return "所引原文没有明确施工分项金额，不能将EPC总合同额或总工程造价替代施工部分金额参与评分。";
        return "";
    }
    static boolean amountValueGrounded(String rawValue, List<Evidence> sources) {
        if(rawValue == null || rawValue.length()>200)return false;
        Pattern number=Pattern.compile("(?<![0-9.])[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?(?![0-9.])");
        var wanted=number.matcher(rawValue.replaceAll("\\s+",""));if(!wanted.find())return false;
        java.math.BigDecimal value;
        try {value=new java.math.BigDecimal(wanted.group().replace(",",""));}catch(NumberFormatException invalid){return false;}
        if(wanted.find())return false;
        for(Evidence source:sources) {
            // Do not merge numeric table cells: a month column "4 1501.16"
            // contains the amount 1501.16, not the invented number 41501.16.
            String text=source.quote().replaceAll("(?<=\\d)\\s*([.,])\\s*(?=\\d)","$1");
            var actual=number.matcher(text);
            while(actual.find())try{if(new java.math.BigDecimal(actual.group().replace(",","")).compareTo(value)==0)return true;}
            catch(NumberFormatException invalid){/* malformed numeric token does not ground an amount */}
        }
        return false;
    }
    static boolean hasValidatedCertificateTiming(JsonNode claims, Map<String,Evidence> available, Set<String> usedIds) {
        Set<String> covered = new HashSet<>(), required = new HashSet<>();
        for(String id:usedIds) {
            Evidence source=available.get(id);
            if(id.startsWith("C") && source!=null && source.quote().matches("(?s).*(证书|证照|许可证|认证).*(有效期|有效期限).*"))required.add(id);
        }
        for(JsonNode claim:claims) if("DATE".equals(claim.path("kind").asText()) && assertsValidity(claim.path("text").asText())
                && !claim.path("referenceDate").asText().isBlank() && claim.path("referenceEvidenceIds").isArray() && !claim.path("referenceEvidenceIds").isEmpty()) {
            Set<LocalDate> starts=dates(claim.path("startDate").asText()), ends=dates(claim.path("endDate").asText());
            if(starts.size()!=1 || ends.size()!=1)continue;
            for(JsonNode id:claim.path("evidenceIds")) {
                Evidence source=available.get(id.asText());
                if(id.asText().startsWith("C") && source!=null && fieldDates(source.quote(),claim.path("validityField").asText(),2)
                        .equals(List.of(starts.iterator().next(),ends.iterator().next())))covered.add(id.asText());
            }
        }
        return !covered.isEmpty() && covered.containsAll(required);
    }
    private static boolean boundAmount(List<Evidence> sources, String labels, String rawValue) {
        Pattern numbers = Pattern.compile("[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?");
        var valueMatch = numbers.matcher(rawValue);if(!valueMatch.find())return false;
        java.math.BigDecimal expected;
        try { expected = new java.math.BigDecimal(valueMatch.group().replace(",","")); } catch(NumberFormatException invalid) {return false;}
        if(valueMatch.find())return false;
        for(Evidence source:sources) {
            var label=Pattern.compile(labels).matcher(source.quote());
            while(label.find()) {
                String after=source.quote().substring(label.end(),Math.min(source.quote().length(),label.end()+80));
                var amount=numbers.matcher(after);if(!amount.find())continue;
                String between=after.substring(0,amount.start());
                if(between.matches("(?s).*(?:总价|总额|总造价|工程造价|设计费|另见|未明确|待核|不详|[；;。]).*"))continue;
                String tail=after.substring(amount.end());
                var another=numbers.matcher(tail);
                // More than one amount in the same unbroken cell/phrase is ambiguous.
                if(another.find() && !tail.substring(0,another.start()).matches("(?s).*[；;。\\n].*"))continue;
                try {if(new java.math.BigDecimal(amount.group().replace(",","")).compareTo(expected)==0)return true;}
                catch(NumberFormatException invalid) { /* A nonnumeric price cannot prove the requested basis. */ }
            }
        }
        return false;
    }

    private static Set<String> assertedFields(String explanation) {
        Set<String> fields = new HashSet<>();
        for (String sentence : explanation.split("[。；！？\\n]")) {
            // This contract compares certificate/query fields, not project/tender numbers.
            // A generic 编号一致 statement must not be given an unrelated certificate warning.
            if (!sentence.matches("(?s).*(证书|证照|注册|执业|查询截图|查询信息|证件).*")) continue;
            if (sentence.matches("(?s).*(不能确认|无法确认|需核对|待核|未确认|是否|不一致|不相同).*")) continue;
            if (sentence.matches("(?s).*(编号|证书信息|截图信息|查询信息|有效期|注册单位|姓名).*(一致|相同).*" )
                    || sentence.matches("(?s).*(一致|相同).*(编号|证书信息|截图信息|查询信息|有效期|注册单位|姓名).*")) {
                String remaining = sentence;boolean found = false;
                for (String field : List.of("电子证书编号", "统一社会信用代码", "注册有效期", "使用有效期", "执业印章号", "注册编号", "证书编号", "身份证号", "注册单位", "聘用单位", "有效期", "姓名")) {
                    if (remaining.contains(field)) { fields.add(field);remaining = remaining.replace(field, "");found = true; }
                }
                if (!found) fields.add("UNSPECIFIED_FIELDS");
            }
        }
        return fields;
    }
    private static boolean assertsValidity(String text) {
        // A literal 投标截止时间 or a warning about 投标有效性 is not a
        // certificate-validity coverage claim and must not acquire certificate fields.
        if (AffirmativeAssertions.certificateValidity(text)) return true;
        if (text.matches("(?s).*(待核|是否|无法|不能|未确认|需核).*")) return false;
        return text.matches("(?s).*(注册有效期|使用有效期|证书有效期|有效期限|有效期).*(覆盖|符合|满足|仍有效|未过期|时点有效).*" );
    }
    static boolean sourceContainsDateValue(String quote, String value) {
        Set<LocalDate> expected = dates(value);
        if (!expected.isEmpty()) return dates(quote).containsAll(expected);
        Set<YearMonth> months = months(value);return !months.isEmpty() && months(quote).containsAll(months);
    }
    static boolean dateValueGrounded(JsonNode claim, List<Evidence> sources) {
        String value = claim.path("value").asText();
        if (!value.isBlank() && value.length() <= 200 && !SourceRelations.normalized(value).isBlank()) {
            String namedField=claim.path("validityField").asText();Set<LocalDate> values=dates(value);
            if(meaningful(namedField)&&!values.isEmpty())return sources.stream().anyMatch(e->new HashSet<>(DateFieldValues.readAssertion(e.quote(),namedField,values.size())).equals(values));
            return sources.stream().anyMatch(e -> contains(e.quote(), value) || sourceContainsDateValue(e.quote(), value));
        }
        Set<LocalDate> starts = dates(claim.path("startDate").asText()), ends = dates(claim.path("endDate").asText());
        String field = claim.path("validityField").asText();
        if (starts.size() == 1 && ends.size() == 1) return meaningful(field)
                && sources.stream().anyMatch(e -> fieldDates(e.quote(), field, 2).equals(List.of(starts.iterator().next(), ends.iterator().next())));
        if (meaningful(field) && starts.isEmpty() && ends.size() == 1)
            return sources.stream().anyMatch(e -> fieldDates(e.quote(), field, 2).equals(List.of(ends.iterator().next())));
        if (meaningful(field) && ends.isEmpty() && starts.size() == 1)
            return sources.stream().anyMatch(e -> fieldDates(e.quote(), field, 2).equals(List.of(starts.iterator().next())));
        Set<YearMonth> monthStarts = months(claim.path("startDate").asText()), monthEnds = months(claim.path("endDate").asText());
        return monthStarts.size() == 1 && monthEnds.size() == 1 && sources.stream().anyMatch(e ->
                months(e.quote()).containsAll(monthStarts) && months(e.quote()).containsAll(monthEnds));
    }
    private static List<LocalDate> fieldDates(String quote, String field, int limit) {
        return DateFieldValues.read(quote,field,limit);
    }
    static List<Map<String,Object>> declarationProofs(JsonNode claims,Map<String,Evidence> available,Document bid){
        List<Map<String,Object>> proofs=new ArrayList<>();for(JsonNode claim:claims)if("RELATION".equals(claim.path("kind").asText())&&Set.of("ISSUED_COMMITMENT","DECLARATION_BY").contains(claim.path("relation").asText()))
            for(JsonNode id:claim.path("evidenceIds")){var proof=DeclarationSourceProof.prove(bid,available.get(id.asText()),claim.path("subject").asText(),claim.path("object").asText(),available.values());if(!proof.isEmpty()){proofs.add(proof);break;}}
        return List.copyOf(proofs);
    }
    private static boolean meaningful(String value) { return value.length() <= 200 && SourceRelations.normalized(value).length() >= 2; }
    private static boolean contains(String quote, String value) { return SourceRelations.normalized(quote).contains(SourceRelations.normalized(value)); }
    private static String compact(String text) { return text.replaceAll("\\s+", ""); }
    private static boolean fieldValue(String quote, String label, String value) {
        return Pattern.compile(Pattern.quote(compact(label)) + "[:：]?" + Pattern.quote(compact(value))).matcher(compact(quote)).find();
    }
    private static Set<LocalDate> dates(String text) {
        Set<LocalDate> values = new HashSet<>();var match = DATE.matcher(text);
        while (match.find()) try { values.add(LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)))); }
        catch (RuntimeException invalid) { /* Invalid dates cannot anchor a validity assertion. */ }
        return values;
    }
    private static Set<YearMonth> months(String text) {
        Set<YearMonth> values = new HashSet<>();var match = MONTH.matcher(text);
        while(match.find()) try { values.add(YearMonth.of(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)))); }
        catch(RuntimeException invalid) { /* Invalid months are never grounding evidence. */ }
        return values;
    }
}
