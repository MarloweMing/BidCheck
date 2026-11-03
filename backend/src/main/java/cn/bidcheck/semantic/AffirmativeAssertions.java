package cn.bidcheck.semantic;

import java.util.regex.Pattern;

/** Detects affirmative conclusions, not proof that those conclusions are correct. */
final class AffirmativeAssertions {
    private AffirmativeAssertions() { }
    private static final String VISUAL="(?:签字|签名|签署|签章|盖章|公章|印章|单位章|专用章)";
    private static final String CERTIFICATE="(?:证书|证照|许可证|资格证|资质证|认证证|注册证|执业证|三体系)";
    private static final Pattern VISUAL_TERM=Pattern.compile(VISUAL);
    private static final Pattern CERTIFICATE_TERM=Pattern.compile(CERTIFICATE);
    private static final Pattern UNCERTAIN=Pattern.compile(
        "(?:待|尚待)(?:核实|确认|复核|核验)|是否|存疑|尚不明确|"
        +"(?:无法|不能|未能|难以|不足以|尚未)(?:.{0,16})(?:确认|判断|核实|核验|证明)|"
        +"(?:需|需要|仍需|有待|建议|请|应当|(?<!响)应)(?:.{0,12})(?:核对|核实|复核|核验|确认)");
    private static final Pattern TEMPLATE=Pattern.compile(
        "模板提示|(?:模板|格式).{0,12}(?:标注|注明|提示|写明|要求)|"
        +"(?:标注|注明|提示|文字写有|文字注明|文本写有).{0,8}[‘“\"']|"
        +"(?:签字|盖章|签章)(?:栏|位置|处)(?:为空|待填|空白)|"
        +"(?:投标人|法定代表人|授权代表)[（(](?:签字|签章|盖章)[）)]");
    private static final Pattern REQUIREMENT=Pattern.compile(
        "(?:要求|应当|必须|须|(?<!响)应|需要|需)(?:.{0,35})(?:"+VISUAL+"|"+CERTIFICATE+"|有效)|"
        +"(?:"+VISUAL+"|"+CERTIFICATE+").{0,20}(?:属于|为)(?:必填|要求|模板)");
    private static final Pattern VISUAL_NEGATIVE=Pattern.compile(
        "(?:未|没有|并未|尚未|未见|缺少|缺失|遗漏|漏)(?:.{0,8})"+VISUAL+"|"
        +VISUAL+".{0,12}(?:不齐全|不完整|缺失|缺少|不符合|无效)|无需.{0,12}"+VISUAL);
    private static final Pattern VISUAL_AFFIRMATIVE=Pattern.compile(
        "(?:已|已经|完成|载有|带有|附有|可见).{0,24}"+VISUAL+"|"
        +VISUAL+".{0,20}(?:齐全|完整|完备|符合要求|真实有效|已完成|清晰可见)|"
        +"(?:投标人|供应商|法定代表人|法人代表|授权代表|委托代理人|负责人|投标函|授权书|承诺书|投标文件|响应文件)"
        +".{0,24}(?:签字|签名|签署|签章|盖章|加盖.{0,8}(?:公章|印章|单位章|专用章))|"
        +"加盖.{0,12}(?:公章|印章|单位章|专用章)");
    private static final Pattern CERTIFICATE_NEGATIVE=Pattern.compile(
        CERTIFICATE+".{0,30}(?:无效|失效|已过期|已经过期|不在有效期内|超出有效期)|"
        +"(?:无效|失效|过期)的?"+CERTIFICATE);
    private static final Pattern CERTIFICATE_AFFIRMATIVE=Pattern.compile(
        CERTIFICATE+".{0,50}(?:在有效期内|处于有效期|未过期|没有过期|覆盖.{0,18}(?:投标|截止|开标|参照|基准)|"
        +"(?:均|全部|皆|仍|目前|当前|保持|合法|真实)?有效(?!期|性))|"
        +"(?:具有|持有|提供|具备).{0,10}有效的?.{0,8}"+CERTIFICATE+"|有效(?:的)?"+CERTIFICATE);

    static boolean visualCompletion(String text) {
        for(String sentence:sentences(VisualAssertionScope.withoutBoundaries(text))) {
            if(!VISUAL_TERM.matcher(sentence).find()||excluded(sentence)||VISUAL_NEGATIVE.matcher(sentence).find()) continue;
            if(VISUAL_AFFIRMATIVE.matcher(sentence).find()) return true;
        }
        return false;
    }

    static boolean certificateValidity(String text) {
        for(String sentence:sentences(text)) {
            if(!CERTIFICATE_TERM.matcher(sentence).find()||excluded(sentence)||CERTIFICATE_NEGATIVE.matcher(sentence).find()) continue;
            if(CERTIFICATE_AFFIRMATIVE.matcher(sentence).find()) return true;
        }
        return false;
    }

    private static boolean excluded(String sentence) {
        // “已按要求签字” is an asserted completion; “要求签字” is only an obligation.
        String requirements=sentence.replaceAll("(?:已|已经)(?:按|按照)要求","");
        return UNCERTAIN.matcher(sentence).find()||TEMPLATE.matcher(sentence).find()||REQUIREMENT.matcher(requirements).find();
    }
    private static String[] sentences(String text) {
        if(text==null||cn.bidcheck.compat.Java8Api.isBlank(text)) return new String[0];
        return text.replaceAll("[\\t \\u00a0\\u3000]+","").split("[。！？!?；;\\r\\n]+");
    }
}
