package uk.gov.ida.saml.security;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import net.shibboleth.utilities.java.support.component.ComponentInitializationException;
import net.shibboleth.utilities.java.support.xml.BasicParserPool;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opensaml.saml.metadata.resolver.impl.BasicRoleDescriptorResolver;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.opensaml.saml.security.impl.MetadataCredentialResolver;
import org.opensaml.security.credential.Credential;
import org.opensaml.xmlsec.config.DefaultSecurityConfigurationBootstrap;
import org.opensaml.xmlsec.keyinfo.KeyInfoCredentialResolver;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.X509Data;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.opensaml.xmlsec.signature.support.impl.ExplicitKeySignatureTrustEngine;
import uk.gov.ida.common.shared.security.verification.CertificateChainValidator;
import uk.gov.ida.common.shared.security.verification.CertificateValidity;
import uk.gov.ida.saml.core.test.TestCertificateStrings;
import uk.gov.ida.saml.core.test.TestEntityIds;
import uk.gov.ida.saml.core.validation.SamlTransformationErrorException;
import uk.gov.ida.saml.security.saml.OpenSAMLMockitoRunner;
import uk.gov.ida.saml.security.saml.StringEncoding;
import uk.gov.ida.saml.security.saml.TestCredentialFactory;
import uk.gov.ida.saml.security.saml.builders.AssertionBuilder;
import uk.gov.ida.saml.security.saml.builders.KeyInfoBuilder;
import uk.gov.ida.saml.security.saml.builders.SignatureBuilder;
import uk.gov.ida.saml.security.saml.builders.X509CertificateBuilder;
import uk.gov.ida.saml.security.saml.builders.X509DataBuilder;
import uk.gov.ida.saml.security.saml.deserializers.AuthnRequestUnmarshaller;
import uk.gov.ida.saml.security.saml.deserializers.SamlObjectParser;
import uk.gov.ida.saml.security.saml.deserializers.StringToOpenSamlObjectTransformer;

import java.io.IOException;
import java.net.URL;
import java.security.cert.CertPathValidatorException;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(OpenSAMLMockitoRunner.class)
public class MetadataBackedSignatureValidatorTest {

    private final String issuerId = TestEntityIds.HUB_ENTITY_ID;

    private KeyInfoCredentialResolver keyInfoResolver = DefaultSecurityConfigurationBootstrap.buildBasicInlineKeyInfoCredentialResolver();

    @Test
    public void shouldValidateSignatureUsingTrustedCredentials() throws Exception {
        MetadataBackedSignatureValidator metadataBackedSignatureValidator = createMetadataBackedSignatureValidator();
        Credential signingCredential = new TestCredentialFactory(TestCertificateStrings.HUB_TEST_PUBLIC_SIGNING_CERT, TestCertificateStrings.HUB_TEST_PRIVATE_SIGNING_KEY).getSigningCredential();
        final Assertion assertion = AssertionBuilder.anAssertion().withSignature(SignatureBuilder.aSignature().withSigningCredential(signingCredential).build()).build();
        assertThat(metadataBackedSignatureValidator.validate(assertion, issuerId, SPSSODescriptor.DEFAULT_ELEMENT_NAME)).isEqualTo(true);
    }

    @Test
    public void shouldFailValidationIfKeyInfoNotPresentInMetadata() throws Exception {
        MetadataBackedSignatureValidator metadataBackedSignatureValidator = createMetadataBackedSignatureValidator();
        Credential signingCredential = new TestCredentialFactory(TestCertificateStrings.TEST_RP_MS_PUBLIC_SIGNING_CERT, TestCertificateStrings.TEST_RP_PRIVATE_SIGNING_KEY).getSigningCredential();
        Signature signature = createSignatureWithKeyInfo(signingCredential, TestCertificateStrings.TEST_RP_MS_PUBLIC_SIGNING_CERT);
        final Assertion assertion = AssertionBuilder.anAssertion().withSignature(signature).build();
        assertThat(metadataBackedSignatureValidator.validate(assertion, issuerId, SPSSODescriptor.DEFAULT_ELEMENT_NAME)).isEqualTo(false);
    }

    @Test
    public void shouldFailValidationIfCertificateDoesNotChainWithATrustedRoot() throws Exception {
        CertificateChainValidator invalidCertificateChainMockValidator = createCertificateChainValidator(CertificateValidity.invalid(new CertPathValidatorException()));
        MetadataBackedSignatureValidator metadataBackedSignatureValidator = createMetadataBackedSignatureValidatorWithChainValidation(invalidCertificateChainMockValidator);
        Credential signingCredential = new TestCredentialFactory(TestCertificateStrings.HUB_TEST_PUBLIC_SIGNING_CERT, TestCertificateStrings.HUB_TEST_PRIVATE_SIGNING_KEY).getSigningCredential();
        final Assertion assertion = AssertionBuilder.anAssertion().withSignature(SignatureBuilder.aSignature().withSigningCredential(signingCredential).build()).build();

        boolean validationResult = metadataBackedSignatureValidator.validate(assertion, issuerId, SPSSODescriptor.DEFAULT_ELEMENT_NAME);

        assertThat(validationResult).as("Assertion was expected to be invalid due to an invalid certificate chain").isEqualTo(false);
    }

