package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Issued by the Host from a verified identity in exactly one frozen inventory row. */
final class ContractEvidenceRecord {
    static final String FIELD_SOURCES="recordFieldSourceIds";
    private static final Pattern BODY_HEADING=Pattern.compile("(?m)^[ \\t#|]*(?:第[0-9一二三四五六七八九十]+(?:部分|章|节)[ \\t]*)?(?:合同协议书|合同正文|专用合同条款|通用合同条款|合同条款)[ \\t|]*$");
    private static final Pattern SIGNING_HEADING=Pattern.compile("(?m)^[ \\t#|]*(?:签署页|签字盖章页|签字页)[ \\t]*(?:[（(]此页无正文[）)])?[ \\t|]*$");
    final String id, name, number;
    final List<String> anchors;
    final Map<String,Object> row;

    ContractEvidenceRecord(Map<String,Object> row, Fact fact) {
        this.row = row;
        name = str(fact.attributes(), "contractName");
        number = str(fact.attributes(), "contractNumber");
        anchors = Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(fact.sourceIds())));
        id = identity(String.join("|", str(row,"inventoryIdentity"), str(row,"documentHash"),
            str(row,"documentVersion"), clean(number), clean(name), String.join(",", anchors)));
    }

    Map<String,Object> receipt() {
        return linked("recordId", id, "contractNumber", number, "contractName", name,
            "anchorSourceIds", anchors, "businessProof", false);
    }

    /** Older receipts identified body pages by subtracting the host's identity anchors. */
    static Set<String> fieldSourceIds(Fact fact) {
        if(fact.attributes().containsKey(FIELD_SOURCES))
            return new LinkedHashSet<>(ProofVerifier.strings(fact.attributes().get(FIELD_SOURCES)));
        Set<String> body=new LinkedHashSet<>(fact.sourceIds());
        body.removeAll(ProofVerifier.strings(fact.attributes().get("recordAnchorSourceIds")));
        return body;
    }

    static boolean permitsOverlappingField(String field,Source source) {
        if(source==null||source.index()||ContractRecordBindings.isOrder(source.evidence().quote()))return false;
        String quote=source.evidence().quote();
        if(quote.matches("(?s).*(?:本页仅载明合同身份|仅供合同身份识别|仅作封面导航|填写示例|空白合同).*"))return false;
        return BODY_HEADING.matcher(quote).find() || "signedDate".equals(field)&&SIGNING_HEADING.matcher(quote).find();
    }

    String bind(Map<String,Object> atom, Map<String,Source> readSources) {
        Map<String,Object> attrs = new LinkedHashMap<>((Map<String,Object>)atom.get("attributes"));
        String suppliedNumber = str(attrs,"contractNumber"), suppliedName = str(attrs,"contractName");
        if (!number.isEmpty() && !suppliedNumber.isEmpty() && !clean(number).equals(clean(suppliedNumber))
                || !name.isEmpty() && !suppliedName.isEmpty() && !ContractCandidateFields.sameTitle(name,suppliedName))
            return "CONTRACT_IDENTITY_CONFLICT";
        Set<String> refs = new LinkedHashSet<>(ProofVerifier.strings(atom.get("sourceIds")));
        for (String sourceId : refs) {
            Source source = readSources.get(sourceId);
            if (source == null || !DeclaredPerformanceRows.contains(row, source)) return "CONTRACT_PAGE_LINK_UNPROVEN";
        }
        if (!number.isEmpty()) attrs.put("contractNumber", number);
        if (!name.isEmpty()) attrs.put("contractName", name);
        if (!ContractCandidateFields.identityAttribute(str(atom,"field")).isEmpty()
                && refs.equals(new LinkedHashSet<>(anchors))) {
            // The same independently cited identity must keep its original canonical fact ID.
            atom.put("attributes",attrs);
            return null;
        }
        attrs.put("recordAnchorSourceIds", anchors);
        attrs.put("contractRecordId", id);
        attrs.put("recordAnchorName", name);
        attrs.put("recordAnchorNumber", number);
        // Only an explicitly cited overlapping field page needs extra provenance.
        // Existing non-overlapping observations keep their canonical IDs.
        if(!Collections.disjoint(refs,anchors)){
            Set<String> fields=new LinkedHashSet<>(refs);
            fields.removeIf(sourceId->anchors.contains(sourceId)&&!permitsOverlappingField(str(atom,"field"),readSources.get(sourceId)));
            if(!Collections.disjoint(fields,anchors)){
                // A missing and an explicitly empty identity attribute describe the same
                // issued record. A retry should reuse the field accepted from this page.
                if(str(attrs,"contractNumber").isEmpty())attrs.remove("contractNumber");
                if(str(attrs,"contractName").isEmpty())attrs.remove("contractName");
                attrs.put(FIELD_SOURCES,new ArrayList<>(fields));
            }
        }
        refs.addAll(anchors);
        atom.put("attributes", attrs);
        atom.put("sourceIds", new ArrayList<>(refs));
        return null;
    }
}
