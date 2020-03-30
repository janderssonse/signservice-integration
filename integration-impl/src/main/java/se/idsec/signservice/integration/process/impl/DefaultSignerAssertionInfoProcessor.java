/*
 * Copyright 2019-2020 IDsec Solutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.idsec.signservice.integration.process.impl;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.opensaml.core.xml.io.UnmarshallingException;
import org.opensaml.saml.saml2.core.Assertion;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;
import net.shibboleth.utilities.java.support.xml.XMLParserException;
import se.idsec.signservice.integration.SignResponseProcessingParameters;
import se.idsec.signservice.integration.authentication.SignerAssertionInformation;
import se.idsec.signservice.integration.authentication.SignerAssertionInformation.SignerAssertionInformationBuilder;
import se.idsec.signservice.integration.authentication.SignerIdentityAttributeValue;
import se.idsec.signservice.integration.core.error.ErrorCode;
import se.idsec.signservice.integration.core.error.SignServiceIntegrationException;
import se.idsec.signservice.integration.core.error.impl.SignServiceProtocolException;
import se.idsec.signservice.integration.core.impl.CorrelationID;
import se.idsec.signservice.integration.dss.DssUtils;
import se.idsec.signservice.integration.dss.SignRequestWrapper;
import se.idsec.signservice.integration.dss.SignResponseWrapper;
import se.idsec.signservice.integration.process.SignResponseProcessingConfig;
import se.idsec.signservice.integration.state.SignatureSessionState;
import se.litsec.opensaml.utils.ObjectUtils;
import se.litsec.swedisheid.opensaml.saml2.attribute.AttributeConstants;
import se.swedenconnect.schemas.csig.dssext_1_1.ContextInfo;
import se.swedenconnect.schemas.csig.dssext_1_1.MappedAttributeType;
import se.swedenconnect.schemas.csig.dssext_1_1.PreferredSAMLAttributeNameType;
import se.swedenconnect.schemas.csig.dssext_1_1.RequestedCertAttributes;
import se.swedenconnect.schemas.csig.dssext_1_1.SignerAssertionInfo;

/**
 * Default implementation of the {@link SignerAssertionInfoProcessor} interface.
 * 
 * @author Martin Lindström (martin@idsec.se)
 * @author Stefan Santesson (stefan@idsec.se)
 */
@Slf4j
public class DefaultSignerAssertionInfoProcessor implements SignerAssertionInfoProcessor, InitializingBean {

  /** Processing config. */
  protected SignResponseProcessingConfig processingConfig;

