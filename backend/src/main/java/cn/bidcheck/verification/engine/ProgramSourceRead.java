package cn.bidcheck.verification.engine;

import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Deterministic text readers retain their own immutable scan receipt, never a model-read claim. */
final class ProgramSourceRead {
    static final String VERSION="program-source-read-1";
    private ProgramSourceRead(){}
    static boolean applies(Rule rule){return ConditionalMaterialEvidence.applies(rule)||BidLetterResponse.applies(rule);}
    static Map<String,Object> receipt(EvidenceCorpus corpus,List<Source> candidates){
        List<Map<String,Object>> documents=corpus.documents.stream().map(d->linked("documentId",d.id(),"documentHash",d.sha256(),"documentVersion",d.version(),
                "decodedSourceRevision",identity(d.pages().stream().map(p->p.number()+":"+p.locationType()+":"+Objects.toString(p.text(),"")+":"+Objects.toString(p.parser(),"")+":"+Objects.toString(p.warning(),"")).toList().toString()))).toList();
        return linked("version",VERSION,"executorVersions",List.of(ConditionalMaterialEvidence.VERSION,BidLetterResponse.VERSION),"documents",documents,
                "sourceRevision",identity(documents.toString()),"scannedSourceCount",corpus.all.size(),"candidateSourceIds",candidates.stream().map(Source::id).distinct().toList(),
                "readScope","FROZEN_DECODED_TEXT","unreadImageCoverageProven",false,"modelExtractionRequired",false,"businessProof",false);
    }
    static boolean proven(Rule rule,Decision decision,RuleEvaluator evaluator){
        if(!applies(rule)||!rule.children().isEmpty()||!Set.of("PASS","FAIL").contains(decision.status())||!decision.gaps().isEmpty()||decision.evidences().isEmpty()||!rule.id().equals(decision.id()))return false;
        Decision fresh=ConditionalMaterialEvidence.applies(rule)?ConditionalMaterialEvidence.evaluate(rule,evaluator):BidLetterResponse.evaluate(rule,evaluator);
        // Re-execution rebinds source conditions, company/people, project and all returned values.
        // Old receipt IDs and model-provided contentPresent flags are never sufficient.
        return fresh.status().equals(decision.status())&&fresh.gaps().isEmpty()&&fresh.evidences().equals(decision.evidences())&&fresh.proof().equals(decision.proof());
    }
}