    private Signature createSignatureWithKeyInfo(Credential signingCredential, String certificateString) {
        Signature signature = SignatureBuilder.aSignature().withSigningCredential(signingCredential).build();
        org.opensaml.xmlsec.signature.X509Certificate certificate = X509CertificateBuilder.aX509Certificate().withCert(certificateString).build();
        X509Data x509 = X509DataBuilder.aX509Data().withX509Certificate(certificate).build();
        signature.setKeyInfo(KeyInfoBuilder.aKeyInfo().withX509Data(x509).build());
        return signature;
    }

    private MetadataBackedSignatureValidator createMetadataBackedSignatureValidator() throws ComponentInitializationException {
        return MetadataBackedSignatureValidator.withoutCertificateChainValidation(getExplicitKeySignatureTrustEngine());
    }

    private MetadataBackedSignatureValidator createMetadataBackedSignatureValidatorWithChainValidation(CertificateChainValidator certificateChainValidator) throws ComponentInitializationException {
        ExplicitKeySignatureTrustEngine signatureTrustEngine = getExplicitKeySignatureTrustEngine();
        CertificateChainEvaluableCriterion certificateChainEvaluableCriterion = new CertificateChainEvaluableCriterion(certificateChainValidator, null);
        return MetadataBackedSignatureValidator.withCertificateChainValidation(signatureTrustEngine, certificateChainEvaluableCriterion);
    }

    private String loadMetadata(final String fileName) {
        try {
            URL authnRequestUrl = getClass().getClassLoader().getResource(fileName);
            return Resources.toString(authnRequestUrl, Charsets.UTF_8);
        }
        catch(IOException ioException) {
            ioException.printStackTrace(System.out);
        }
        return null;
    }

    private ExplicitKeySignatureTrustEngine getExplicitKeySignatureTrustEngine() throws ComponentInitializationException {
        StringBackedMetadataResolver metadataResolver = new StringBackedMetadataResolver(loadMetadata("metadata.xml"));
        BasicParserPool basicParserPool = new BasicParserPool();
        basicParserPool.initialize();
        metadataResolver.setParserPool(basicParserPool);
        metadataResolver.setRequireValidMetadata(true);
        metadataResolver.setId("arbitrary id");
        metadataResolver.initialize();

        BasicRoleDescriptorResolver basicRoleDescriptorResolver = new BasicRoleDescriptorResolver(metadataResolver);
        basicRoleDescriptorResolver.initialize();

        MetadataCredentialResolver metadataCredentialResolver = new MetadataCredentialResolver();
        metadataCredentialResolver.setRoleDescriptorResolver(basicRoleDescriptorResolver);
        metadataCredentialResolver.setKeyInfoCredentialResolver(DefaultSecurityConfigurationBootstrap.buildBasicInlineKeyInfoCredentialResolver());
        metadataCredentialResolver.initialize();

        return new ExplicitKeySignatureTrustEngine(metadataCredentialResolver, keyInfoResolver);
    }

    private CertificateChainValidator createCertificateChainValidator(CertificateValidity validity) {
        CertificateChainValidator certificateChainValidator = mock(CertificateChainValidator.class);
        when(certificateChainValidator.validate(any(X509Certificate.class), eq(null))).thenReturn(validity);
        return certificateChainValidator;
    }

    /* ******************************************************************************************* *
     * Tests below this point were lifted from SignatureValidatorTest to check that
     * MetadataBackedSignatureValidator has equivalent behaviour.
     * These test cover mostly OpenSAML code.
     * ******************************************************************************************* */

    @Test
    public void shouldAcceptSignedAssertions() throws Exception {
        Credential signingCredential = new TestCredentialFactory(TestCertificateStrings.HUB_TEST_PUBLIC_SIGNING_CERT, TestCertificateStrings.HUB_TEST_PRIVATE_SIGNING_KEY).getSigningCredential();
        final Assertion assertion = AssertionBuilder.anAssertion().withSignature(SignatureBuilder.aSignature().withSigningCredential(signingCredential).build()).build();
        assertThat(createMetadataBackedSignatureValidator().validate(assertion, issuerId, SPSSODescriptor.DEFAULT_ELEMENT_NAME)).isEqualTo(true);
    }

