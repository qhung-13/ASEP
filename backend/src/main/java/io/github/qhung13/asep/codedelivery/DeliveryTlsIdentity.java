package io.github.qhung13.asep.codedelivery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Cryptographic construction only. Protected bootstrapping must separately prove
 * file ownership, process isolation, installed artifact and trust-bundle enrollment.
 * No key generation, permissive trust manager or credential discovery occurs here.
 */
public final class DeliveryTlsIdentity {
    private DeliveryTlsIdentity(){}
    public static SSLContext context(byte[] certificatePem,byte[] privateKeyPem,byte[] trustPem,String expectedIdentity){
        byte[] encoded=null;char[] password=UUID.randomUUID().toString().toCharArray();
        try{
            if(certificatePem.length>65536||privateKeyPem.length>16384||trustPem.length>65536)throw new IllegalStateException();
            var parser=CertificateFactory.getInstance("X.509");var chain=parser.generateCertificates(new ByteArrayInputStream(certificatePem));
            if(chain.isEmpty()||chain.size()>8)throw new IllegalStateException();var leaf=(X509Certificate)chain.iterator().next();leaf.checkValidity();
            var names=leaf.getSubjectAlternativeNames();if(names==null)throw new IllegalStateException();
            var uris=names.stream().filter(name->Integer.valueOf(6).equals(name.getFirst())).toList();
            if(uris.size()!=1||!expectedIdentity.equals(uris.getFirst().get(1)))throw new IllegalStateException();
            String text=new String(privateKeyPem,StandardCharsets.US_ASCII).strip();
            if(!text.startsWith("-----BEGIN PRIVATE KEY-----")||!text.endsWith("-----END PRIVATE KEY-----"))throw new IllegalStateException();
            encoded=Base64.getMimeDecoder().decode(text.substring(27,text.length()-25));PrivateKey key=null;
            for(String algorithm:new String[]{"EC","RSA","Ed25519"}){try{key=KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(encoded));break;}catch(java.security.GeneralSecurityException unsupported){}}
            if(key==null)throw new IllegalStateException();
            String algorithm=switch(key.getAlgorithm()){case "EC"->"SHA256withECDSA";case "RSA"->"SHA256withRSA";default->"Ed25519";};
            byte[] challenge="asep-service-key-match-v1".getBytes(StandardCharsets.US_ASCII);var signer=Signature.getInstance(algorithm);signer.initSign(key);signer.update(challenge);byte[] signed=signer.sign();signer.initVerify(leaf.getPublicKey());signer.update(challenge);if(!signer.verify(signed))throw new IllegalStateException();
            var identity=KeyStore.getInstance("PKCS12");identity.load(null,null);identity.setKeyEntry("service",key,password,chain.toArray(Certificate[]::new));
            var trusted=KeyStore.getInstance("PKCS12");trusted.load(null,null);var roots=parser.generateCertificates(new ByteArrayInputStream(trustPem));if(roots.isEmpty()||roots.size()>8)throw new IllegalStateException();
            int n=0;for(var root:roots)trusted.setCertificateEntry("root-"+n++,root);
            var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(identity,password);
            var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(trusted);
            var context=SSLContext.getInstance("TLSv1.3");context.init(keys.getKeyManagers(),trust.getTrustManagers(),null);return context;
        }catch(Exception invalid){throw new IllegalStateException("Protected service identity unavailable");}
        finally{Arrays.fill(password,'\0');if(encoded!=null)Arrays.fill(encoded,(byte)0);}
    }
}