  /** {@inheritDoc} */
  @Override
  public SignerAssertionInformation processSignerAssertionInfo(final SignResponseWrapper signResponse, final SignatureSessionState state,
      final SignResponseProcessingParameters parameters) throws SignServiceIntegrationException {

    final SignerAssertionInfo signerAssertionInfo = signResponse.getSignResponseExtension().getSignerAssertionInfo();
    if (signerAssertionInfo == null) {
      final String msg =
          String.format("No SignerAssertionInfo available in SignResponse [request-id='%s']", state.getSignRequest().getRequestID());
      log.error("{}: {}", CorrelationID.id(), msg);
      throw new SignServiceProtocolException(msg);
    }

    final SignerAssertionInformationBuilder builder = SignerAssertionInformation.builder();
    final SignRequestWrapper signRequest = state.getSignRequest();

    // Attributes
    // Validate that we got all required attributes ...
    //
    List<SignerIdentityAttributeValue> attributes = this.processAttributes(signerAssertionInfo, signRequest);
    builder.signerAttributes(attributes);

    // Get ContextInfo values ...
    //
    final ContextInfo contextInfo = signerAssertionInfo.getContextInfo();
    if (contextInfo == null) {
      final String msg =
          String.format("No SignerAssertionInfo/ContextInfo available in SignResponse [request-id='%s']", signRequest.getRequestID());
      log.error("{}: {}", CorrelationID.id(), msg);
      throw new SignServiceProtocolException(msg);
    }

    // IdentityProvider
    //
    if (contextInfo.getIdentityProvider() == null || !StringUtils.hasText(contextInfo.getIdentityProvider().getValue())) {
      final String msg = String.format("No SignerAssertionInfo/ContextInfo/IdentityProvider available in SignResponse [request-id='%s']",
        signRequest.getRequestID());
      log.error("{}: {}", CorrelationID.id(), msg);
      throw new SignServiceProtocolException(msg);
    }
    if (!contextInfo.getIdentityProvider().getValue().equals(signRequest.getSignRequestExtension().getIdentityProvider().getValue())) {
      final String msg =
          String.format("IdentityProvider in SignResponse (%s) does not match provider given in SignRequest (%s) [request-id='%s']",
            contextInfo.getIdentityProvider().getValue(), signRequest.getSignRequestExtension().getIdentityProvider().getValue(),
            signRequest.getRequestID());
      log.error("{}: {}", CorrelationID.id(), msg);
      throw new SignResponseProcessingException(new ErrorCode.Code("invalid-response"), msg);
    }
    builder.authnServiceID(contextInfo.getIdentityProvider().getValue());

    // AuthenticationInstant
    //
    // This validation is essential. We want to ensure that the authentication instant is not too old. It must not be
    // before we actually sent our request. Furthermore, it must not be after the sign response was sent. In both cases
    // we take the allowed clock skew in account.
    //
    builder.authnInstant(
      this.processAuthenticationInstant(contextInfo, signRequest, signResponse));

    // AuthnContextClassRef
    //
    final String authnContextClassRef = contextInfo.getAuthnContextClassRef();
    if (!StringUtils.hasText(authnContextClassRef)) {
      final String msg = String.format(
        "No SignerAssertionInfo/ContextInfo/AuthnContextClassRef available in SignResponse [request-id='%s']", signRequest.getRequestID());
      log.error("{}: {}", CorrelationID.id(), msg);
      throw new SignServiceProtocolException(msg);
    }

    // Get hold of the LoA from the request.
    final String requestedAuthnContextClassRef = signRequest.getSignRequestExtension().getCertRequestProperties().getAuthnContextClassRef();

    // Did we require a sign message to be displayed?
    final boolean requireDisplaySignMessageProof = this.requireDisplaySignMessageProof(state);

    if (authnContextClassRef.equals(requestedAuthnContextClassRef)) {
      // If display of SignMessage was required, we need the signMessageDigest attribute to be released.
      if (requireDisplaySignMessageProof) {
        final String signMessageDigest = attributes.stream()
          .filter(a -> AttributeConstants.ATTRIBUTE_NAME_SIGNMESSAGE_DIGEST.equals(a.getName()))
          .map(a -> a.getValue())
          .findFirst()
          .orElse(null);

        if (!StringUtils.hasText(signMessageDigest)) {
          final String msg = String.format(
            "Missing proof for displayed sign message (no signMessageDigest and no sigmessage authnContext) [request-id='%s']",
            signRequest.getRequestID());
          log.error("{}: {}", CorrelationID.id(), msg);
          throw new SignResponseProcessingException(new ErrorCode.Code("invalid-authncontext"), msg);
        }
        else if (this.processingConfig.isStrictProcessing()) {
          // Compare hash with our own hash of the sent SignMessage.
          // TODO
        }
      }
    }
    else {
      // If we don't allow sigmessage URI:s, we need a match ...
      if (!this.processingConfig.isAllowSigMessageUris()) {
        final String msg = String.format("Unexpected authnContextRef received - %s. %s was expected [request-id='%s']",
          authnContextClassRef, requestedAuthnContextClassRef, signRequest.getRequestID());
        log.error("{}: {}", CorrelationID.id(), msg);
        throw new SignResponseProcessingException(new ErrorCode.Code("invalid-authncontext"), msg);
      }
      // OK, we allow sigmessage URI:s. Check if we find a valid mapping.
      final String expectedUri = this.processingConfig.getSigMessageUriMap().get(requestedAuthnContextClassRef);
      if (expectedUri == null) {
        final String msg = String.format("Unrecognized authnContextRef received %s [request-id='%s']",
          authnContextClassRef, signRequest.getRequestID());
        log.error("{}: {}", CorrelationID.id(), msg);
        throw new SignResponseProcessingException(new ErrorCode.Code("invalid-authncontext"), msg);
      }
      else if (!authnContextClassRef.equals(expectedUri)) {
        final String msg = String.format("Unexpected authnContextRef received - %s. %s was expected [request-id='%s']",
          authnContextClassRef, expectedUri, signRequest.getRequestID());
        log.error("{}: {}", CorrelationID.id(), msg);
        throw new SignResponseProcessingException(new ErrorCode.Code("invalid-authncontext"), msg);
      }
      // OK, the mapping is OK. Finally, check that we actually sent a SignMessage.
      if (state.getSignMessage() == null) {
        final String msg = String.format(
          "Invalid authnContextRef received - %s. No SignMessage was sent, so returning a sigmessage URI is illegal [request-id='%s']",
          authnContextClassRef, signRequest.getRequestID());
        log.error("{}: {}", CorrelationID.id(), msg);
        throw new SignResponseProcessingException(new ErrorCode.Code("invalid-authncontext"), msg);
      }
    }
    builder.authnContextRef(authnContextClassRef);

    // AuthType
    //
    builder.authnType(contextInfo.getAuthType());

    // AssertionRef
    //
    final String assertionRef = contextInfo.getAssertionRef();
    if (!StringUtils.hasText(assertionRef)) {
      final String msg = String.format(
        "No SignerAssertionInfo/ContextInfo/AssertionRef available in SignResponse [request-id='%s']", signRequest.getRequestID());
      log.error("{}: {}", CorrelationID.id(), msg);
      throw new SignServiceProtocolException(msg);
    }
    builder.assertionReference(assertionRef);

    // Assertions ...
    //
    if (!signerAssertionInfo.isSetSamlAssertions() || !signerAssertionInfo.getSamlAssertions().isSetAssertions()) {
      if (this.processingConfig.isRequireAssertion()) {
        final String msg =
            String.format("No SignerAssertionInfo/SamlAssertions present in SignResponse. Configuration requires this [request-id='%s']",
              signRequest.getRequestID());
        log.error("{}: {}", CorrelationID.id(), msg);
        throw new SignResponseProcessingException(new ErrorCode.Code("invalid-response"), msg);
      }
    }
    else {
      if (signerAssertionInfo.getSamlAssertions().getAssertions().size() == 1 && !this.processingConfig.isStrictProcessing()) {
        // If strict processing is turned off and we only got one assertion we trust that the SignService
        // included the assertion that corresponds to AssertionRef.
        //
        builder.assertion(new String(signerAssertionInfo.getSamlAssertions().getAssertions().get(0)));
      }
      // Find the assertion matching the AssertionRef ...
      else {
        String userAssertion = null;
        for (byte[] a : signerAssertionInfo.getSamlAssertions().getAssertions()) {
          try {
            final Assertion assertion = ObjectUtils.unmarshall(new ByteArrayInputStream(a), Assertion.class);
            if (assertionRef.equals(assertion.getID())) {
              userAssertion = new String(a);
              break;
            }
            else {
              log.info("{}: Processing assertion with ID '%s' - no match with AssertionRef [request-id='{}']",
                assertion.getID(), signRequest.getRequestID());
            }
          }
          catch (XMLParserException | UnmarshallingException e) {
            final String msg =
                String.format("Invalid SAML assertion found in SignerAssertionInfo/SamlAssertions. %s [request-id='%s']",
                  e.getMessage(), signRequest.getRequestID());
            log.error("{}: {}", CorrelationID.id(), msg);
            throw new SignResponseProcessingException(new ErrorCode.Code("invalid-response"), msg);
          }
        }
        if (userAssertion != null) {
          builder.assertion(userAssertion);
        }
        else {
          final String msg =
              String.format("No SAML assertion matching AssertionRef found in SignerAssertionInfo/SamlAssertions [request-id='%s']",
                signRequest.getRequestID());
          log.error("{}: {}", CorrelationID.id(), msg);
          throw new SignResponseProcessingException(new ErrorCode.Code("invalid-response"), msg);
        }
      }
    }

    return builder.build();
  }

