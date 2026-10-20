package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import java.io.*;
import java.nio.file.*;
import java.security.Provider;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.cos.*;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.Store;
import org.springframework.stereotype.Service;
import static cn.bidcheck.verification.source.SourceSafety.map;

/** Verifies detached CMS bytes locally, independently of PKI trust and claimed signing time. */
@Service
public class DigitalSignatureInspector {
    private static final Provider BC=new BouncyCastleProvider();
    private static final int MAX_CONTAINER=1024*1024;
    public Map<String,Object> inspect(Document doc,Path original){
        try{
            String hash=SourceSafety.verify(doc,original);List<Map<String,Object>> signatures=new ArrayList<>();
            if(!"pdf".equals(SourceSafety.extension(doc)))return map("status","UNSUPPORTED","documentHash",hash,"signatures",signatures,"authenticityVerified",false,"limitations",List.of("当前仅验证PDF分离式CMS签名；Word签名包未验证。"));
            long length=Files.size(original);Budget budget=new Budget();
            try(var pdf=Loader.loadPDF(original.toFile(),IOUtils.createTempFileOnlyStreamCache())){
                if(pdf.isEncrypted())throw new IOException("SIGNATURE_SOURCE_ENCRYPTED");
                var source=pdf.getSignatureDictionaries();if(source.size()>100)throw new IOException("SIGNATURE_COUNT_LIMIT");int i=0;
                for(var signature:source){SourceSafety.interrupted();int[] range=signature.getByteRange();boolean valid=range!=null&&range.length==4&&range[0]==0&&range[1]>=0&&range[2]>range[1]&&range[3]>=0&&(long)range[2]+range[3]<=length;
                    COSBase value=signature.getCOSObject().getDictionaryObject(COSName.CONTENTS);byte[] contents=value instanceof COSString s?s.getBytes():null;boolean present=contents!=null&&contents.length>0;
                    Map<String,Object> crypto=verify(original,range,valid,contents,signature.getSubFilter(),budget);
                    signatures.add(map("index",++i,"byteRange",range,"byteRangeStructurallyValid",valid,"coversCurrentRevisionBytes",valid&&(long)range[2]+range[3]==length,"signatureContainerPresent",present,
                        "subFilter",signature.getSubFilter(),"claimedSigningTime",signature.getSignDate()==null?null:signature.getSignDate().toInstant().toString(),"cryptographicIntegrity",crypto.get("status"),"integrityDetail",crypto,
                        "trustChain","NOT_VERIFIED","revocation","NOT_CHECKED","trustedTimestamp","NOT_VERIFIED","certificateTimeValidity","NOT_VERIFIED"));
                }
            }
            return map("status",signatures.isEmpty()?"NO_SIGNATURE_CONTAINER":"SIGNATURES_INSPECTED","documentHash",hash,"signatures",signatures,"authenticityVerified",false,"limitations",List.of("VALID仅表示所列ByteRange覆盖字节与嵌入证书公钥的CMS签名一致；未覆盖当前版本时不证明后续变更完整性。","未配置可信根、吊销与可信时间戳验证；嵌入证书或自签证书不能据此获得可信身份，签署时点及证书期限未确认。","未发现数字签名字典不代表未手写签字或未盖实体章；扫描件签章另走图像核验。"));
        }catch(CancellationException e){throw e;}
        catch(Exception e){return map("status","ERROR","errorCode","SIGNATURE_INSPECTION_FAILED","signatures",List.of(),"authenticityVerified",false,"limitations",List.of("数字签名读取未完成，不能认定真实性通过。"));}
    }
    private static Map<String,Object> verify(Path file,int[] range,boolean rangeValid,byte[] contents,String subFilter,Budget budget){
        if(!rangeValid)return outcome("INVALID","BYTE_RANGE_INVALID",List.of());
        if(contents==null||contents.length==0)return outcome("NOT_VERIFIED","SIGNATURE_CONTAINER_MISSING",List.of());
        if(contents.length>MAX_CONTAINER)return outcome("NOT_VERIFIED","SIGNATURE_CONTAINER_LIMIT",List.of());
        if(!Set.of("adbe.pkcs7.detached","ETSI.CAdES.detached").contains(Objects.toString(subFilter,"")))return outcome("NOT_VERIFIED","SIGNATURE_SUBFILTER_UNSUPPORTED",List.of());
        CMSSignedDataParser parser=null;
        try{
            budget.check(0);if(!contentsGapMatches(file,range,contents,budget))return outcome("INVALID","BYTE_RANGE_CONTENTS_MISMATCH",List.of());
            byte[] cmsBytes=unpaddedContainer(contents);CMSSignedData structure=new CMSSignedData(cmsBytes);
            if(!structure.isDetachedSignature())return outcome("INVALID","DETACHED_SIGNATURE_REQUIRED",List.of());
            if(structure.getSignerInfos().size()<1||structure.getSignerInfos().size()>8||structure.getCertificates().getMatches(null).size()>32)return outcome("NOT_VERIFIED","SIGNER_OR_CERTIFICATE_LIMIT",List.of());
            try(InputStream signed=new BufferedInputStream(new ByteRanges(file,range,budget),64*1024)){
                parser=new CMSSignedDataParser(new JcaDigestCalculatorProviderBuilder().setProvider(BC).build(),new CMSTypedStream(signed),cmsBytes);
                parser.getSignedContent().drain();Store<X509CertificateHolder> certificates=parser.getCertificates();List<Map<String,Object>> signers=new ArrayList<>();boolean all=true;
                for(SignerInformation signer:parser.getSignerInfos().getSigners()){
                    budget.check(0);Collection<X509CertificateHolder> matches=certificates.getMatches(signer.getSID());
                    if(matches.size()!=1)return outcome("NOT_VERIFIED","SIGNER_CERTIFICATE_NOT_UNIQUE",signers);
                    X509CertificateHolder cert=matches.iterator().next();var publicKey=new JcaX509CertificateConverter().setProvider(BC).getCertificate(cert).getPublicKey();
                    if(publicKey instanceof java.security.interfaces.RSAPublicKey rsa&&rsa.getModulus().bitLength()>8192||publicKey instanceof java.security.interfaces.ECPublicKey ec&&ec.getParams().getCurve().getField().getFieldSize()>600)return outcome("NOT_VERIFIED","SIGNATURE_KEY_LIMIT",signers);
                    // Public-key overload avoids treating an untrusted claimed signingTime as a trusted certificate time.
                    boolean valid=signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider(BC).build(publicKey));all&=valid;
                    signers.add(map("certificateSha256",SourceSafety.digest(cert.getEncoded()),"digestAlgorithm",signer.getDigestAlgOID(),"signatureAlgorithm",signer.getEncryptionAlgOID(),"integrity",valid?"VALID":"INVALID","certificateTrust","NOT_VERIFIED"));
                }
                return outcome(all?"VALID":"INVALID",all?"CMS_DIGEST_AND_SIGNATURE_MATCH":"CMS_SIGNATURE_MISMATCH",signers);
            }
        }catch(CancellationException e){throw e;}
        catch(CMSSignerDigestMismatchException e){return outcome("INVALID","CMS_SIGNED_BYTES_MISMATCH",List.of());}
        catch(Exception e){return outcome("NOT_VERIFIED",e instanceof IOException&&"SIGNATURE_BUDGET_EXCEEDED".equals(e.getMessage())?e.getMessage():"CMS_INVALID_OR_UNSUPPORTED",List.of());}
        finally{if(parser!=null)try{parser.close();}catch(IOException ignored){}}
    }
    private static Map<String,Object> outcome(String status,String code,List<?> signers){return map("status",status,"code",code,"signers",signers);}
    private static byte[] unpaddedContainer(byte[] contents)throws IOException{
        ByteArrayInputStream input=new ByteArrayInputStream(contents);int used;
        try(ASN1InputStream asn=new ASN1InputStream(input)){if(asn.readObject()==null)throw new IOException("Empty CMS");used=contents.length-input.available();}
        for(int i=used;i<contents.length;i++)if(contents[i]!=0)throw new IOException("Non-zero data after CMS");
        return Arrays.copyOf(contents,used);
    }
    /** Only the actual hex Contents token may be excluded; arbitrary omitted document bytes cannot pass integrity. */
    private static boolean contentsGapMatches(Path path,int[] range,byte[] contents,Budget budget)throws IOException{
        int size=range[2]-range[1];if(size<2||size>2*MAX_CONTAINER+4096)return false;budget.check(size);byte[] gap=new byte[size];
        try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"r")){file.seek(range[1]);file.readFully(gap);}
        if(gap[0]!='<'||gap[size-1]!='>')return false;ByteArrayOutputStream decoded=new ByteArrayOutputStream();int high=-1;
        for(int i=1;i<size-1;i++){int ch=gap[i]&255;if(ch==0||ch==9||ch==10||ch==12||ch==13||ch==32)continue;int digit=Character.digit((char)ch,16);if(digit<0)return false;if(high<0)high=digit;else{decoded.write(high*16+digit);high=-1;}}
        if(high>=0)decoded.write(high*16);return Arrays.equals(contents,decoded.toByteArray());
    }
    private static final class Budget{
        long remaining=512L*1024*1024,deadline=System.nanoTime()+30_000_000_000L;
        void check(long bytes)throws IOException{SourceSafety.interrupted();remaining-=bytes;if(remaining<0||System.nanoTime()>deadline)throw new IOException("SIGNATURE_BUDGET_EXCEEDED");}
    }
    private static final class ByteRanges extends InputStream{
        final RandomAccessFile file;final int[] ranges;final Budget budget;int part;long remaining;
        ByteRanges(Path path,int[] ranges,Budget budget)throws IOException{file=new RandomAccessFile(path.toFile(),"r");this.ranges=ranges;this.budget=budget;file.seek(ranges[0]);remaining=ranges[1];}
        public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        public int read(byte[] b,int off,int length)throws IOException{if(length==0)return 0;budget.check(0);while(remaining==0){if(part==1)return -1;part=1;file.seek(ranges[2]);remaining=ranges[3];}int count=file.read(b,off,(int)Math.min(length,remaining));if(count<0)throw new EOFException();remaining-=count;budget.check(count);return count;}
        public void close()throws IOException{file.close();}
    }
}
