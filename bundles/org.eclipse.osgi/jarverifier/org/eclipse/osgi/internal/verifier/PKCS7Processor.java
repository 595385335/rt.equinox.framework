/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.internal.verifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.util.*;
import javax.security.auth.x500.X500Principal;
import org.eclipse.osgi.framework.log.FrameworkLogEntry;
import org.eclipse.osgi.internal.provisional.verifier.CertificateChain;
import org.eclipse.osgi.internal.provisional.verifier.CertificateTrustAuthority;
import org.eclipse.osgi.util.NLS;

/**
 * This class processes a PKCS7 file. See RFC 2315 for specifics.
 */
public class PKCS7Processor implements CertificateChain, JarVerifierConstant {

	private static CertificateFactory certFact;

	static {
		try {
			certFact = CertificateFactory.getInstance("X.509"); //$NON-NLS-1$
		} catch (CertificateException e) {
			SignedBundleHook.log(e.getMessage(), FrameworkLogEntry.ERROR, e);
		}
	}

	private String certChain;
	private Certificate[] certificates;
	private boolean trusted;

	// key(object id) = value(structure)
	private Map signedAttrs;

	//	key(object id) = value(structure)
	private Map unsignedAttrs;

	// store the signature of a signerinfo
	private byte signature[];
	private String digestAlgorithm;
	private String signatureAlgorithm;

	private Certificate signerCert;
	private Date sigingTime;