  /**
   * Tells if the display of a sign message with a MustShow flag set was requested.
   * 
   * @param state
   *          the state
   * @return if sign message display is required true is returned, otherwise false
   */
  protected boolean requireDisplaySignMessageProof(final SignatureSessionState state) {
    if (state.getSignMessage() != null && state.getSignMessage().getMustShow() != null) {
      return state.getSignMessage().getMustShow().booleanValue();
    }
    return false;
  }

  /**
   * Extracts the attributes from the response and validates that we received all attributes that were requested (if
   * strict processing is enabled).
   * 
   * @param signerAssertionInfo
   *          the signer info (including the received attributes)
   * @param signRequest
   *          the request
   * @throws SignServiceIntegrationException
   *           for validation errors
   */
  protected List<SignerIdentityAttributeValue> processAttributes(final SignerAssertionInfo signerAssertionInfo,
      final SignRequestWrapper signRequest) throws SignServiceIntegrationException {

    if (signerAssertionInfo.getAttributeStatement() == null
        || signerAssertionInfo.getAttributeStatement().getAttributesAndEncryptedAttributes().isEmpty()) {
      final String msg = String.format("No SignerAssertionInfo/AttributeStatement available in SignResponse [request-id='%s']",
        signRequest.getRequestID());
      log.error("{}: {}", CorrelationID.id(), msg);
      throw new SignServiceProtocolException(msg);
    }
    final List<SignerIdentityAttributeValue> attributes = DssUtils.fromAttributeStatement(signerAssertionInfo.getAttributeStatement());

    if (this.processingConfig.isStrictProcessing()) {
      final RequestedCertAttributes requestedAttributes =
          signRequest.getSignRequestExtension().getCertRequestProperties().getRequestedCertAttributes();
      for (MappedAttributeType mat : requestedAttributes.getRequestedCertAttributes()) {
        if (!mat.isRequired() || StringUtils.hasText(mat.getDefaultValue())) {
          // For non required attributes or those having a default value there is no requirement to
          // get it from the IdP or AA.
          continue;
        }
        boolean attrDelivered = false;
        for (PreferredSAMLAttributeNameType attr : mat.getSamlAttributeNames()) {
          if (attributes.stream().filter(a -> a.getName().equals(attr.getValue())).findFirst().isPresent()) {
            log.trace("{}: Requested attribute '{}' was delivered by IdP/AA [request-id='{}']",
              CorrelationID.id(), attr.getValue(), signRequest.getRequestID());
            attrDelivered = true;
            break;
          }
        }
        if (!attrDelivered) {
          final String msg = String.format(
            "None of the requested attribute(s) %s were delivered in SignerAssertionInfo/AttributeStatement [request-id='%s']",
            mat.getSamlAttributeNames().stream().map(a -> a.getValue()).collect(Collectors.toList()), signRequest.getRequestID());
          log.error("{}: {}", CorrelationID.id(), msg);
          throw new SignResponseProcessingException(new ErrorCode.Code("invalid-response"), msg);
        }
      }
    }
    return attributes;
  }