    @Test
    public void shouldNotAcceptUnsignedAssertions() throws Exception {
        assertThat(createMetadataBackedSignatureValidator().validate(AssertionBuilder.anAssertion().withoutSigning().build(), issuerId, SPSSODescriptor.DEFAULT_ELEMENT_NAME)).isEqualTo(false);
    }

    @Test
    public void shouldNotAcceptMissignedAssertions() throws Exception {
        Credential badSigningCredential = new TestCredentialFactory(TestCertificateStrings.UNCHAINED_PUBLIC_CERT, TestCertificateStrings.UNCHAINED_PRIVATE_KEY).getSigningCredential();
        final Assertion assertion = AssertionBuilder.anAssertion().withSignature(SignatureBuilder.aSignature().withSigningCredential(badSigningCredential).build()).build();
        assertThat(createMetadataBackedSignatureValidator().validate(assertion, issuerId, SPSSODescriptor.DEFAULT_ELEMENT_NAME)).isEqualTo(false);
    }

    /*
     * Signature algorithm should be valid.
     */
    @Test(expected = SamlTransformationErrorException.class)
    public void shouldNotValidateBadSignatureAlgorithm() throws Exception {
        validateAuthnRequestFile("authnRequestBadAlgorithm.xml");
    }

    /*
     * Signature object should exist.
     */
    @Test(expected = SignatureException.class)
    public void shouldNotValidateMissingSignature() throws Exception {
        validateAuthnRequestFile("authnRequestNoSignature.xml");
    }

    /*
     * Signature must be an immediate child of the SAML object.
     */
    @Test(expected = SignatureException.class)
    public void shouldNotValidateSignatureNotImmediateChild() throws Exception {
        validateAuthnRequestFile("authnRequestNotImmediateChild.xml");
    }

    /*
     * Signature should not contain more than one Reference.
     */
    @Test(expected = SignatureException.class)
    public void shouldNotValidateSignatureTooManyReferences() throws Exception {
        validateAuthnRequestFile("authnRequestTooManyRefs.xml");
    }

    /*
     * Reference requires a valid URI pointing to a fragment ID.
     */
    @Test(expected = SignatureException.class)
    public void shouldNotValidateSignatureBadReferenceURI() throws Exception {
        validateAuthnRequestFile("authnRequestBadRefURI.xml");
    }

    /*
     * Reference URI should point to parent SAML object.
     */
    @Test(expected = SignatureException.class)
    public void shouldNotValidateSignatureReferenceURINotParentID() throws Exception {
        validateAuthnRequestFile("authnRequestRefURINotParentID.xml");
    }

    /*
     * Root SAML object should have an ID.
     */
    @Test(expected = SignatureException.class)
    public void shouldNotValidateSignatureNoParentID() throws Exception {
        validateAuthnRequestFile("authnRequestNoParentID.xml");
    }

    /*
     * Signature must have Transforms defined.
     */
    @Test(expected = SignatureException.class)
    public void shouldNotValidateSignatureNoTransforms() throws Exception {
        validateAuthnRequestFile("authnRequestNoTransforms.xml");
    }

    /*
     * Signature should not have more than two Transforms.
     */
    @Test(expected = SignatureException.class)
    public void shouldNotValidateSignatureTooManyTransforms() throws Exception {
        validateAuthnRequestFile("authnRequestTooManyTransforms.xml");
    }

    /*
     * Signature must have enveloped-signature Transform.
     */
    @Test(expected = SignatureException.class)
    public void shouldNotValidateSignatureNoEnvelopeTransform() throws Exception {
        validateAuthnRequestFile("authnRequestNoEnvTransform.xml");
    }

    /*
     * Signature must have a valid enveloped-signature Transform.
     */
    @Test(expected = SignatureException.class)
    public void shouldNotValidateSignatureInvalidEnvelopeTransform() throws Exception {
        validateAuthnRequestFile("authnRequestInvalidEnvTransform.xml");
    }

    /*
     * Signature should not contain any Object children.
     */
    @Test(expected = SignatureException.class)
    public void shouldNotValidateSignatureContainingObject() throws Exception {
        validateAuthnRequestFile("authnRequestSigContainsChildren.xml");
    }

    private void validateAuthnRequestFile(String fileName) throws Exception {
        URL authnRequestUrl = getClass().getClassLoader().getResource(fileName);
        String input = StringEncoding.toBase64Encoded(Resources.toString(authnRequestUrl, Charsets.UTF_8));
        AuthnRequest request = getStringtoOpenSamlObjectTransformer().apply(input);
        createMetadataBackedSignatureValidator().validate(request, issuerId, SPSSODescriptor.DEFAULT_ELEMENT_NAME);
    }

    private StringToOpenSamlObjectTransformer getStringtoOpenSamlObjectTransformer() {
        return new StringToOpenSamlObjectTransformer(new AuthnRequestUnmarshaller(new SamlObjectParser()));
    }
}