	String oid2String(int oid[]) {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < oid.length; i++) {
			if (i > 0)
				sb.append('.');
			sb.append(oid[i]);
		}
		return sb.toString();
	}

	String findEncryption(int encOid[]) throws NoSuchAlgorithmException {
		if (Arrays.equals(DSA_OID, encOid)) {
			return "DSA"; //$NON-NLS-1$
		}
		if (Arrays.equals(RSA_OID, encOid)) {
			return "RSA"; //$NON-NLS-1$
		}
		throw new NoSuchAlgorithmException("No algorithm found for " + oid2String(encOid)); //$NON-NLS-1$
	}

	String findDigest(int digestOid[]) throws NoSuchAlgorithmException {
		if (Arrays.equals(SHA1_OID, digestOid)) {
			return SHA1_STR;
		}
		if (Arrays.equals(MD5_OID, digestOid)) {
			return MD5_STR;
		}
		if (Arrays.equals(MD2_OID, digestOid)) {
			return MD2_STR;
		}
		throw new NoSuchAlgorithmException("No algorithm found for " + oid2String(digestOid)); //$NON-NLS-1$
	}

	/*
	 * static void printBP(BERProcessor bp, int depth) {
	 * System.out.print(depth); for(int i = 0; i < depth; i++)
	 * System.out.print(" "); System.out.println(bp); }
	 * 
	 * static void dumpSeq(BERProcessor bp, int depth) {
	 * while(!bp.endOfSequence()) { printBP(bp, depth); if (bp.constructed) {
	 * dumpSeq(bp.stepInto(), depth+1); } bp.stepOver(); } }
	 * 
	 * void hexDump(byte buffer[], int off, int len) { for(int i = 0; i < len;
	 * i++) { System.out.print(Integer.toString(buffer[i]&0xff, 16) + " "); if
	 * (i % 16 == 15) System.out.println(); } System.out.println(); }
	 */

	public PKCS7Processor(String certChain, boolean trusted, byte[][] certificates) throws CertificateException {
		this.certChain = certChain;
		this.trusted = trusted;
		this.certificates = new Certificate[certificates.length];
		for (int i = 0; i < certificates.length; i++)
			this.certificates[i] = certFact.generateCertificate(new ByteArrayInputStream(certificates[i]));
	}

	public PKCS7Processor(byte pkcs7[], int pkcs7Offset, int pkcs7Length) throws IOException, CertificateException, NoSuchAlgorithmException {

		// First grab the certificates
		List certs = null;

		BERProcessor bp = new BERProcessor(pkcs7, pkcs7Offset, pkcs7Length);

		// Just do a sanity check and make sure we are actually doing a PKCS7
		// stream
		// PKCS7: Step into the ContentType
		bp = bp.stepInto();
		if (!Arrays.equals(bp.getObjId(), SIGNEDDATA_OID)) {
			throw new IOException("Not a valid PKCS#7 file"); //$NON-NLS-1$
		}

		// PKCS7: Process the SignedData structure
		bp.stepOver(); // (**wrong comments**) skip over the oid
		bp = bp.stepInto(); // go into the Signed data
		bp = bp.stepInto(); // It is a structure;
		bp.stepOver(); // Yeah, yeah version = 1
		bp.stepOver(); // We'll see the digest stuff again; digestAlgorithms
		bp.stepOver(); // We'll see the contentInfo in signerinfo

		// PKCS7: check if the class tag is 0
		if (bp.classOfTag == BERProcessor.CONTEXTSPECIFIC_TAGCLASS && bp.tag == 0) {
			// process the certificate elements inside the signeddata strcuture
			certs = processCertificates(bp);
		}

		if (certs == null || certs.size() < 1)
			throw new SecurityException("There are no certificates in the .RSA/.DSA file!");

		// Okay, here are our certificates.
		bp.stepOver();
		if (bp.classOfTag == BERProcessor.UNIVERSAL_TAGCLASS && bp.tag == 1) {
			bp.stepOver(); // Don't use the CRLs if present
		}

		processSignerInfos(bp, certs);

		// set the cert chain variable
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < certs.size(); i++) {
			X509Certificate x509Cert = ((X509Certificate) certs.get(i));
			sb.append(x509Cert.getSubjectDN().getName());
			sb.append("; ");
		}
		certChain = sb.toString();

		// initialize the certificates
		certificates = (Certificate[]) certs.toArray(new Certificate[certs.size()]);

		// determine the signing if there is
		sigingTime = PKCS7DateParser.parseDate(this);
	}

	public void validateCerts() throws CertificateExpiredException, CertificateNotYetValidException, InvalidKeyException, SignatureException {
		if (certificates == null) {
			throw new SecurityException("There are no certificates in the signature block file!");
		}

		int len = certificates.length;

		if (len == 1) {
			X509Certificate currentX509Cert = (X509Certificate) certificates[0];
			if (sigingTime == null)
				currentX509Cert.checkValidity();
			else
				currentX509Cert.checkValidity(sigingTime);
		} else {

			// there are more than one certs
			for (int i = 0; i < len - 1; i++) {
				X509Certificate currentX509Cert = (X509Certificate) certificates[i];
				// check if the cert is still valid
				if (sigingTime != null)
					currentX509Cert.checkValidity(sigingTime);
				else
					currentX509Cert.checkValidity();

				X509Certificate nextX509Cert = (X509Certificate) certificates[i + 1];
				// verify the current cert is signed by the private key that corresponds to the public key in the next cert
				try {
					currentX509Cert.verify(nextX509Cert.getPublicKey());
				} catch (NoSuchAlgorithmException e) {
					SignedBundleHook.log(e.getMessage(), FrameworkLogEntry.ERROR, e);
					throw new SecurityException(NLS.bind(JarVerifierMessages.No_Such_Algorithm_Excep, new String[] {e.getMessage()}));
				} catch (NoSuchProviderException e) {
					SignedBundleHook.log(e.getMessage(), FrameworkLogEntry.ERROR, e);
					throw new SecurityException(NLS.bind(JarVerifierMessages.No_Such_Provider_Excep, new String[] {e.getMessage()}));
				} catch (CertificateException e) {
					SignedBundleHook.log(e.getMessage(), FrameworkLogEntry.ERROR, e);
					throw new SecurityException(NLS.bind(JarVerifierMessages.Validate_Certs_Certificate_Exception, new String[] {e.getMessage()}));
				}
			}
		}
	}

	private Certificate processSignerInfos(BERProcessor bp, List certs) throws CertificateException, NoSuchAlgorithmException {
		// We assume there is only one SingerInfo element 

		// PKCS7: SignerINFOS processing
		bp = bp.stepInto(); // Step into the set of signerinfos
		bp = bp.stepInto(); // Step into the signerinfo sequence

		// make sure the version is 1
		BigInteger signerInfoVersion = bp.getIntValue();
		if (signerInfoVersion.intValue() != 1) {
			throw new CertificateException(JarVerifierMessages.PKCS7_SignerInfo_Version_Not_Supported);
		}

		// PKCS7: version CMSVersion 
		bp.stepOver(); // Skip the version

		// PKCS7: sid [SignerIdentifier : issuerAndSerialNumber or subjectKeyIdentifer]
		BERProcessor issuerAndSN = bp.stepInto();
		X500Principal signerIssuer = new X500Principal(new ByteArrayInputStream(issuerAndSN.buffer, issuerAndSN.offset, issuerAndSN.endOffset - issuerAndSN.offset));
		issuerAndSN.stepOver();
		BigInteger sn = issuerAndSN.getIntValue();

		// initilize the newSignerCert to the issuer cert of leaf cert
		Certificate newSignerCert = null;

		Iterator itr = certs.iterator();
		// PKCS7: compuare the issuers in the issuerAndSN BER equals to the issuers in Certs generated at the beginning of this method
		// it seems like there is no neeed, cause both ways use the same set of bytes
		while (itr.hasNext()) {
			X509Certificate cert = (X509Certificate) itr.next();
			if (cert.getIssuerX500Principal().equals(signerIssuer) && cert.getSerialNumber().equals(sn)) {
				newSignerCert = cert;
				break;
			}
		}
		if (newSignerCert == null)
			throw new CertificateException("Signer certificate not in pkcs7block"); //$NON-NLS-1$

		// set the signer cert
		signerCert = newSignerCert;

		// PKCS7: skip over the sid [SignerIdentifier : issuerAndSerialNumber or subjectKeyIdentifer]
		bp.stepOver(); // skip the issuer name and serial number

		// PKCS7: digestAlgorithm DigestAlgorithmIdentifier
		BERProcessor digestAlg = bp.stepInto();
		digestAlgorithm = findDigest(digestAlg.getObjId());

		// PKCS7: check if the next one if context class for signedAttrs
		bp.stepOver(); // skip the digest alg

		// process the signed attributes if there is any
		processSignedAttributes(bp);

		// PKCS7: signatureAlgorithm for this SignerInfo
		BERProcessor encryptionAlg = bp.stepInto();
		signatureAlgorithm = findEncryption(encryptionAlg.getObjId());
		bp.stepOver(); // skip the encryption alg

		// PKCS7: signature
		signature = bp.getBytes();

		// PKCS7: Step into the unsignedAttrs, 
		bp.stepOver();

		// process the unsigned attributes if there is any
		processUnsignedAttributes(bp);

		return newSignerCert;
	}

	private void processUnsignedAttributes(BERProcessor bp) {

		if (bp.classOfTag == BERProcessor.CONTEXTSPECIFIC_TAGCLASS && bp.tag == 1) {

			// there are some unsignedAttrs are found!!
			unsignedAttrs = new HashMap();

			// step into a set of unsigned attributes, I believe, when steps 
			// into here, the 'poiter' is pointing to the first element
			BERProcessor unsignedAttrsBERS = bp.stepInto();
			do {
				// process the unsignedAttrsBER by getting the attr type first,
				// then the strcuture for the type
				BERProcessor unsignedAttrBER = unsignedAttrsBERS.stepInto();

				// check if it is timestamp attribute type
				int objID[] = unsignedAttrBER.getObjId();
				// if(Arrays.equals(TIMESTAMP_OID, objID)) {
				// System.out.println("This is a timestamp type, to continue");
				// }

				// get the structure for the attribute type
				unsignedAttrBER.stepOver();
				byte structure[] = unsignedAttrBER.getBytes();
				unsignedAttrs.put(objID, structure);
				unsignedAttrsBERS.stepOver();
			} while (!unsignedAttrsBERS.endOfSequence());
		}
	}

	private void processSignedAttributes(BERProcessor bp) {
		if (bp.classOfTag == BERProcessor.CONTEXTSPECIFIC_TAGCLASS) {

			// process the signed attributes
			signedAttrs = new HashMap();

			BERProcessor signedAttrsBERS = bp.stepInto();
			do {
				BERProcessor signedAttrBER = signedAttrsBERS.stepInto();
				int[] signedAttrObjID = signedAttrBER.getObjId();

				// step over to the attribute value
				signedAttrBER.stepOver();

				byte[] signedAttrStructure = signedAttrBER.getBytes();

				signedAttrs.put(signedAttrObjID, signedAttrStructure);

				signedAttrsBERS.stepOver();
			} while (!signedAttrsBERS.endOfSequence());
			bp.stepOver();
		}
	}

	/**
	 * Returns the Certificate of the signer of this PKCS7Block
	 */
	public Certificate getSigner() {
		if (certificates == null || certificates.length == 0)
			return null;
		return certificates[0];
	}

	public Certificate getRoot() {
		if (certificates == null || certificates.length == 0)
			return null;
		return certificates[certificates.length - 1];
	}

	public Certificate[] getCertificates() {
		return certificates;
	}

	/**
	 * Returns the list of X500 distinguished names that make up the signature chain. Each
	 * distinguished name is separated by a ';'.
	 */
	public String getChain() {
		return certChain;
	}

	/**
	 * Returns true if the signer certificate is trusted
	 * @return true if the signer certificate is trusted
	 */
	public boolean isTrusted() {
		return trusted;
	}

	public boolean equals(Object obj) {
		if (!(obj instanceof CertificateChain))
			return false;
		if (certificates == null)
			return false;
		CertificateChain chain = (CertificateChain) obj;
		if (trusted != chain.isTrusted() || (certChain == null ? chain.getChain() != null : !certChain.equals(chain.getChain())))
			return false;
		Certificate[] otherCerts = chain.getCertificates();
		if (otherCerts == null || certificates.length != otherCerts.length)
			return false;
		for (int i = 0; i < certificates.length; i++)
			if (!certificates[i].equals(otherCerts[i]))
				return false;
		return true;
	}

	public void verifySFSignature(byte data[], int dataOffset, int dataLength) throws InvalidKeyException, NoSuchAlgorithmException, SignatureException {
		Signature sig = Signature.getInstance(digestAlgorithm + "with" + signatureAlgorithm); //$NON-NLS-1$
		sig.initVerify(signerCert.getPublicKey());
		sig.update(data, dataOffset, dataLength);
		if (!sig.verify(signature)) {
			throw new SignatureException(JarVerifierMessages.Signature_Not_Verify);
		}
	}

	/**
	 * Return a map of signed attributes, the key(objid) = value(PKCSBlock in bytes for the key)
	 * 
	 * @return  map if there is any signed attributes, null otherwise
	 */
	public Map getUnsignedAttrs() {
		return unsignedAttrs;
	}

	/**
	 * Return a map of signed attributes, the key(objid) = value(PKCSBlock in bytes for the key)
	 * 
	 * @return  map if there is any signed attributes, null otherwise
	 */
	public Map getSignedAttrs() {
		return signedAttrs;
	}

	/**
	 * 
	 * @param bp
	 * @return		a List of certificates from target cert to root cert in order
	 * 
	 * @throws CertificateException
	 */
	private List processCertificates(BERProcessor bp) throws CertificateException {
		List rtvList = new ArrayList(3);

		// Step into the first certificate-element
		BERProcessor certsBERS = bp.stepInto();

		do {
			X509Certificate x509Cert = (X509Certificate) certFact.generateCertificate(new ByteArrayInputStream(certsBERS.buffer, certsBERS.offset, certsBERS.endOffset - certsBERS.offset));

			if (x509Cert != null) {
				rtvList.add(x509Cert);
			}

			// go to the next cert element
			certsBERS.stepOver();
		} while (!certsBERS.endOfSequence());

		Collections.reverse(rtvList);
		return rtvList;
	}

	void determineTrust(CertificateTrustAuthority certsTrust) {
		try {
			certsTrust.checkTrust(certificates);
			trusted = true;
		} catch (CertificateException e) {
			trusted = false;
		}
	}

	public Date getSigningTime() {
		return sigingTime;
	}

	/*
	 public static void main(String[] args) throws InvalidKeyException, CertificateException, NoSuchAlgorithmException, SignatureException, KeyStoreException, IOException {
	 byte buffer[] = new byte[65536];
	 int len = System.in.read(buffer);
	 byte manifestBuff[] = new byte[65536];
	 int rc = new FileInputStream("man").read(manifestBuff);
	 PKCS7Processor p7 = new PKCS7Processor(buffer, 0, len, manifestBuff, 0, rc);
	 System.out.println(p7.getSignerCertificate());
	 System.out.println(p7.getCertificateChain());
	 }
	 */
}