  /**
   * Validates that the received authentication instant is OK.
   * 
   * @param contextInfo
   *          the context info holding the authn instant
   * @param signRequest
   *          the sign request
   * @param signResponse
   *          the sign response
   * @throws SignServiceIntegrationException
   *           for validation errors
   */
  protected long processAuthenticationInstant(final ContextInfo contextInfo, final SignRequestWrapper signRequest,
      final SignResponseWrapper signResponse)
      throws SignServiceIntegrationException {

    if (contextInfo.getAuthenticationInstant() == null) {
      final String msg =
          String.format("No SignerAssertionInfo/ContextInfo/AuthenticationInstant available in SignResponse [request-id='%s']",
            signRequest.getRequestID());
      log.error("{}: {}", CorrelationID.id(), msg);
      throw new SignServiceProtocolException(msg);
    }
    final long authnInstant = contextInfo.getAuthenticationInstant().toGregorianCalendar().getTimeInMillis();
    final long requestTime = signRequest.getSignRequestExtension().getRequestTime().toGregorianCalendar().getTimeInMillis();
    final long responseTime = signResponse.getSignResponseExtension().getResponseTime().toGregorianCalendar().getTimeInMillis();

    if ((authnInstant + this.processingConfig.getAllowedClockSkew()) < requestTime) {
      final String msg = String.format("Invalid authentication instant (%d). It is before the SignRequest was sent (%d) [request-id='%s']",
        authnInstant, requestTime, signRequest.getRequestID());
      log.error("{}: {}", CorrelationID.id(), msg);
      throw new SignResponseProcessingException(new ErrorCode.Code("invalid-response"), msg);
    }
    if ((authnInstant - this.processingConfig.getAllowedClockSkew()) > responseTime) {
      final String msg = String.format("Invalid authentication instant (%d). It is after the SignResponse time (%d) [request-id='%s']",
        authnInstant, responseTime, signRequest.getRequestID());
      log.error("{}: {}", CorrelationID.id(), msg);
      throw new SignResponseProcessingException(new ErrorCode.Code("invalid-response"), msg);
    }
    return authnInstant;
  }

  protected String processAuthnContextClassRef(final ContextInfo contextInfo, final SignRequestWrapper signRequest)
      throws SignServiceIntegrationException {

    final String authnContextClassRef = contextInfo.getAuthnContextClassRef();
    if (!StringUtils.hasText(authnContextClassRef)) {
      final String msg = String.format(
        "No SignerAssertionInfo/ContextInfo/AuthnContextClassRef available in SignResponse [request-id='%s']", signRequest.getRequestID());
      log.error("{}: {}", CorrelationID.id(), msg);
      throw new SignServiceProtocolException(msg);
    }

    // Get hold of the LoA from the request.
    //
    final String requestedAuthnContextClassRef = signRequest.getSignRequestExtension().getCertRequestProperties().getAuthnContextClassRef();

    if (authnContextClassRef.equals(requestedAuthnContextClassRef)) {
      // OK if:
      // If SM was required: signMessageDigest is present
      // If not required - OK always

      // AdditionalCheck: check hash
    }
    else {
      // OK if:
      // sigMessageUris allowed
      // Mapping exists
      // SignMessage was sent
    }

    return authnContextClassRef;
  }

  /**
   * Assigns the processing config settings.
   * 
   * @param processingConfig
   *          the processing config settings
   */
  public void setProcessingConfig(final SignResponseProcessingConfig processingConfig) {
    this.processingConfig = processingConfig;
  }

  /** {@inheritDoc} */
  @Override
  public void afterPropertiesSet() throws Exception {
    if (this.processingConfig == null) {
      this.processingConfig = SignResponseProcessingConfig.defaultSignResponseProcessingConfig();
    }
  }

}