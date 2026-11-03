package cn.bidcheck.verification.source;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.math.BigInteger;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.*;
import org.bouncycastle.util.CollectionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DigitalSignatureInspectorTest {
    @TempDir Path temp;
    @Test void unsignedPdfIsNotMisreportedAsUnsignedVisibleFormOrAuthentic()throws Exception{Path p=temp.resolve("unsigned.pdf");try(PDDocument d=new PDDocument()){d.addPage(new PDPage());d.save(p.toFile());}java.util.Map<java.lang.String, java.lang.Object> r=new DigitalSignatureInspector().inspect(EvidenceLayoutServiceTest.document(p,cn.bidcheck.compat.Java8Api.listOf()),p);assertEquals("NO_SIGNATURE_CONTAINER",r.get("status"));assertEquals(false,r.get("authenticityVerified"));assertFalse(((List<?>)r.get("limitations")).isEmpty());}
    @Test void byteRangePresenceDoesNotClaimCryptoTrust()throws Exception{
        Path p=temp.resolve("dictionary.pdf");try(PDDocument d=new PDDocument()){d.addPage(new PDPage());org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm form=new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(d);d.getDocumentCatalog().setAcroForm(form);org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField field=new org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField(form);form.getFields().add(field);org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature signature=new org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature();signature.setByteRange(new int[]{0,10,20,10});signature.setContents(new byte[]{1,2,3});field.setValue(signature);d.save(p.toFile());}
        java.util.Map<java.lang.String, java.lang.Object> r=new DigitalSignatureInspector().inspect(EvidenceLayoutServiceTest.document(p,cn.bidcheck.compat.Java8Api.listOf()),p);assertEquals("SIGNATURES_INSPECTED",r.get("status"));Map<?,?> s=(Map<?,?>)((List<?>)r.get("signatures")).get(0);assertEquals("NOT_VERIFIED",s.get("cryptographicIntegrity"));assertEquals("NOT_VERIFIED",s.get("trustChain"));assertEquals(false,r.get("authenticityVerified"));
    }
    @Test void validDetachedCmsVerifiesIntegrityWithoutTrustAndIsExposedInLayout()throws Exception{
        Path p=signedPdf();cn.bidcheck.domain.Domain.Document document=EvidenceLayoutServiceTest.document(p,cn.bidcheck.compat.Java8Api.listOf());java.util.Map<java.lang.String, java.lang.Object> r=new DigitalSignatureInspector().inspect(document,p);Map<?,?> s=first(r);
        assertEquals("VALID",s.get("cryptographicIntegrity"),s.toString());assertEquals(true,s.get("coversCurrentRevisionBytes"));assertEquals("NOT_VERIFIED",s.get("trustChain"));assertEquals("NOT_CHECKED",s.get("revocation"));assertEquals(false,r.get("authenticityVerified"));
        Map<?,?> layout=new EvidenceLayoutService().layout(document,p);assertEquals("COMPLETE",layout.get("status"));assertEquals("VALID",first((Map<?,?>)layout.get("digitalSignatures")).get("cryptographicIntegrity"));
    }
    @Test void modifyingSignedBytesFailsCmsEvenWhenSourceHashIsRecomputed()throws Exception{
        Path p=signedPdf();byte[] bytes=Files.readAllBytes(p);assertEquals("%PDF-1.",new String(bytes,0,7,StandardCharsets.US_ASCII));bytes[7]=bytes[7]=='5'?(byte)'6':(byte)'5';Files.write(p,bytes);
        Map<?,?> s=first(new DigitalSignatureInspector().inspect(EvidenceLayoutServiceTest.document(p,cn.bidcheck.compat.Java8Api.listOf()),p));assertEquals("INVALID",s.get("cryptographicIntegrity"),s.toString());
    }
    @Test void appendedRevisionDoesNotPretendCurrentFileIsFullySigned()throws Exception{
        Path p=signedPdf();cn.bidcheck.compat.Java8Api.writeString(p, "\n% appended unsigned bytes\n", StandardOpenOption.APPEND);
        java.util.Map<java.lang.String, java.lang.Object> r=new DigitalSignatureInspector().inspect(EvidenceLayoutServiceTest.document(p,cn.bidcheck.compat.Java8Api.listOf()),p);Map<?,?> s=first(r);assertEquals("VALID",s.get("cryptographicIntegrity"),s.toString());assertEquals(false,s.get("coversCurrentRevisionBytes"));assertEquals(false,r.get("authenticityVerified"));
    }
    private static Map<?,?> first(Map<?,?> result){return (Map<?,?>)((List<?>)result.get("signatures")).get(0);}
    private Path signedPdf()throws Exception{
        org.bouncycastle.jce.provider.BouncyCastleProvider provider=new BouncyCastleProvider();KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair keys=generator.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name name=new X500Name("CN=Synthetic Offline Test");org.bouncycastle.operator.ContentSigner signer=new JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keys.getPrivate());
        org.bouncycastle.cert.X509CertificateHolder cert=new JcaX509v3CertificateBuilder(name,BigInteger.ONE,new Date(0),new Date(4102444800000L),name,keys.getPublic()).build(signer);
        org.bouncycastle.cms.CMSSignedDataGenerator cms=new CMSSignedDataGenerator();cms.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().setProvider(provider).build()).build(signer,cert));cms.addCertificates(new CollectionStore<>(cn.bidcheck.compat.Java8Api.listOf(cert)));
        Path base=temp.resolve("base-"+UUID.randomUUID()+".pdf"),signed=temp.resolve("signed-"+UUID.randomUUID()+".pdf");
        try(PDDocument d=new PDDocument()){d.addPage(new PDPage());d.getDocumentInformation().setTitle("SIGNED-ORIGINAL");d.save(base.toFile());}
        try(PDDocument d=Loader.loadPDF(base.toFile());java.io.OutputStream out=Files.newOutputStream(signed)){
            PDSignature signature=new PDSignature();signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);d.addSignature(signature,input->{try{return cms.generate(new CMSProcessableByteArray(cn.bidcheck.compat.Java8Api.readAllBytes(input)),false).getEncoded();}catch(Exception e){throw new java.io.IOException(e);}});d.saveIncremental(out);
        }return signed;
    }
}
