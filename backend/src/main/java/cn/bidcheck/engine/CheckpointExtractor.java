package cn.bidcheck.engine;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;

/** Conservative, editable candidates from explicit Chinese procurement clauses. */
public final class CheckpointExtractor {
    public static final String VERSION="checkpoint-extraction-3-source-only-conditional-obligations";
    public List<Checkpoint> extract(Document document) {
        Objects.requireNonNull(document,"document");
        Map<String,Checkpoint> rules=new LinkedHashMap<>();
        List<Fact> facts=new FactExtractor().extract(document);
        for(String field:List.of("projectName","projectNumber")) {
            facts.stream().filter(f->field.equals(f.field())).findFirst().ifPresent(f -> put(rules,document,field.equals("projectName")?"项目名称一致性":"项目编号一致性","商务响应","HIGH","ENTITY_FIELD_CONSISTENCY",field,"=",f.normalizedValue(),"",true,f.evidence()));
        }
        String deadline="";
        String openingDate="";
        Evidence deadlineSource=null;
        Evidence openingSource=null;
        int deadlinePriority=0;
        String section="";
        boolean inContents=false;
        boolean inReviewProcedure=false;
        List<LocatedLine> located=new ArrayList<>();
        for(Page page:document.pages()==null?List.<Page>of():document.pages()) {
            List<String> lines=physicalLines(page);
            for(int i=0;i<lines.size();i++) if(!lines.get(i).isBlank()) located.add(new LocatedLine(page,i+1,lines.get(i)));
        }
        Set<String> consumedStructured=new HashSet<>();
        Set<String> chapterReferences=chapterReferences(located);
        FormatHierarchy formatHierarchy=new FormatHierarchy();
        for(Page page:document.pages()==null?List.<Page>of():document.pages()) {
            List<String> lines=physicalLines(page);
            for(int index=0; index<lines.size();index++) {
                String line=lines.get(index); Evidence source=EngineText.evidence(document,page,index+1,line);
                if(line.isBlank()) continue;
                if(chapterReferences.contains(source.anchor())) continue;
                if(line.matches("目\\s*录")) { inContents=true; continue; }
                boolean chapter=line.matches("^第[一二三四五六七八九十百0-9]+[章篇]\\s*.{1,50}$") && !line.matches(".*\\s[0-9]+$");
                if(chapter) inReviewProcedure=false;
                if(inContents) { if(chapter) inContents=false; else continue; }
                if(line.matches("^第[一二三四五六七八九十百0-9]+[章篇].*\\t[0-9]+$")) continue;
                String nextSection=section(line);
                if(scopeExit(line)) {
                    if(stripNumber(line).strip().equals("评审程序")) inReviewProcedure=true;
                    section=""; continue;
                }
                if(inReviewProcedure) nextSection="";
                if(!nextSection.isBlank()) {
                    if(!nextSection.equals(section)) formatHierarchy=new FormatHierarchy();
                    section=nextSection;
                }
                else if(chapter) section="";
                if("MATERIAL".equals(section)) {
                    String name=materialHeading(line,formatHierarchy);
                    if(nextSection.isBlank() && !name.isBlank()) {
                        String following=lines.subList(index+1,lines.size()).stream().filter(s->!s.isBlank()).findFirst().orElse("");
                        if(following.isBlank() && document.pages()!=null) {
                            int at=document.pages().indexOf(page);
                            if(at>=0 && at+1<document.pages().size()) following=EngineText.lines(document.pages().get(at+1)).stream().findFirst().orElse("");
                        }
                        boolean explicitCondition=following.matches(".*(?:无需|无要求可不提供|如.*则|按.*要求提供).*" );
                        String referencedCondition=materialCondition(name,located);
                        if(!explicitCondition && !referencedCondition.isBlank()) { following=referencedCondition; explicitCondition=true; }
                        boolean conditional=name.matches(".*(?:如有|认为有必要).*" ) || explicitCondition;
                        if(conditional) {
                            // Guidance belongs to presentation, not the original obligation. Keep
                            // explicit conditions; widen only to a literal contiguous source span.
                            String expected=name;Evidence conditionSource=source;
                            if(explicitCondition){
                                String pageText=Objects.toString(page.text(),"");int begin=pageText.indexOf(line),end=begin<0?-1:pageText.indexOf(following,begin+line.length());
                                if(begin>=0&&end>=0&&pageText.substring(begin+line.length(),end).isBlank()){
                                    String quote=pageText.substring(begin,end+following.length());expected=quote;
                                    conditionSource=new Evidence(source.documentId(),source.documentName(),source.page(),source.locationType(),quote,source.anchor(),source.confidence());
                                }else expected=name+"。"+following; // Unbound cross-block condition remains visible for source repair.
                            }
                            put(rules,document,"适用时提供："+name,"组成检查","MEDIUM","SEMANTIC_RESPONSE","conditionalMaterial","RESPONDS",expected,"",false,conditionSource);
                        }
                        else if(name.matches(".*(?:规范书|文件|条款).*(?:要求|规定).*(?:文件|资料|材料)")) put(rules,document,"投标文件组成："+name,"组成检查","HIGH","SEMANTIC_RESPONSE","materialReference","RESPONDS",name,"",true,source);
                        else put(rules,document,"投标文件组成："+name,"组成检查","HIGH","MATERIAL_EXISTENCE","material","EXISTS",name,"",true,source);
                    }
                }
                if(!consumedStructured.contains(source.anchor()) && Set.of("QUALIFICATION","SCORING","COMPLIANCE").contains(section) && (nextSection.isBlank() || line.contains("\t")) && line.length()>1) {
                    int start=position(located,source);
                    Clause clause=clause(document,located,start);
                    consumedStructured.addAll(clause.anchors());
                    if("QUALIFICATION".equals(section)) qualification(rules,document,clause.text(),clause.source());
                    else if("SCORING".equals(section)) {
                        scoring(rules,document,clause,located,consumedStructured);
                    } else if(actionable(clause.text()) || clause.text().contains("\t") && !clause.text().matches(".*(?:条款号|评审因素).*")) {
                        put(rules,document,"响应要求："+shorten(clause.text()),"响应检查","HIGH","SEMANTIC_RESPONSE","compliance","RESPONDS",clause.text(),"",true,clause.source());
                    }
                }
                numeric(rules,document,line,source,"bidValidity","投标有效期","投标有效期",">=","DAY");
                numeric(rules,document,line,source,"duration","工期","(?:总工期|项目工期|设计工期|施工工期|安装工期|调试工期|计划工期|交付工期|工期|交货期)","<=","DAY");
                numeric(rules,document,line,source,"warranty","质保期","(?:质保期|保修期|质量保证期)",">=","MONTH");
                Matcher amount=Pattern.compile("(?:最高投标限价|最高限价|投标报价不得超过|投标报价不超过|预算金额)\\s*(?:为|人民币|[:：=\\s])*"+EngineText.NUMBER+"\\s*(亿元|万元|元|万)?").matcher(line);
                if(amount.find()) {
                    String taxUnit=FactExtractor.taxUnit(line,amount.start(),amount.end());
                    String taxLabel="CNY_EXCLUSIVE".equals(taxUnit)?"不含税":"CNY_INCLUSIVE".equals(taxUnit)?"含税":"";
                    put(rules,document,taxLabel+"投标报价不超过最高限价","报价检查","HIGH","VALUE_COMPARE","totalPrice","<=",EngineText.amount(amount.group(1),amount.group(2)),taxUnit,true,source);
                }
                if(line.matches(".*(?:车牌|行驶证|VIN|车架号).*")) put(rules,document,"车辆表与行驶证信息一致性","一致性检查","HIGH","ENTITY_FIELD_CONSISTENCY","plateNumber","=","","",true,source);
                if(!Set.of("SCORING","QUALIFICATION").contains(section) && line.matches(".*(?:技术方案|技术参数|售后服务|实施方案|培训方案).*")) put(rules,document,"技术响应："+line.substring(0,Math.min(30,line.length())),"技术响应","MEDIUM","SEMANTIC_RESPONSE","technicalResponse","RESPONDS",line,"",true,source);
                if(line.matches(".*(?:投标截止|递交截止|开标时间|开标日期).*")) {
                    Matcher deadlineMatch=Pattern.compile("(?:投标(?:文件)?(?:递交)?截止(?:时间|日期)?|递交截止(?:时间|日期)?|开标时间|开标日期)\\s*(?:为|是)?\\s*[:：]?\\s*"+EngineText.DATE.pattern()).matcher(line);
                    while(deadlineMatch.find()) {
                        int priority=deadlineMatch.group().startsWith("开标")?1:2;
                        var dates=EngineText.dates(deadlineMatch.group());
                        if(!dates.isEmpty() && priority==1 && openingDate.isBlank()) { openingDate=dates.get(0).toString(); openingSource=source; }
                        if(!dates.isEmpty() && priority>deadlinePriority) { deadline=dates.get(0).toString(); deadlineSource=source; deadlinePriority=priority; }
                    }
                }
            }
        }
        // A deadline establishes a shared reference date, not a standalone certificate obligation.
        // Explicit qualification requirements are extracted with their own certificate/source context above.
        put(rules,document,"投标人名称内部一致性","一致性检查","HIGH","ENTITY_FIELD_CONSISTENCY","companyName","=","","",true,null);
        put(rules,document,"统一社会信用代码内部一致性","一致性检查","HIGH","ENTITY_FIELD_CONSISTENCY","creditCode","=","","",true,null);
        put(rules,document,"人员角色与姓名一致性","一致性检查","MEDIUM","ENTITY_FIELD_CONSISTENCY","personName","=","","",true,null);
        put(rules,document,"全文图文信息一致性","全文信息检查","HIGH","FULL_TEXT_REVIEW","fullTextConsistency","REVIEW","核对全文、表格与证照图片中的同一主体信息是否一致","",true,null);
        put(rules,document,"金额与计算错误","全文信息检查","HIGH","FULL_TEXT_REVIEW","amountAccuracy","REVIEW","核对报价金额、数量乘单价及金额口径；复杂汇总和大写金额需复核","",true,null);
        put(rules,document,"漏填与错填","全文信息检查","HIGH","FULL_TEXT_REVIEW","missingFields","REVIEW","核对未填写字段、占位符及填写内容是否正确","",true,null);
        put(rules,document,"签署日期检查","全文信息检查","HIGH","FULL_TEXT_REVIEW","signatureDate","REVIEW","核对签署、签章与落款日期格式和真实性","",true,null);
        put(rules,document,"落款时间不得晚于开标时间","全文信息检查","HIGH","FULL_TEXT_REVIEW","signingAfterOpening","REVIEW",openingDate,"DATE",true,openingSource);
        put(rules,document,"错别字检查","全文信息检查","MEDIUM","FULL_TEXT_REVIEW","typos","REVIEW","检查文字中的疑似错别字；专有名称和图像文字需人工复核","",true,null);
        put(rules,document,"全文证件有效期覆盖检查","效期检查","HIGH","FULL_TEXT_REVIEW","certificateCoverage","REVIEW",deadline,"DATE",true,deadlineSource);
        return List.copyOf(rules.values());
    }
    private void qualification(Map<String,Checkpoint> rules,Document doc,String line,Evidence source) {
        // Remove explanatory notes, but retain explicit instructions in notes (for example stamping copies).
        String[] notes=line.split("(?:^|[。；;\\s])注\\s*[:：]",2);
        String requirements=notes[0];
        if(notes.length>1 && notes[1].matches(".*(?:须|需|应|必须).{0,16}(?:提供|提交|加盖|盖章|签字|签署).*")) requirements+="；"+notes[1];
        List<String> clauses=new ArrayList<>();
        for(String part:splitRequirements(requirements)) {
            String item=stripNumber(part.strip());
            if(item.matches("^(?:并|且|还|同时|以及).*" ) && !clauses.isEmpty()) {
                int last=clauses.size()-1;
                clauses.set(last,clauses.get(last)+"；"+item);
            } else if(!item.isBlank()) clauses.add(item);
        }
        for(String item:clauses) {
            if(item.matches("^[^，,。；;:：]{1,30}[:：]$")) continue;
            if(!actionable(item) && !(item.contains("\t") && !item.substring(item.lastIndexOf('\t')+1).isBlank())) continue;
            boolean conditional=conditional(item);
            Matcher provision=Pattern.compile("(?:提供|提交|出具)").matcher(item);
            if(provision.find() && !conditional) {
                String background=item.substring(0,provision.start());
                // Objective thresholds and eligibility restrictions remain part of the material requirement.
                boolean constraint=background.matches(".*(?:[0-9]|[一二三四五六七八九十甲乙丙丁]+级|不少于|不低于|至少|不|未|无|禁|须具备|应具备|年限|有效期|独立法人资格|资质|证书|许可证|职称|负责人|安全员|人员要求).*" )
                    || item.matches(".*(?:不得参与|不得参加|不得同时|禁止|严禁|禁入|不接受).*" );
                if(!constraint) item=item.substring(provision.start());
            }
            put(rules,doc,(conditional?"适用条件：":"资格材料：")+shorten(item),"资格检查","HIGH","SEMANTIC_RESPONSE",conditional?"conditionalMaterial":"qualification","RESPONDS",item,"",!conditional,source);
        }
    }
    private void scoring(Map<String,Checkpoint> rules,Document doc,Clause clause,List<LocatedLine> located,Set<String> consumed) {
        String line=scoringBody(clause.text()); Evidence source=clause.source();
        if(located.get(clause.start()).page().text().stripLeading().startsWith("注：")) return;
        // Each independently scored certificate becomes one candidate; alternatives within a certificate stay together.
        if(!line.contains("方案") && line.matches(".*(?:证书|认证).*" ) && line.matches(".*(?:具备|具有|提供|获得|取得).*")) {
            String proof=""; Matcher proofMatch=Pattern.compile("(?:证明材料|证明文件要求)[:：](.*)").matcher(line);
            if(proofMatch.find()) { proof=proofMatch.group().strip(); line=line.substring(0,proofMatch.start()); }
            for(String part:line.split("(?=[（(][0-9一二三四五六七八九十]+[）)])|[；;]")) {
                if(!part.matches(".*(?:证书|认证).*" ) || !part.matches(".*(?:具备|具有|提供|获得|取得).*")) continue;
                String requirement=part.replaceFirst("^.*?(?:具备|具有|提供|获得|取得)(?:有效的?|的)?\\s*","").strip();
                // Keep validity, copies, thresholds and proof instructions; strip only the score awarded.
                requirement=requirement.replaceFirst("[，,]?\\s*(?:可?得|计|获)[^分]*分.*$","").replaceFirst("的?[，,；;。]+$","");
                requirement=requirement.replaceFirst("证书的$","证书");
                String subject=part.replaceFirst("^(?:[\\s/]*[（(][0-9一二三四五六七八九十]+[）)])?\\s*","").split("(?:具备|具有|提供|获得|取得)",2)[0].strip();
                if(subject.matches("(?:施工单位|设计单位|联合体牵头人|联合体成员|项目负责人|设计负责人)")) requirement=subject+"："+requirement;
                if(!proof.isBlank()) requirement+="。"+proof;
                put(rules,doc,requirement,"响应检查","MEDIUM","SEMANTIC_RESPONSE","scoring","RESPONDS",requirement,"",true,source);
            }
            return;
        }
        if(line.matches(".*(?:方案|施工组织设计).*" ) && line.matches(".*(?:提供|提交|编制|制定|包括|包含|完整性|建设目标|人员构成).*")) {
            List<String> parts=new ArrayList<>(scoringParts(line));
            int last=clause.end(); boolean foundBand=parts.stream().anyMatch(this::band);
            for(int next=last+1;next<located.size();) {
                String candidate=located.get(next).text();
                if(boundary(candidate) || newScoringItem(candidate)) break;
                Clause following=clause(doc,located,next);
                List<String> additions=scoringParts(scoringBody(following.text()));
                boolean hasBand=additions.stream().anyMatch(this::band);
                boolean intro=rubricIntro(following.text());
                if(candidate.contains("\t") && !hasBand && !intro) break;
                if(foundBand && !hasBand && !intro) break;
                // Before a rubric, preserve all plan contents, including numbered and wrapped subclauses.
                if(!hasBand && !intro && independentRequirement(candidate) && !candidate.matches(".*(?:方案包括|方案包含|方案内容|保障|风险|措施).*")) break;
                parts.addAll(additions); consumed.addAll(following.anchors());
                foundBand|=hasBand; last=following.end(); next=last+1;
            }
            StringBuilder requirement=new StringBuilder();
            List<String> best=new ArrayList<>(); double bestScore=-1;
            for(String part:parts) {
                if(band(part)) {
                    double value=score(part);
                    if(value>bestScore) { best.clear(); bestScore=value; }
                    if(value==bestScore) best.add(part);
                } else if(!rubricIntro(part)) requirement.append(part);
            }
            if(!best.isEmpty()) {
                if(!requirement.toString().matches(".*[。；;]$")) requirement.append("。");
                List<String> standards=best.stream().map(part->stripNumber(part).replaceFirst("(?:的?为[优良中差]|[，,]?\\s*(?:可?得|计|获)).*$","").replaceFirst("[，,。；;]+$","")).toList();
                requirement.append(String.join("；或",standards)).append("。");
            }
            source=spanEvidence(doc,located,clause.start(),last);
            put(rules,doc,(best.isEmpty()?"方案响应（最高档待确认）：":"方案最高档要求：")+shorten(line),"响应检查","MEDIUM","SEMANTIC_RESPONSE","scoring","RESPONDS",requirement.toString(),"",true,source);
            return;
        }
        // A score total or tier label alone is not an actionable response requirement.
        if(line.matches(".*(?:本项|该项|此项)满分\\s*[0-9一二三四五六七八九十]+.*")
            && !line.matches(".*(?:提供|提交|具备|具有|须|应当|至少|不少于|满足|符合).*")) return;
        if(actionable(line) || line.matches(".*(?:业绩|合同|方案).*"))
            put(rules,doc,"响应评分："+shorten(line),"响应检查","MEDIUM","SEMANTIC_RESPONSE","scoring","RESPONDS",highestDiscreteBand(line),"",true,source);
    }
    private double score(String line) {
        Matcher clause=Pattern.compile("(?:得|计|获)([^分]*)分").matcher(line);
        double max=-1;
        while(clause.find()) { Matcher number=Pattern.compile("[0-9]+(?:\\.[0-9]+)?").matcher(clause.group(1)); while(number.find()) max=Math.max(max,Double.parseDouble(number.group())); }
        return max<0 && line.matches(".*(?:不得分|不计分|无分).*" )?0:max;
    }
    private boolean band(String text) { return score(text)>=0 && text.matches("^[（(]?(?:[A-ZＡ-Ｚ]|[0-9一二三四五六七八九十]+)[）)、.．:：].*"); }
    private List<String> scoringParts(String text) {
        return Arrays.stream(text.split("(?=[A-ZＡ-Ｚ][、．:：]|(?<![A-Za-z0-9])[A-Z]\\.\\s)|(?=[（(][0-9一二三四五六七八九十]+[）)])"))
            .map(s->s.replaceFirst("^[\\s/]+","").replaceFirst("[\\s/]+$","")).filter(s->!s.isBlank()).toList();
    }
    private String scoringBody(String text) { return text.replaceFirst("\\t\\s*-?[0-9]+(?:\\.[0-9]+)?\\s*$","").replaceAll("\\s+/\\s+"," "); }
    private boolean rubricIntro(String text) { return text.matches("^(?:以上|根据|按|分为|评分档次|评分标准).*(?:档次|评分|档).*" ); }
    private String highestDiscreteBand(String text) {
        if(text.matches(".*(?:每有|每增加|每提供|每项|最高|扣|价格得分).*")) return text;
        String[] proof=text.split("(?=(?:证明材料|证明文件要求)[:：])",2);
        Matcher award=Pattern.compile("([^，,。；;]*(?:得|计|获)\\s*[0-9]+(?:\\.[0-9]+)?\\s*分)").matcher(proof[0]);
        String best=""; double maximum=-1; int count=0;
        while(award.find()) { count++; double value=score(award.group()); if(value>maximum) { maximum=value; best=award.group(); } }
        if(count<2) return text;
        return best.replaceFirst("[，,]?\\s*(?:得|计|获)[^分]*分$","")+"。"+(proof.length>1?proof[1]:"");
    }
    private boolean newScoringItem(String line) {
        if(band(line)) return false;
        String clean=stripNumber(line);
        return clean.matches("^(?:提供|提交|编制|制定).{0,50}方案.*")
            || clean.matches("^(?:[^\\t]{1,80}\\t).*(?:完整性|提供|包括|包含).*" ) && !rubricIntro(clean)
            || clean.matches("^[^。；;，,]{1,70}(?:方案|能力|业绩|证书|评价)[（(]\\s*[0-9.]+\\s*分[）)]$");
    }
    private boolean actionable(String text) {
        return text.matches(".*(?:应(?:当|提供|提交|具备|为|满足|符合|具有|响应|承诺|在|不)|须|具备|具有|提供|提交|出具|资格|资质|符合|满足|不得|未被|未列入|未处于|不接受|不少于|不低于|无不良|无重大|无违法|不在|严禁|禁止|独立承担|注册资本|注册资金|[0-9一二三四五六七八九十甲乙丙丁]+级|响应|合格).*" );
    }
    private boolean conditional(String text) { return text.matches(".*(?:若|如为|如系|如果|分支机构|联合体投标时|适用时|如有|不适用|无需|无须|不需).*" ); }
    private List<String> splitRequirements(String text) {
        List<String> result=new ArrayList<>(); int start=0,depth=0;
        if(text.contains(" / ")) {
            for(String paragraph:text.split("\\s+/\\s+")) {
                if(!result.isEmpty() && paragraph.strip().matches("^(?:须|需|应)?(?:提供|提交|出具).*(?:证明|证书|截图|扫描).*")) {
                    int last=result.size()-1; result.set(last,result.get(last)+" "+paragraph.strip());
                } else result.addAll(splitRequirements(paragraph));
            }
            return result;
        }
        // A prohibition may name several service-provider roles before its shared negative condition.
        if(text.matches(".*(?:为本项目|为招标人).*(?:提供).*(?:不得参与|不得参加|禁止).*" ) && !text.contains(" / ")) return List.of(text);
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='（' || c=='(' || c=='《') depth++;
            else if(c=='）' || c==')' || c=='》') depth=Math.max(0,depth-1);
            boolean branch=i>start && (c=='若' || c=='如') && text.substring(i).matches("^(?:若|如果|如为|如系)(?:应答人|投标人|供应商|申请人).*" );
            boolean paragraph=c=='/' && i>0 && i+1<text.length() && Character.isWhitespace(text.charAt(i-1)) && Character.isWhitespace(text.charAt(i+1));
            if(paragraph && text.substring(i+1).stripLeading().matches("^[（(][0-9]+[）)].*")) depth=0;
            if(depth==0 && (c=='；' || c==';' || branch || paragraph)) {
                result.add(text.substring(start,i)); start=branch?i:i+1;
            }
        }
        result.add(text.substring(start)); return result;
    }
    private String stripNumber(String text) {
        return text.replaceFirst("^[0-9]+(?:\\.[0-9]+)+(?![0-9.])[\\s、.．]*","")
            .replaceFirst("^(?:[（(]?[0-9一二三四五六七八九十]+[）)、.．]\\s*|[A-ZＡ-Ｚ][、.．:：]\\s*)","");
    }
    private boolean boundary(String text) { return scopeExit(text) || !section(text).isBlank() || text.matches("^第[一二三四五六七八九十百0-9]+[章篇].*"); }
    private boolean scopeExit(String text) {
        String heading=stripNumber(text).strip();
        return heading.matches("(?:招标文件(?:的)?获取|获取招标文件|投标文件(?:的)?递交|招标组织形式|资格审查方法|资格审查|评审程序|评标方法|初步评审|评标报告|踏勘现场|投标预备会|招标文件(?:的)?组成|分值汇总表)[:：]?");
    }
    private Set<String> chapterReferences(List<LocatedLine> lines) {
        Set<String> references=new HashSet<>();
        for(int i=0;i<lines.size();) {
            if(!lines.get(i).text().matches("^第[一二三四五六七八九十百0-9]+[章篇]\\s*.{1,60}$")) { i++; continue; }
            int end=i+1;
            while(end<lines.size() && lines.get(end).text().matches("^第[一二三四五六七八九十百0-9]+[章篇]\\s*.{1,60}$")) end++;
            if(end-i>1) for(int j=i;j<end;j++) references.add(lines.get(j).anchor());
            i=end;
        }
        return references;
    }
    private String materialCondition(String material,List<LocatedLine> lines) {
        String name=material.replaceAll("[（(][^）)]*[）)]","").replaceAll("\\s+","");
        Pattern associated=Pattern.compile("《([^》]+)》\\s*[（(]([^）)]*(?:无需|无须|不需|免于)[^）)]*)[）)]");
        for(LocatedLine line:lines) {
            Matcher matcher=associated.matcher(line.text());
            while(matcher.find()) {
                String referenced=matcher.group(1).replaceAll("\\s+","");
                if(referenced.equals(name) || referenced.endsWith(name) && name.length()>=5) return matcher.group(2);
            }
        }
        return "";
    }
    private boolean independentRequirement(String text) {
        return text.matches("^(?:[（(]?[0-9一二三四五六七八九十]+[）)、.．]\\s*)?(?:若|如果|如为|如系|投标人|应答人|供应商|申请人|提供|提交|具有|具备|不得|未被|不接受).*" );
    }
    private int position(List<LocatedLine> lines,Evidence source) {
        for(int i=0;i<lines.size();i++) if(lines.get(i).anchor().equals(source.anchor())) return i;
        throw new IllegalArgumentException("Missing source position: "+source.anchor());
    }
    private Clause clause(Document doc,List<LocatedLine> lines,int start) {
        StringBuilder text=new StringBuilder(lines.get(start).text());
        List<String> anchors=new ArrayList<>(); anchors.add(lines.get(start).anchor());
        int end=start;
        while(end+1<lines.size()) {
            String next=lines.get(end+1).text(),previous=lines.get(end).text();
            if(boundary(next) || next.contains("\t") || next.matches("^[（(]?(?:[0-9一二三四五六七八九十]+|[A-ZＡ-Ｚ])[）)、.．:：].*")) break;
            boolean unfinished=bracketDepth(text.toString())>0;
            boolean continuation=next.matches("^(?:的|或|并|且|还须|同时|以及|注\\s*[:：]).*");
            boolean alternative=next.matches("^[①②③④⑤⑥⑦⑧⑨⑩].*" ) && text.toString().matches(".*(?:条件之一|以下任一|下列任一|任一条件|满足其一).*" );
            boolean newLabeledParagraph=lines.get(end+1).page()!=lines.get(end).page() && next.matches("^[^，,。；;]{1,45}[:：].*" ) && !next.startsWith("证明材料");
            // Broken source brackets must not absorb subsequent complete paragraphs or independent labels.
            if(newLabeledParagraph || !continuation && !alternative && previous.matches(".*[。；;!?！？]$")) break;
            if(!unfinished && !continuation && !alternative && (previous.matches(".*[:：]$") || independentRequirement(next) && !previous.matches(".*[，,、]$"))) break;
            if(!unfinished && !continuation && !actionable(text.toString()) && !actionable(next)) break;
            if(!unfinished && !continuation && next.matches("[^，,。；;：:]{1,40}(?:要求|标准|办法|方案|证书)$")) break;
            char previousChar=text.charAt(text.length()-1),nextChar=next.charAt(0);
            if(previousChar<128 && nextChar<128 && Character.isLetterOrDigit(previousChar) && Character.isLetterOrDigit(nextChar)) text.append(' ');
            text.append(next); end++; anchors.add(lines.get(end).anchor());
        }
        return new Clause(start,end,text.toString(),spanEvidence(doc,lines,start,end),List.copyOf(anchors));
    }
    private int bracketDepth(String text) {
        int depth=0;
        for(char c:text.toCharArray()) { if(c=='(' || c=='（' || c=='《') depth++; else if(c==')' || c=='）' || c=='》' || c==']' || c=='］') depth=Math.max(0,depth-1); }
        return depth;
    }
    private Evidence spanEvidence(Document doc,List<LocatedLine> lines,int start,int end) {
        LocatedLine first=lines.get(start),last=first;
        for(int i=start;i<=end && lines.get(i).page()==first.page();i++) last=lines.get(i);
        // Evidence remains a verbatim substring at one navigable location, even for a cross-page requirement.
        String pageText=first.page().text(); int from=0,to=0,lineNumber=0;
        Matcher matcher=Pattern.compile("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").matcher(pageText);
        while(matcher.find()) {
            lineNumber++;
            if(lineNumber==first.line()) from=matcher.start()+matcher.group().indexOf(first.text());
            if(lineNumber==last.line()) { to=matcher.start()+matcher.group().lastIndexOf(last.text())+last.text().length(); break; }
        }
        return EngineText.evidence(doc,first.page(),first.line(),to>from?pageText.substring(from,to):first.text());
    }
    private List<String> physicalLines(Page page) { return Arrays.stream((page.text()==null?"":page.text()).split("\\R",-1)).map(String::trim).toList(); }
    private String shorten(String text) { return text.substring(0,Math.min(text.length(),45)); }
    private record LocatedLine(Page page,int line,String text) { String anchor() { return "p"+page.number()+"-l"+line; } }
    private record Clause(int start,int end,String text,Evidence source,List<String> anchors) {}
    private void numeric(Map<String,Checkpoint> rules,Document doc,String line,Evidence source,String field,String title,String label,String fallback,String unit) {
        Matcher m=Pattern.compile(label+"\\s*(?:[:：=为约是]|要求|不少于|不低于|不得低于|不超过|不高于|不得少于|不得超过|至少|至多|最长|最短|自投标截止之日起|合同签订后|合同签订之日起|\\s)*"+EngineText.NUMBER+"\\s*"+EngineText.TIME_UNIT+"\\s*(?:以内|以下|以上)?").matcher(line);
        while(m.find()) {
        String clause=m.group();
        if("duration".equals(field) && !"duration".equals(FactExtractor.durationField(clause))) {
            put(rules,doc,clause+"要求","商务响应","HIGH","SEMANTIC_RESPONSE",FactExtractor.durationField(clause),"RESPONDS",clause,"",true,source);
            continue;
        }
        String op=clause.matches(".*(?:不超过|不得超过|不高于|最长|以内|以下|至多).*")?"<=":clause.matches(".*(?:不少于|不得少于|不低于|不得低于|至少|最短|以上).*")?">=":fallback;
        String normalized=EngineText.time(m.group(1),m.group(2),field);
        String actualUnit=unit;
        if(normalized.contains(":")) { String[] parts=normalized.split(":",2); actualUnit=parts[0]; normalized=parts[1]; }
        put(rules,doc,title+"要求","商务响应","HIGH","VALUE_COMPARE",field,op,normalized,actualUnit,true,source);
        }
    }
    private String section(String line) {
        for(String cell:line.split("\\t")) {
            String heading=cell.replaceFirst("^[★☆]\\s*","").replaceFirst("^(?:第[一二三四五六七八九十百0-9]+[章篇]\\s*|[（(]?[0-9]+(?:\\.[0-9]+)*[）)、.．]?\\s*|[（(]?[一二三四五六七八九十]+[）)、．.]\\s*)","").replaceFirst("^[★☆]","").trim();
            if(heading.matches("(?:(?:投标人|应答人|供应商|申请人)的?)?(?:资格审查(?:标准|表)|资格评审(?:标准|表)?|资格要求|资格条件)[:：]?")) return "QUALIFICATION";
            if(heading.matches("(?:详细评审(?:标准)?|评分标准|评分办法|评分细则|商务技术打分表|响应评审与评分项|评审标准|商务评审标准|技术评审标准)[:：]?")) return "SCORING";
            if(heading.matches("(?:符合性审查(?:标准)?|符合性评审(?:标准)?|形式评审(?:标准)?|响应性?评审(?:标准)?|初步评审表)[:：]?")) return "COMPLIANCE";
            if(line.indexOf('\t')<0 && heading.matches("(?:投标|应答|响应)文件格式[:：]?")) return "MATERIAL";
        }
        return "";
    }
    private String materialHeading(String line,FormatHierarchy hierarchy) {
        if(hierarchy.catalogClosed) return "";
        String catalogName=line.replaceFirst("^[★☆]","").strip();
        if(catalogName.matches("^第[一二三四五六七八九十0-9]+分册\\s*.+")) hierarchy.catalog=true;
        if(hierarchy.catalog) {
            if(!hierarchy.catalogHeadings.add(catalogName)) { hierarchy.catalogClosed=true; return ""; }
            if(!catalogName.matches(".*[\\t：:；;。].*")) return catalogName;
        }
        if(line.matches(".*[\\t；;。].*")) return "";
        String clean=line.replaceFirst("^[★☆]","");
        boolean numbered=clean.matches("^(?:(?:附件|附表|格式)\\s*)?[（(]?[一二三四五六七八九十0-9]+(?:[.-][0-9]+)*[）)、.．\\s:：]+.*");
        Matcher decimal=Pattern.compile("^([0-9]+(?:\\.[0-9]+)+)(?:[、.．]|\\s).*|^([0-9]+(?:\\.[0-9]+)+)(?=[\\u4e00-\\u9fff]).*").matcher(clean);
        if(decimal.matches()) {
            String prefix=decimal.group(1)==null?decimal.group(2):decimal.group(1);
            if(prefix.split("\\.").length>2) return "";
        } else if(clean.matches("^[一二三四五六七八九十]+[、．.].*")) {
            hierarchy.chineseTop=true; hierarchy.chineseSub=false;
        } else if(clean.matches("^[（(][一二三四五六七八九十]+[）)].*")) hierarchy.chineseSub=true;
        else if(clean.matches("^[（(][0-9]+[）)].*") && hierarchy.chineseTop || clean.matches("^[0-9]+[、．.].*") && hierarchy.chineseSub) return "";
        String name=clean.replaceFirst("^(?:(?:附件|附表|格式)\\s*)?[（(]?[一二三四五六七八九十0-9]+(?:[.-][0-9]+)*[）)、.．\\s:：]+","").replaceAll("[（(](?:格式|样本|范本)[）)]","").replaceFirst("[:：]$","").trim();
        if(name.matches(".*(?:编制要求|注意事项|目录|密封|装订|须知|无要求|应当|必须|遵守法律|请填写|须提供|应提供|不得|无需).*" ) || name.matches(".*[:：].*")) return "";
        if(numbered && !name.isBlank() && !name.matches(".*[，,].*")) return name;
        return name.matches(".*(?:投标函|应答函|响应函|封面|索引表|偏离表|证明等文件|情况表|信息表|信息一览表|证明文件|资格证明|身份证明|授权委托书|协议书|承诺书|合规承诺|技术建议书|报价文件|商务文件|技术文件|商务部分|技术部分|资格部分|报价部分|报价表|明细表|清单|方案|计划|企业简介|组织机构|营业执照|资质证书|财务报表|审计报告|投标报价)(?:[（(]如有[）)])?")?name:"";
    }
    private static final class FormatHierarchy { boolean chineseTop; boolean chineseSub; boolean catalog; boolean catalogClosed; Set<String> catalogHeadings=new HashSet<>(); }
    private String years(String text) {
        Set<Integer> years=new TreeSet<>(); Matcher m=Pattern.compile("20\\d{2}").matcher(text);
        while(m.find()) years.add(Integer.parseInt(m.group()));
        if(years.size()==2 && text.matches(".*(?:至|到|—|－|~|～|-).*")) {
            int start=years.iterator().next(),end=((TreeSet<Integer>)years).last();
            if(end-start<=10) for(int year=start;year<=end;year++) years.add(year);
        }
        return years.isEmpty()?"":"YEAR:"+String.join(",",years.stream().map(Object::toString).toList());
    }
    private void put(Map<String,Checkpoint> rules,Document doc,String title,String category,String severity,String type,String field,String op,String expected,String unit,boolean required,Evidence source) {
        category=switch(category) {
            case "组成检查","签章检查" -> "形式检查";
            case "资格审查","资格评审" -> "DATE_VALIDITY".equals(type)?"效期检查":"资格检查";
            case "商务响应","报价检查","评分检查","符合性检查","技术响应" -> "ENTITY_FIELD_CONSISTENCY".equals(type)?"全文信息检查":"响应检查";
            case "一致性检查" -> "全文信息检查";
            default -> category;
        };
        String key=category+"|"+type+"|"+field+"|"+op+"|"+expected+"|"+unit;
        rules.putIfAbsent(key,new Checkpoint(EngineText.id(doc.id()+"|"+doc.version()+"|"+key),title,category,severity,type,field,op,expected,unit,required,false,true,source));
    }
}
